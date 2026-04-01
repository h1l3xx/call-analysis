package com.malikov.service

import com.malikov.config.NotFoundException
import com.malikov.db.*
import com.malikov.dto.*
import com.malikov.pipeline.PipelineCriterionInput
import com.malikov.pipeline.PipelineSpeakerTurn
import com.malikov.pipeline.PipelineService
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class CallService(
    private val callRepo: CallRepository,
    private val managerRepo: ManagerRepository,
    private val scriptRepo: ScriptRepository,
    private val pipelineService: PipelineService,
    private val batchRepo: BatchRepository,
    private val batchProcessingService: BatchProcessingService,
    private val audioStorage: AudioStorageService,
) {

    fun list(
        schema: String,
        params: PaginationParams,
        status: String? = null,
        managerId: UUID? = null,
    ): PaginatedResponse<CallResponse> {
        val (rows, total) = callRepo.list(schema, params.offset, params.pageSize, status, managerId)
        return paginated(rows.map { it.toResponse() }, total, params)
    }

    fun getById(schema: String, callId: UUID): CallDetailResponse =
        callRepo.findById(schema, callId)?.toDetailResponse()
            ?: throw NotFoundException("Call not found")

    fun create(schema: String, request: CreateCallRequest): CallResponse {
        val managerId = UUID.fromString(request.managerId)
        val scriptId  = UUID.fromString(request.scriptId)

        managerRepo.findById(schema, managerId)
            ?: throw IllegalArgumentException("Manager not found")

        val callId = callRepo.create(
            schema        = schema,
            managerId     = managerId,
            scriptId      = scriptId,
            source        = request.source,
            audioS3Key    = request.audioS3Key,
            audioFilename = request.audioFilename,
        )

        return callRepo.findById(schema, callId)!!.toResponse()
    }

    /**
     * Создаёт запись звонка и запускает асинхронную обработку через AI pipeline.
     *
     * @param schema       tenant-схема БД
     * @param managerId    UUID менеджера
     * @param scriptId     UUID скрипта оценки
     * @param audioFile    временный файл с аудиозаписью
     * @param filename     оригинальное имя файла от клиента
     * @return CallResponse со статусом "queued"
     */
    fun createWithAudio(
        schema: String,
        managerId: UUID,
        scriptId: UUID,
        audioFile: File,
        filename: String,
    ): CallResponse {
        managerRepo.findById(schema, managerId)
            ?: throw IllegalArgumentException("Manager not found")

        val criteria = scriptRepo.findById(schema, scriptId)
            ?.criteria
            ?.filter { it.isActive }
            ?.map { cr ->
                PipelineCriterionInput(
                    id          = cr.orderNum,
                    name        = cr.name,
                    description = cr.description,
                    block       = if (cr.groupType == "required") "main" else "additional",
                )
            }

        val callId = callRepo.create(
            schema        = schema,
            managerId     = managerId,
            scriptId      = scriptId,
            source        = "upload",
            audioS3Key    = null,
            audioFilename = filename,
        )

        val ext = filename.substringAfterLast('.', "wav").lowercase()
        try {
            val audioKey = audioStorage.save(schema, callId, ext, audioFile)
            callRepo.updateAudioKey(schema, callId, audioKey)
        } catch (_: Exception) { /* non-critical: playback unavailable but processing continues */ }

        pipelineService.submitAsync(schema, callId, scriptId, audioFile, criteria)

        return callRepo.findById(schema, callId)!!.toResponse()
    }

    /**
     * Массовая загрузка: без scriptId, авто-классификация внутренний/внешний.
     * Создаёт батч, определяет менеджера и тип звонка из имени файла,
     * запускает двухфазную обработку через BatchProcessingService.
     */
    fun createBulkWithAudio(
        schema: String,
        files: List<Pair<File, String>>,
    ): BulkUploadResponse {
        val stats = CallTypeStatsJson()
        var intCount = 0; var extInCount = 0; var extOutCount = 0; var unkCount = 0

        data class ParsedFile(
            val audioFile: File, val filename: String,
            val callType: CallType, val callTypeStr: String,
            val matchedId: String?, val manager: ManagerRow?,
        )

        val parsed = files.map { (audioFile, filename) ->
            val ct = PhoneParser.detectCallType(filename)
            val ctStr = when (ct) {
                CallType.INTERNAL -> { intCount++; "internal" }
                CallType.EXTERNAL_INCOMING -> { extInCount++; "external" }
                CallType.EXTERNAL_OUTGOING -> { extOutCount++; "external" }
                CallType.UNKNOWN -> { unkCount++; "unknown" }
            }
            val candidates = PhoneParser.extractManagerIdentifiers(filename)
            val match = managerRepo.findFirstByIdentifiers(schema, candidates)
            ParsedFile(audioFile, filename, ct, ctStr, match?.first, match?.second)
        }

        val typeStats = CallTypeStatsJson(intCount, extInCount, extOutCount, unkCount)
        val batchId = batchRepo.create(schema, files.size, typeStats)

        val results = mutableListOf<BulkUploadItemResult>()
        var queued = 0; var failed = 0
        val queuedCallIds = mutableListOf<UUID>()

        for (p in parsed) {
            if (p.manager == null) {
                failed++
                val candidates = PhoneParser.extractManagerIdentifiers(p.filename)
                results.add(BulkUploadItemResult(
                    filename = p.filename, status = "skipped",
                    phone = candidates.firstOrNull(), callType = p.callTypeStr,
                    error = when {
                        candidates.isEmpty() -> "Не удалось определить номер менеджера из имени файла"
                        else -> "Менеджер не найден (пробовали: ${candidates.joinToString(", ")})"
                    },
                ))
                p.audioFile.delete()
                continue
            }
            try {
                val callId = callRepo.create(
                    schema = schema, managerId = p.manager.id,
                    source = "bulk_upload", audioS3Key = null,
                    audioFilename = p.filename, batchId = batchId,
                    callType = p.callTypeStr,
                )

                val ext = p.filename.substringAfterLast('.', "wav").lowercase()
                try {
                    val audioKey = audioStorage.save(schema, callId, ext, p.audioFile)
                    callRepo.updateAudioKey(schema, callId, audioKey)
                } catch (_: Exception) { /* non-critical */ }

                queuedCallIds.add(callId)
                queued++
                results.add(BulkUploadItemResult(
                    filename = p.filename, status = "queued",
                    callId = callId.toString(), managerId = p.manager.id.toString(),
                    managerName = p.manager.fullName, phone = p.matchedId,
                    callType = p.callTypeStr,
                ))
            } catch (e: Exception) {
                failed++
                results.add(BulkUploadItemResult(
                    filename = p.filename, status = "error",
                    phone = p.matchedId, managerId = p.manager.id.toString(),
                    managerName = p.manager.fullName, callType = p.callTypeStr,
                    error = e.message ?: "Unknown error",
                ))
                p.audioFile.delete()
            }
        }

        batchProcessingService.startBatchProcessing(
            schema = schema, batchId = batchId,
            callFiles = queuedCallIds.zip(parsed.filter { it.manager != null }.map { it.audioFile }),
        )

        return BulkUploadResponse(
            batchId = batchId.toString(), total = files.size,
            queued = queued, failed = failed, items = results,
        )
    }

    fun getResult(schema: String, callId: UUID): CallResultResponse {
        val result = callRepo.findResult(schema, callId)
            ?: throw NotFoundException("Call not found")
        return result.toResultResponse()
    }

    fun getManagerIdByUserId(schema: String, userId: UUID): UUID? =
        managerRepo.findByUserId(schema, userId)?.id

    private fun CallRow.toResponse() = CallResponse(
        id              = id.toString(),
        managerId       = managerId?.toString(),
        managerName     = managerName,
        scriptId        = scriptId?.toString(),
        scriptName      = scriptName,
        status          = status,
        source          = source,
        callType        = callType,
        batchId         = batchId?.toString(),
        durationSeconds = durationSeconds,
        createdAt       = createdAt,
        finishedAt      = finishedAt,
    )

    private fun CallRow.toDetailResponse() = CallDetailResponse(
        id              = id.toString(),
        managerId       = managerId?.toString(),
        managerName     = managerName,
        scriptId        = scriptId?.toString(),
        scriptName      = scriptName,
        status          = status,
        source          = source,
        callType        = callType,
        batchId         = batchId?.toString(),
        audioS3Key      = audioS3Key,
        audioFilename   = audioFilename,
        durationSeconds = durationSeconds,
        failedStep      = failedStep,
        errorMessage    = errorMessage,
        createdAt       = createdAt,
        finishedAt      = finishedAt,
    )

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private fun parseSpeakerTurns(raw: String?): List<SpeakerTurnDto>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val turns = jsonParser.decodeFromString<List<PipelineSpeakerTurn>>(raw)
            turns.map { SpeakerTurnDto(it.speaker, it.text, it.start, it.end) }
        } catch (_: Exception) { null }
    }

    private fun CallResultRow.toResultResponse() = CallResultResponse(
        callId         = call.id.toString(),
        status         = call.status,
        transcription  = transcription?.let {
            TranscriptionResponse(
                rawText        = it.rawText,
                cleanedText    = it.cleanedText,
                language       = it.language,
                languageProb   = it.languageProb,
                classification = it.classification,
                speakerTurns   = parseSpeakerTurns(it.speakerTurns),
            )
        },
        speakerMetrics = speakerMetrics?.let {
            SpeakerMetricsResponse(
                managerTalkRatio    = it.managerTalkRatio,
                clientTalkRatio     = it.clientTalkRatio,
                silenceRatio        = it.silenceRatio,
                interruptionsCount  = it.interruptionsCount,
                avgPauseSeconds     = it.avgPauseSeconds,
                managerWpm          = it.managerWpm,
                clientWpm           = it.clientWpm,
                longestMonologueSec = it.longestMonologueSec,
            )
        },
        qualityScore   = qualityScore?.let {
            QualityScoreResponse(
                overallScore    = it.overallScore,
                requiredScore   = it.requiredScore,
                optionalScore   = it.optionalScore,
                criteria        = it.criteria,
                strengths       = it.strengths,
                weaknesses      = it.weaknesses,
                recommendations = it.recommendations,
                summary         = it.summary,
            )
        },
        errors         = errors.map {
            ErrorEventResponse(
                id            = it.id,
                criterionName = it.criterionName,
                severity      = it.severity,
                status        = it.status,
                score         = it.score,
                comment       = it.comment,
                quote         = it.quote,
            )
        },
    )
}
