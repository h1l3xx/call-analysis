package com.malikov.pipeline

import com.malikov.config.PipelineConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.streams.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * HTTP-клиент для AI pipeline (Python FastAPI сервис).
 *
 * POST /analyze        — отправка аудио + метаданных, блокирующий ответ с результатами
 * GET  /healthz        — проверка доступности pipeline
 * GET  /analyses       — список обработанных анализов (хранятся на стороне pipeline)
 * GET  /analyses/{id}  — детали конкретного анализа
 */
class PipelineClient(
    private val config: PipelineConfig,
    private val apiKey: String? = null,
) {
    private val log = LoggerFactory.getLogger(PipelineClient::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis  = 30_000
        }
        expectSuccess = false
    }

    /**
     * Отправляет аудиофайл в pipeline и возвращает результат анализа.
     * Файл передается через streaming (InputProvider), без загрузки в heap целиком.
     *
     * Timeout отключён: pipeline выполняет ASR + LLM quality analysis,
     * что может занимать от 30 секунд до десятков минут в зависимости от
     * длины аудио и нагрузки. Вызов уже асинхронный (корутина в PipelineService),
     * поэтому не блокирует HTTP-поток пользователя.
     *
     * @param audioFile локальный аудиофайл
     * @param criteria  список критериев скрипта; если передан, pipeline оценивает по ним
     *                  вместо своих файловых шаблонов
     */
    suspend fun analyze(
        audioFile: File,
        criteria: List<PipelineCriterionInput>? = null,
    ): PipelineAnalyzeResponse {
        log.info(
            "Sending audio to pipeline: {} ({} bytes), criteria={}",
            audioFile.name, audioFile.length(), criteria?.size ?: "default",
        )

        val response: HttpResponse = client.submitFormWithBinaryData(
            url = "${config.baseUrl}/analyze",
            formData = formData {
                append("file", InputProvider(audioFile.length()) {
                    audioFile.inputStream().asInput()
                }, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"${audioFile.name}\"")
                    append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                })
                if (criteria != null) {
                    append("criteria", json.encodeToString(criteria))
                }
            }
        ) {
            apiKey?.let { header("X-API-Key", it) }
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                socketTimeoutMillis  = Long.MAX_VALUE
            }
        }

        return handleResponse(response, "analyze")
    }

    // ── Proxy: analyses history (хранится на стороне Python) ─────────

    /**
     * Возвращает список анализов, сохраненных на стороне pipeline.
     */
    suspend fun listAnalyses(
        limit: Int = 20,
        offset: Int = 0,
        query: String? = null,
        hasQuality: Boolean? = null,
    ): PipelineAnalysesListResponse {
        val response: HttpResponse = client.get("${config.baseUrl}/analyses") {
            parameter("limit", limit)
            parameter("offset", offset)
            query?.let { parameter("query", it) }
            hasQuality?.let { parameter("has_quality", it) }
            apiKey?.let { header("X-API-Key", it) }
        }
        return handleResponse(response, "listAnalyses")
    }

    /**
     * Возвращает детали конкретного анализа по result_id.
     */
    suspend fun getAnalysis(resultId: String): PipelineAnalysisDetailResponse {
        val response: HttpResponse = client.get("${config.baseUrl}/analyses/$resultId") {
            apiKey?.let { header("X-API-Key", it) }
        }
        return handleResponse(response, "getAnalysis")
    }

    // ── Health ───────────────────────────────────────────────────────

    suspend fun healthCheck(): PipelineHealthResponse {
        val response: HttpResponse = client.get("${config.baseUrl}/healthz") {
            apiKey?.let { header("X-API-Key", it) }
        }

        if (!response.status.isSuccess()) {
            throw PipelineException(
                statusCode = response.status.value,
                detail = "Pipeline health check failed: ${response.status}",
            )
        }

        return response.body()
    }

    suspend fun isAvailable(): Boolean = try {
        healthCheck().status == "ok"
    } catch (e: Exception) {
        log.warn("Pipeline is unavailable: {}", e.message)
        false
    }

    fun close() {
        client.close()
    }

    // ── internal ────────────────────────────────────────────────────

    private suspend inline fun <reified T> handleResponse(
        response: HttpResponse,
        operation: String,
    ): T {
        val status = response.status.value

        if (response.status.isSuccess()) {
            return response.body()
        }

        val detail = try {
            response.body<PipelineErrorResponse>().detail
        } catch (_: Exception) {
            response.bodyAsText().take(500)
        }

        log.error("Pipeline {} failed [{}]: {}", operation, status, detail)

        throw PipelineException(
            statusCode = status,
            detail = detail,
        )
    }
}

class PipelineException(
    val statusCode: Int,
    val detail: String,
) : RuntimeException("Pipeline error [$statusCode]: $detail")
