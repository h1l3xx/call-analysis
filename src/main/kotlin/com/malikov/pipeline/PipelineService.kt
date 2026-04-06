package com.malikov.pipeline

import com.malikov.db.TCalls
import com.malikov.db.TTranscriptions
import com.malikov.service.InternalCallEvaluator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Two-phase call processing orchestrator:
 *   Phase A — transcription + diarization via Python pipeline
 *   Phase B — LLM quality evaluation via backend (OpenRouter)
 */
class PipelineService(
    private val client: PipelineClient,
    private val resultWriter: PipelineResultWriter,
    private val evaluator: InternalCallEvaluator,
    maxConcurrency: Int = 3,
) {
    private val log = LoggerFactory.getLogger(PipelineService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(maxConcurrency)

    fun submitAsync(
        schema: String,
        callId: UUID,
        scriptId: UUID,
        audioFile: File,
        criteria: List<PipelineCriterionInput>? = null,
    ) {
        scope.launch {
            semaphore.acquire()
            try {
                processCall(schema, callId, scriptId, audioFile, criteria)
            } finally {
                semaphore.release()
            }
        }
    }

    suspend fun processCall(
        schema: String,
        callId: UUID,
        scriptId: UUID,
        audioFile: File,
        criteria: List<PipelineCriterionInput>? = null,
    ) {
        log.info("Starting pipeline processing for call {} [schema={}, script={}, criteria={}]",
            callId, schema, scriptId, criteria?.size ?: "default")

        try {
            resultWriter.markProcessing(schema, callId)

            // Phase A: transcription + diarization
            val response = client.analyze(audioFile, criteria = null)
            log.info("Phase A complete: result_id={} for call {}", response.resultId, callId)

            resultWriter.saveTranscriptionOnly(schema, callId, response)

            // Phase B: LLM quality evaluation
            val transcription = getTranscription(schema, callId)
            if (!transcription.isNullOrBlank()) {
                log.info("Phase B: LLM evaluation for call {} [script={}]", callId, scriptId)
                markStatus(schema, callId, "analyzing")

                if (criteria != null && criteria.isNotEmpty()) {
                    val qualityJson = evaluator.evaluateWithCriteria(transcription, criteria, "script")
                    resultWriter.saveQualityFromJson(schema, callId, scriptId, qualityJson)
                } else {
                    evaluator.evaluate(schema, callId, transcription)
                }

                markDone(schema, callId)
                log.info("Call {} fully processed (transcription + evaluation)", callId)
            } else {
                log.warn("No transcription for call {}, skipping Phase B", callId)
            }

        } catch (e: PipelineException) {
            if (e.statusCode == 422) {
                log.info("Call {} skipped (no speech / bad audio): {}", callId, e.detail)
                resultWriter.markNoSpeech(schema, callId, e.detail)
            } else {
                log.error("Pipeline error for call {}: [{}] {}", callId, e.statusCode, e.detail)
                resultWriter.markFailed(
                    schema       = schema,
                    callId       = callId,
                    failedStep   = "pipeline_analyze",
                    errorMessage = "Pipeline [${e.statusCode}]: ${e.detail}",
                )
            }

        } catch (e: Exception) {
            log.error("Unexpected error processing call {}", callId, e)
            resultWriter.markFailed(
                schema       = schema,
                callId       = callId,
                failedStep   = "pipeline_analyze",
                errorMessage = e.message ?: "Unknown error",
            )

        } finally {
            try {
                if (audioFile.exists()) {
                    audioFile.delete()
                    log.debug("Temp audio file deleted: {}", audioFile.absolutePath)
                }
            } catch (e: Exception) {
                log.warn("Failed to delete temp file {}: {}", audioFile.absolutePath, e.message)
            }
        }
    }

    private fun getTranscription(schema: String, callId: UUID): String? = transaction {
        val t = TTranscriptions(schema)
        t.selectAll().where { t.callId eq callId }.singleOrNull()
            ?.let { it[t.cleanedText] ?: it[t.rawText] }
    }

    private fun markStatus(schema: String, callId: UUID, status: String) = transaction {
        val cl = TCalls(schema)
        cl.update({ cl.id eq callId }) { it[cl.status] = status }
    }

    private fun markDone(schema: String, callId: UUID) = transaction {
        val cl = TCalls(schema)
        cl.update({ cl.id eq callId }) {
            it[cl.status] = "done"
            it[cl.finishedAt] = System.currentTimeMillis()
        }
    }

    // ── Proxy: pipeline-side analyses ────────────────────────────────

    /**
     * Проксирует список анализов, хранящихся на стороне Python pipeline.
     */
    suspend fun listAnalyses(
        limit: Int = 20,
        offset: Int = 0,
        query: String? = null,
        hasQuality: Boolean? = null,
    ): PipelineAnalysesListResponse =
        client.listAnalyses(limit, offset, query, hasQuality)

    /**
     * Проксирует детали конкретного анализа на стороне Python pipeline.
     */
    suspend fun getAnalysis(resultId: String): PipelineAnalysisDetailResponse =
        client.getAnalysis(resultId)

    // ── Health ───────────────────────────────────────────────────────

    suspend fun checkHealth(): PipelineHealthResponse = client.healthCheck()

    suspend fun isAvailable(): Boolean = client.isAvailable()

    fun shutdown() {
        log.info("Shutting down PipelineService...")
        scope.cancel("Application shutdown")
        client.close()
    }
}
