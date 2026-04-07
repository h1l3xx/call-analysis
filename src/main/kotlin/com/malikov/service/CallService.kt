package com.malikov.service

import com.malikov.config.NotFoundException
import com.malikov.db.*
import com.malikov.dto.*
import com.malikov.pipeline.PipelineCriterionInput
import com.malikov.pipeline.PipelineSpeakerTurn
import com.malikov.pipeline.PipelineService
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(CallService::class.java)

    fun list(
        schema: String,
        params: PaginationParams,
        status: String? = null,
        managerId: UUID? = null,
        managerIds: List<UUID>? = null,
        departmentId: UUID? = null,
        search: String? = null,
    ): PaginatedResponse<CallResponse> {
        val (rows, total) = callRepo.list(schema, params.offset, params.pageSize, status, managerId, managerIds, departmentId, search)
        val enriched = enrichSecondManagerNames(schema, rows)
        val shared = resolveSharedExtensions(schema, enriched)
        return paginated(enriched.mapIndexed { i, row -> row.toResponse(shared[i]) }, total, params)
    }

    fun getById(schema: String, callId: UUID): CallDetailResponse {
        val row = callRepo.findById(schema, callId) ?: throw NotFoundException("Call not found")
        val enriched = enrichSecondManagerNames(schema, listOf(row)).first()
        val shared = resolveSharedExtensions(schema, listOf(enriched))
        return enriched.toDetailResponse(shared[0])
    }

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
        } catch (e: Exception) {
            log.error("Failed to save audio for call {} (schema={}): {}", callId, schema, e.message, e)
        }

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
        var intCount = 0; var extInCount = 0; var extOutCount = 0; var unkCount = 0

        data class ParsedFile(
            val audioFile: File, val filename: String,
            val callType: CallType, val callTypeStr: String,
            val matchedId: String?, val manager: ManagerRow?,
            val secondManager: ManagerRow?,
            val dedupKey: String?,
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

            val secondMgr = if (ct == CallType.INTERNAL && match?.second != null) {
                val allExts = PhoneParser.extractAllPbxExtensions(filename)
                val allMgrs = managerRepo.findAllByExtensions(schema, allExts)
                allMgrs.firstOrNull { it.extension != match.second.extension }
            } else null

            val dedupKey = PhoneParser.extractInternalCallKey(filename)

            ParsedFile(audioFile, filename, ct, ctStr, match?.first, match?.second, secondMgr, dedupKey)
        }

        val seenInternalKeys = mutableSetOf<String>()
        val deduped = mutableListOf<ParsedFile>()
        val skippedDupes = mutableListOf<ParsedFile>()

        for (p in parsed) {
            if (p.dedupKey != null && !seenInternalKeys.add(p.dedupKey)) {
                skippedDupes.add(p)
                intCount--
            } else {
                deduped.add(p)
            }
        }

        val actualTotal = deduped.size
        val typeStats = CallTypeStatsJson(intCount, extInCount, extOutCount, unkCount)
        val batchId = batchRepo.create(schema, actualTotal, typeStats)

        val results = mutableListOf<BulkUploadItemResult>()
        var queued = 0; var failed = 0
        val queuedCallIds = mutableListOf<UUID>()
        val queuedFiles = mutableListOf<File>()

        for (dup in skippedDupes) {
            results.add(BulkUploadItemResult(
                filename = dup.filename, status = "skipped",
                phone = dup.matchedId, callType = dup.callTypeStr,
                error = "Дубликат внутреннего звонка (запись с другого аппарата)",
            ))
            dup.audioFile.delete()
        }

        for (p in deduped) {
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
                    secondManagerId = p.secondManager?.id,
                )

                val ext = p.filename.substringAfterLast('.', "wav").lowercase()
                try {
                    val audioKey = audioStorage.save(schema, callId, ext, p.audioFile)
                    callRepo.updateAudioKey(schema, callId, audioKey)
                } catch (e: Exception) {
                    log.error("Failed to save audio for call {} (schema={}): {}", callId, schema, e.message, e)
                }

                queuedCallIds.add(callId)
                queuedFiles.add(p.audioFile)
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

        if (queued != actualTotal) {
            batchRepo.updateTotalCalls(schema, batchId, queued)
        }

        batchProcessingService.startBatchProcessing(
            schema = schema, batchId = batchId,
            callFiles = queuedCallIds.zip(queuedFiles),
        )

        return BulkUploadResponse(
            batchId = batchId.toString(), total = files.size,
            queued = queued, failed = failed, items = results,
        )
    }

    fun listDepartments(schema: String): List<Map<String, String>> = transaction {
        val d = com.malikov.db.TDepartments(schema)
        d.selectAll().where { d.isActive eq true }
            .orderBy(d.name)
            .map { mapOf("id" to it[d.id].toString(), "name" to it[d.name]) }
    }

    fun getStats(schema: String, managerId: UUID? = null): Map<String, Long> {
        val byStatus = callRepo.countByStatus(schema, managerId)
        val processing = listOf("queued", "processing", "analyzing", "transcribing")
            .sumOf { byStatus[it] ?: 0L }
        val done = listOf("done", "transcribed_only", "no_speech")
            .sumOf { byStatus[it] ?: 0L }
        val failed = byStatus["failed"] ?: 0L
        val total = byStatus.values.sum()
        val noSpeech = byStatus["no_speech"] ?: 0L
        return mapOf(
            "total" to total, "processing" to processing,
            "done" to done, "failed" to failed, "noSpeech" to noSpeech,
        )
    }

    fun getResult(schema: String, callId: UUID): CallResultResponse {
        val result = callRepo.findResult(schema, callId)
            ?: throw NotFoundException("Call not found")
        return result.toResultResponse()
    }

    fun getManagerIdByUserId(schema: String, userId: UUID): UUID? =
        managerRepo.findByUserId(schema, userId)?.id

    /**
     * For calls missing secondManagerId (uploaded before V16), resolves it from the filename.
     * Also resolves secondManagerName from the DB.
     */
    private fun enrichSecondManagerNames(schema: String, rows: List<CallRow>): List<CallRow> {
        val enriched = rows.map { row ->
            if (row.secondManagerId != null) return@map row
            val filename = row.audioFilename ?: return@map row
            val allExts = PhoneParser.extractAllPbxExtensions(filename)
            if (allExts.size < 2) return@map row
            val allMgrs = managerRepo.findAllByExtensions(schema, allExts)
            val primaryMgr = allMgrs.firstOrNull { it.id == row.managerId }
            val second = if (primaryMgr?.extension != null)
                allMgrs.firstOrNull { it.extension != primaryMgr.extension }
            else
                allMgrs.firstOrNull { it.id != row.managerId }
            if (second != null) row.copy(secondManagerId = second.id, secondManagerName = second.fullName)
            else row
        }
        val idsToResolve = enriched.filter { it.secondManagerId != null && it.secondManagerName == null }
            .mapNotNull { it.secondManagerId }.distinct()
        if (idsToResolve.isEmpty()) return enriched
        val names = callRepo.resolveManagerNames(schema, idsToResolve)
        return enriched.map { row ->
            if (row.secondManagerId != null && row.secondManagerName == null)
                row.copy(secondManagerName = names[row.secondManagerId])
            else row
        }
    }

    data class SharedInfo(
        val participantNames: List<String>?,
        val secondParticipantNames: List<String>?,
    )

    /**
     * For each call, checks if its manager(s) share extensions with other employees.
     * Returns a list parallel to [rows] with non-null participantNames when the extension is shared.
     */
    private fun resolveSharedExtensions(schema: String, rows: List<CallRow>): List<SharedInfo> {
        val allMgrIds = (rows.mapNotNull { it.managerId } + rows.mapNotNull { it.secondManagerId }).distinct()
        if (allMgrIds.isEmpty()) return rows.map { SharedInfo(null, null) }
        val sharedMap = managerRepo.findSharedExtensionNames(schema, allMgrIds)
        return rows.map { row ->
            SharedInfo(
                participantNames = row.managerId?.let { sharedMap[it] },
                secondParticipantNames = row.secondManagerId?.let { sharedMap[it] },
            )
        }
    }

    private fun CallRow.toResponse(shared: SharedInfo = SharedInfo(null, null)) = CallResponse(
        id                     = id.toString(),
        managerId              = managerId?.toString(),
        managerName            = managerName,
        secondManagerId        = secondManagerId?.toString(),
        secondManagerName      = secondManagerName,
        participantNames       = shared.participantNames,
        secondParticipantNames = shared.secondParticipantNames,
        scriptId               = scriptId?.toString(),
        scriptName             = scriptName,
        status                 = status,
        source                 = source,
        callType               = callType,
        batchId                = batchId?.toString(),
        durationSeconds        = durationSeconds,
        createdAt              = createdAt,
        finishedAt             = finishedAt,
    )

    private fun CallRow.toDetailResponse(shared: SharedInfo = SharedInfo(null, null)) = CallDetailResponse(
        id                     = id.toString(),
        managerId              = managerId?.toString(),
        managerName            = managerName,
        secondManagerId        = secondManagerId?.toString(),
        secondManagerName      = secondManagerName,
        participantNames       = shared.participantNames,
        secondParticipantNames = shared.secondParticipantNames,
        scriptId               = scriptId?.toString(),
        scriptName             = scriptName,
        status                 = status,
        source                 = source,
        callType               = callType,
        batchId                = batchId?.toString(),
        audioS3Key             = audioS3Key,
        audioFilename          = audioFilename,
        durationSeconds        = durationSeconds,
        failedStep             = failedStep,
        errorMessage           = errorMessage,
        createdAt              = createdAt,
        finishedAt             = finishedAt,
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
