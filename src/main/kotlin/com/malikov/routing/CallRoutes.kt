package com.malikov.routing

import com.malikov.auth.Role
import com.malikov.config.ForbiddenException
import com.malikov.config.NotFoundException
import com.malikov.dto.CreateCallRequest
import com.malikov.service.AudioStorageService
import com.malikov.service.CallService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import java.io.File
import java.util.UUID

private val ALLOWED_AUDIO_EXTENSIONS = setOf("wav", "mp3", "ogg", "flac", "m4a", "webm", "opus")
private const val MAX_AUDIO_SIZE_BYTES = 100L * 1024 * 1024  // 100 MB
private const val MAX_BULK_FILES = 500

fun Route.callRoutes(service: CallService, audioStorage: AudioStorageService) {
    route("/calls") {

        // ── Создание звонка (без аудио, для ручного / внешнего pipeline) ──
        post {
            val p = requireTenantRole(Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val request = call.receive<CreateCallRequest>()
            val result = service.create(p.schema!!, request)
            call.respond(HttpStatusCode.Created, result)
        }

        // ── Upload аудио → создание звонка + автоматический анализ ──
        post("/upload") {
            val p = requireTenantRole(Role.TEAM_LEAD, Role.CLIENT_ADMIN)

            var managerId: UUID? = null
            var scriptId: UUID? = null
            var audioFile: File? = null
            var originalFilename: String? = null
            var submittedToPipeline = false

            try {
                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            when (part.name) {
                                "managerId" -> managerId = UUID.fromString(part.value)
                                "scriptId"  -> scriptId  = UUID.fromString(part.value)
                            }
                        }
                        is PartData.FileItem -> {
                            if (part.name == "file") {
                                originalFilename = part.originalFileName ?: "audio.wav"

                                // Проверяем расширение
                                val ext = originalFilename!!.substringAfterLast('.', "").lowercase()
                                require(ext in ALLOWED_AUDIO_EXTENSIONS) {
                                    "Unsupported audio format: .$ext. Allowed: ${ALLOWED_AUDIO_EXTENSIONS.joinToString()}"
                                }

                                // Сохраняем во временный файл
                                val tempFile = File.createTempFile("malikov_", ".$ext")
                                part.streamProvider().use { input ->
                                    tempFile.outputStream().buffered().use { output ->
                                        input.copyTo(output)
                                    }
                                }

                                // Проверяем размер
                                require(tempFile.length() <= MAX_AUDIO_SIZE_BYTES) {
                                    tempFile.delete()
                                    "Audio file too large: ${tempFile.length() / 1024 / 1024}MB. Max: ${MAX_AUDIO_SIZE_BYTES / 1024 / 1024}MB"
                                }

                                audioFile = tempFile
                            }
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                // Валидация
                requireNotNull(managerId)  { "managerId is required" }
                requireNotNull(scriptId)   { "scriptId is required" }
                requireNotNull(audioFile)  { "Audio file is required" }

                val result = service.createWithAudio(
                    schema    = p.schema!!,
                    managerId = managerId!!,
                    scriptId  = scriptId!!,
                    audioFile = audioFile!!,
                    filename  = originalFilename!!,
                )
                submittedToPipeline = true

                call.respond(HttpStatusCode.Accepted, result)
            } finally {
                // If request fails before pipeline takes ownership, clean up temp file.
                if (!submittedToPipeline) {
                    try {
                        audioFile?.let { f ->
                            if (f.exists()) f.delete()
                        }
                    } catch (_: Exception) {
                        // best-effort cleanup
                    }
                }
            }
        }

        // ── Массовая загрузка: авто-классификация, без scriptId ──
        post("/bulk-upload") {
            val p = requireTenantRole(Role.CLIENT_ADMIN)

            val audioFiles = mutableListOf<Pair<File, String>>()

            try {
                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            if (part.name == "files" || part.name == "file") {
                                val origName = part.originalFileName ?: "audio.wav"
                                val ext = origName.substringAfterLast('.', "").lowercase()

                                if (ext in ALLOWED_AUDIO_EXTENSIONS && audioFiles.size < MAX_BULK_FILES) {
                                    val tempFile = File.createTempFile("bulk_", ".$ext")
                                    part.streamProvider().use { input ->
                                        tempFile.outputStream().buffered().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    if (tempFile.length() <= MAX_AUDIO_SIZE_BYTES) {
                                        audioFiles.add(tempFile to origName)
                                    } else {
                                        tempFile.delete()
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                require(audioFiles.isNotEmpty()) { "At least one audio file is required" }

                val result = service.createBulkWithAudio(
                    schema = p.schema!!,
                    files  = audioFiles,
                )

                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                audioFiles.forEach { (f, _) ->
                    runCatching { if (f.exists()) f.delete() }
                }
                throw e
            }
        }

        // ── Статистика по статусам ──
        get("/stats") {
            val p = requireTenantRole(Role.MANAGER, Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val managerId = if (p.roleEnum == Role.MANAGER) {
                service.getManagerIdByUserId(p.schema!!, UUID.fromString(p.userId))
            } else null
            call.respond(service.getStats(p.schema!!, managerId))
        }

        // ── Список звонков ──
        get {
            val p = requireTenantRole(Role.MANAGER, Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val params = paginationParams()
            val status = call.parameters["status"]

            val managerId = if (p.roleEnum == Role.MANAGER) {
                service.getManagerIdByUserId(p.schema!!, UUID.fromString(p.userId))
                    ?: throw NotFoundException("Manager profile not found")
            } else {
                call.parameters["managerId"]?.let { UUID.fromString(it) }
            }

            call.respond(service.list(p.schema!!, params, status, managerId))
        }

        // ── Детали звонка ──
        get("/{id}") {
            val p = requireTenantRole(Role.MANAGER, Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val callId = pathUuid("id")
            val detail = service.getById(p.schema!!, callId)

            if (p.roleEnum == Role.MANAGER) {
                val myManagerId = service.getManagerIdByUserId(p.schema!!, UUID.fromString(p.userId))
                    ?: throw NotFoundException("Manager profile not found")
                if (detail.managerId != myManagerId.toString()) throw ForbiddenException("Access denied")
            }

            call.respond(detail)
        }

        // ── Результат анализа ──
        get("/{id}/result") {
            val p = requireTenantRole(Role.MANAGER, Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val callId = pathUuid("id")

            if (p.roleEnum == Role.MANAGER) {
                val detail = service.getById(p.schema!!, callId)
                val myManagerId = service.getManagerIdByUserId(p.schema!!, UUID.fromString(p.userId))
                    ?: throw NotFoundException("Manager profile not found")
                if (detail.managerId != myManagerId.toString()) throw ForbiddenException("Access denied")
            }

            call.respond(service.getResult(p.schema!!, callId))
        }

        // ── Аудиозапись звонка (стриминг с поддержкой Range) ──
        get("/{id}/audio") {
            val p = requireTenantRole(Role.MANAGER, Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val callId = pathUuid("id")
            val detail = service.getById(p.schema!!, callId)

            if (p.roleEnum == Role.MANAGER) {
                val myManagerId = service.getManagerIdByUserId(p.schema!!, UUID.fromString(p.userId))
                    ?: throw NotFoundException("Manager profile not found")
                if (detail.managerId != myManagerId.toString()) throw ForbiddenException("Access denied")
            }

            val audioKey = detail.audioS3Key
                ?: throw NotFoundException("Аудиозапись отсутствует")

            val audioFile = audioStorage.getFile(audioKey)
                ?: throw NotFoundException("Аудиофайл удалён")

            val contentType = when (audioKey.substringAfterLast('.').lowercase()) {
                "mp3"  -> ContentType.Audio.MPEG
                "wav"  -> ContentType.Audio.Any
                "ogg"  -> ContentType.Audio.OGG
                "flac" -> ContentType.Audio.Any
                "m4a"  -> ContentType.Audio.Any
                "webm" -> ContentType("audio", "webm")
                "opus" -> ContentType("audio", "opus")
                else   -> ContentType.Application.OctetStream
            }

            call.respondFile(audioFile)
        }
    }
}
