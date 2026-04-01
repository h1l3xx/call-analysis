package com.malikov.pipeline

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Оркестратор обработки звонков через AI pipeline.
 *
 * Жизненный цикл звонка:
 *   queued → processing → (transcribed_only | done | failed)
 *
 * Обработка запускается в фоновой корутине — HTTP-запрос клиента
 * получает ответ сразу после создания записи, не дожидаясь pipeline.
 * Семафор ограничивает параллелизм, чтобы не перегружать pipeline.
 */
class PipelineService(
    private val client: PipelineClient,
    private val resultWriter: PipelineResultWriter,
    maxConcurrency: Int = 3,
) {
    private val log = LoggerFactory.getLogger(PipelineService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(maxConcurrency)

    /**
     * Запускает асинхронную обработку звонка через AI pipeline.
     *
     * @param schema    tenant-схема БД
     * @param callId    ID звонка в tenant-таблице calls
     * @param scriptId  ID скрипта оценки (для записи в quality_scores)
     * @param audioFile локальный аудиофайл (временный)
     * @param criteria  критерии скрипта для передачи в pipeline
     */
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

    /**
     * Синхронная обработка (для тестов / ручного вызова).
     */
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

            val response = client.analyze(audioFile, criteria)
            log.info("Pipeline returned result_id={} for call {}", response.resultId, callId)

            resultWriter.saveResult(schema, callId, scriptId, response)

            log.info("Call {} processed successfully, status={}",
                callId,
                if (response.quality != null) "done" else "transcribed_only"
            )

        } catch (e: PipelineException) {
            log.error("Pipeline error for call {}: [{}] {}", callId, e.statusCode, e.detail)
            resultWriter.markFailed(
                schema       = schema,
                callId       = callId,
                failedStep   = "pipeline_analyze",
                errorMessage = "Pipeline [${e.statusCode}]: ${e.detail}",
            )

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
