package com.malikov.service

import com.malikov.config.PipelineConfig
import com.malikov.db.TQualityScores
import com.malikov.pipeline.PipelineCriterionInput
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Оценивает звонки через LLM (OpenAI-совместимый API).
 *
 * Для внутренних звонков: анализ бизнес-процессов + качество коммуникации.
 * Для внешних звонков с критериями: оценка по скрипту продаж.
 */
class InternalCallEvaluator(
    private val config: PipelineConfig,
    private val promptTemplateService: PromptTemplateService? = null,
) {
    private val log = LoggerFactory.getLogger(InternalCallEvaluator::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val llmBaseUrl = System.getenv("LLM_API_BASE_URL") ?: "https://openrouter.ai/api/v1"
    private val llmApiKey = System.getenv("LLM_API_KEY") ?: ""
    private val llmModel = System.getenv("LLM_MODEL") ?: "openai/gpt-4.1"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 300_000
            socketTimeoutMillis = 300_000
        }
        expectSuccess = false
    }

    fun evaluate(schema: String, callId: UUID, transcription: String) {
        val qualityJson = callLlmForInternalEvaluation(schema, transcription)
        saveInternalQuality(schema, callId, qualityJson)
    }

    fun evaluateWithCriteria(
        schema: String,
        transcription: String,
        criteria: List<PipelineCriterionInput>,
        scriptName: String,
    ): String {
        return callLlmForExternalEvaluation(schema, transcription, criteria, scriptName)
    }

    private fun callLlmForInternalEvaluation(schema: String, transcription: String): String {
        val template = getTemplate(schema, "internal_eval", PromptTemplateService.DEFAULT_INTERNAL_EVAL)
        val prompt = template.replace("{transcription}", transcription)
        return callLlm(schema, prompt)
    }

    private fun callLlmForExternalEvaluation(
        schema: String,
        transcription: String,
        criteria: List<PipelineCriterionInput>,
        scriptName: String,
    ): String {
        val template = getTemplate(schema, "external_eval", PromptTemplateService.DEFAULT_EXTERNAL_EVAL)
        val criteriaList = criteria.joinToString("\n") { "  ${it.id}. ${it.name}: ${it.description}" }
        val prompt = template
            .replace("{transcription}", transcription)
            .replace("{criteria}", criteriaList)
            .replace("{scriptName}", scriptName)
        return callLlm(schema, prompt)
    }

    private fun getTemplate(schema: String, id: String, fallback: String): String =
        try {
            promptTemplateService?.getContent(schema, id)?.takeIf { it.isNotBlank() } ?: fallback
        } catch (e: Exception) {
            log.warn("Failed to load template '{}' from DB for schema '{}', using default", id, schema, e)
            fallback
        }

    private fun callLlm(schema: String, prompt: String): String {
        val systemPrompt = getTemplate(schema, "system", PromptTemplateService.DEFAULT_SYSTEM)
        return kotlinx.coroutines.runBlocking {
            val response = client.post("$llmBaseUrl/chat/completions") {
                header("Authorization", "Bearer $llmApiKey")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(LlmRequest(
                    model = llmModel,
                    messages = listOf(
                        LlmMessage("system", systemPrompt),
                        LlmMessage("user", prompt),
                    ),
                    responseFormat = LlmResponseFormat("json_object"),
                    temperature = 0.1,
                    maxTokens = 4096,
                )))
            }

            if (!response.status.isSuccess()) {
                val err = response.body<String>()
                throw RuntimeException("LLM API error [${response.status}]: ${err.take(500)}")
            }

            val result = response.body<LlmResponse>()
            result.choices.firstOrNull()?.message?.content
                ?: throw RuntimeException("Empty LLM response")
        }
    }

    private fun saveInternalQuality(schema: String, callId: UUID, qualityJson: String) =
        transaction {
            val qs = TQualityScores(schema)
            val parsed = try {
                json.parseToJsonElement(qualityJson) as? kotlinx.serialization.json.JsonObject
            } catch (_: Exception) { null }

            val overallScore = parsed?.get("overall_score")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }

            val summary = parsed?.get("summary")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

            qs.insert {
                it[qs.callId] = callId
                it[qs.overallScore] = overallScore
                it[qs.criteria] = qualityJson
                it[qs.strengths] = extractJsonArray(qualityJson, "strengths")
                it[qs.weaknesses] = extractJsonArray(qualityJson, "weaknesses")
                it[qs.recommendations] = extractJsonArray(qualityJson, "recommendations")
                it[qs.summary] = summary
                it[qs.processedAt] = System.currentTimeMillis()
            }
        }

    private fun extractJsonArray(jsonStr: String, key: String): String? =
        try {
            val obj = json.parseToJsonElement(jsonStr) as kotlinx.serialization.json.JsonObject
            obj[key]?.let { json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it) }
        } catch (_: Exception) { null }

    fun shutdown() {
        client.close()
    }

    @Serializable
    private data class LlmRequest(
        val model: String,
        val messages: List<LlmMessage>,
        @kotlinx.serialization.SerialName("response_format")
        val responseFormat: LlmResponseFormat? = null,
        val temperature: Double = 0.1,
        @kotlinx.serialization.SerialName("max_tokens")
        val maxTokens: Int = 4096,
    )

    @Serializable
    private data class LlmMessage(val role: String, val content: String)

    @Serializable
    private data class LlmResponseFormat(val type: String)

    @Serializable
    private data class LlmResponse(val choices: List<LlmChoice> = emptyList())

    @Serializable
    private data class LlmChoice(val message: LlmMessage? = null)
}
