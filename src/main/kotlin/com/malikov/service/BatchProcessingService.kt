package com.malikov.service

import com.malikov.db.*
import com.malikov.pipeline.PipelineClient
import com.malikov.pipeline.PipelineCriterionInput
import com.malikov.pipeline.PipelineException
import com.malikov.pipeline.PipelineResultWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Двухфазная оркестрация обработки батча:
 *   Phase A — транскрипция всех звонков (pipeline без quality analysis)
 *   Phase B — оценка LLM (внешние → по скрипту, внутренние → специальный промпт)
 *   Phase C — суммаризация батча
 */
class BatchProcessingService(
    private val pipelineClient: PipelineClient,
    private val resultWriter: PipelineResultWriter,
    private val batchRepo: BatchRepository,
    private val callRepo: CallRepository,
    private val scriptRepo: ScriptRepository,
    private val internalCallEvaluator: InternalCallEvaluator,
    private val batchSummaryService: BatchSummaryService,
    maxConcurrency: Int = 3,
) {
    private val log = LoggerFactory.getLogger(BatchProcessingService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(maxConcurrency)

    fun startBatchProcessing(
        schema: String,
        batchId: UUID,
        callFiles: List<Pair<UUID, File>>,
    ) {
        scope.launch {
            try {
                batchRepo.updateStatus(schema, batchId, "transcribing")

                // Phase A: transcribe all calls (no quality analysis)
                val transcribedIds = phaseTranscribe(schema, batchId, callFiles)

                if (transcribedIds.isEmpty()) {
                    batchRepo.updateStatus(schema, batchId, "failed")
                    return@launch
                }

                // Phase B: LLM evaluation
                batchRepo.updateStatus(schema, batchId, "evaluating")
                phaseEvaluate(schema, batchId, transcribedIds)

                // Phase C: summary
                batchRepo.updateStatus(schema, batchId, "summarizing")
                batchSummaryService.generateBatchSummary(schema, batchId)

                batchRepo.updateStatus(schema, batchId, "done")
                log.info("Batch {} fully processed", batchId)
            } catch (e: Exception) {
                log.error("Batch {} processing failed", batchId, e)
                batchRepo.updateStatus(schema, batchId, "failed")
            }
        }
    }

    /**
     * Phase A: send each audio to pipeline for transcription only (no quality).
     * Returns list of successfully transcribed call IDs.
     */
    private suspend fun phaseTranscribe(
        schema: String,
        batchId: UUID,
        callFiles: List<Pair<UUID, File>>,
    ): List<UUID> {
        val transcribed = mutableListOf<UUID>()

        coroutineScope {
            callFiles.map { (callId, audioFile) ->
                async {
                    semaphore.acquire()
                    try {
                        transcribeSingle(schema, batchId, callId, audioFile)
                    } finally {
                        semaphore.release()
                    }
                }
            }.forEach { deferred ->
                val callId = deferred.await()
                if (callId != null) {
                    synchronized(transcribed) { transcribed.add(callId) }
                }
            }
        }

        return transcribed
    }

    private suspend fun transcribeSingle(
        schema: String,
        batchId: UUID,
        callId: UUID,
        audioFile: File,
    ): UUID? {
        log.info("Phase A: transcribing call {} in batch {}", callId, batchId)
        return try {
            resultWriter.markProcessing(schema, callId)

            // Pipeline call WITHOUT criteria = transcription only (quality field will be null)
            val response = pipelineClient.analyze(audioFile, criteria = null)

            resultWriter.saveTranscriptionOnly(schema, callId, response)
            batchRepo.incrementProcessed(schema, batchId)

            log.info("Call {} transcribed (result_id={})", callId, response.resultId)
            callId
        } catch (e: PipelineException) {
            log.error("Transcription failed for call {}: [{}] {}", callId, e.statusCode, e.detail)
            resultWriter.markFailed(schema, callId, "transcription", "Pipeline [${e.statusCode}]: ${e.detail}")
            batchRepo.incrementProcessed(schema, batchId)
            null
        } catch (e: Exception) {
            log.error("Transcription error for call {}", callId, e)
            resultWriter.markFailed(schema, callId, "transcription", e.message ?: "Unknown error")
            batchRepo.incrementProcessed(schema, batchId)
            null
        } finally {
            try { if (audioFile.exists()) audioFile.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Phase B: LLM evaluation.
     * - External calls → auto-select script, send criteria
     * - Internal calls → specialized evaluation prompts
     */
    private suspend fun phaseEvaluate(
        schema: String,
        batchId: UUID,
        callIds: List<UUID>,
    ) {
        coroutineScope {
            callIds.map { callId ->
                async {
                    semaphore.acquire()
                    try {
                        evaluateSingle(schema, callId)
                    } finally {
                        semaphore.release()
                    }
                }
            }.forEach { it.await() }
        }
    }

    private suspend fun evaluateSingle(schema: String, callId: UUID) {
        val call = callRepo.findById(schema, callId) ?: return
        val callType = call.callType ?: "unknown"

        log.info("Phase B: evaluating call {} (type={})", callId, callType)

        try {
            val transcription = getTranscription(schema, callId)
            if (transcription.isNullOrBlank()) {
                log.warn("No transcription for call {}, skipping evaluation", callId)
                return
            }

            markAnalyzing(schema, callId)

            when {
                callType == "internal" -> {
                    internalCallEvaluator.evaluate(schema, callId, transcription)
                }
                callType == "external" -> {
                    evaluateExternalCall(schema, callId, transcription)
                }
                else -> {
                    // Unknown type: try external evaluation with default script
                    evaluateExternalCall(schema, callId, transcription)
                }
            }

            markDone(schema, callId)
        } catch (e: Exception) {
            log.error("Evaluation failed for call {}", callId, e)
            resultWriter.markFailed(schema, callId, "evaluation", e.message ?: "Unknown error")
        }
    }

    private fun evaluateExternalCall(schema: String, callId: UUID, transcription: String) {
        val scriptDetail = scriptRepo.findDefault(schema)

        if (scriptDetail != null) {
            val criteria = scriptDetail.criteria.filter { it.isActive }.map { cr ->
                PipelineCriterionInput(
                    id = cr.orderNum,
                    name = cr.name,
                    description = cr.description,
                    block = if (cr.groupType == "required") "main" else "additional",
                )
            }

            val qualityJson = internalCallEvaluator.evaluateWithCriteria(
                transcription, criteria, scriptDetail.script.name
            )

            resultWriter.saveQualityFromJson(
                schema, callId, scriptDetail.script.id, qualityJson
            )
        } else {
            log.warn("No script found for external call {}, evaluating as generic", callId)
            internalCallEvaluator.evaluate(schema, callId, transcription)
        }
    }

    private fun getTranscription(schema: String, callId: UUID): String? = transaction {
        val t = TTranscriptions(schema)
        t.selectAll().where { t.callId eq callId }.singleOrNull()
            ?.let { it[t.cleanedText] ?: it[t.rawText] }
    }

    private fun markAnalyzing(schema: String, callId: UUID) = transaction {
        val cl = TCalls(schema)
        cl.update({ cl.id eq callId }) { it[cl.status] = "analyzing" }
    }

    private fun markDone(schema: String, callId: UUID) = transaction {
        val cl = TCalls(schema)
        cl.update({ cl.id eq callId }) {
            it[cl.status] = "done"
            it[cl.finishedAt] = System.currentTimeMillis()
        }
    }

    fun shutdown() {
        scope.cancel("Application shutdown")
    }
}
