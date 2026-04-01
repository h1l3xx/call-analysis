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

    /**
     * Оценка внутреннего звонка: бизнес-процессы + коммуникация.
     */
    fun evaluate(schema: String, callId: UUID, transcription: String) {
        val qualityJson = callLlmForInternalEvaluation(transcription)
        saveInternalQuality(schema, callId, qualityJson)
    }

    /**
     * Оценка внешнего звонка по критериям скрипта.
     */
    fun evaluateWithCriteria(
        transcription: String,
        criteria: List<PipelineCriterionInput>,
        scriptName: String,
    ): String {
        return callLlmForExternalEvaluation(transcription, criteria, scriptName)
    }

    private fun callLlmForInternalEvaluation(transcription: String): String {
        val prompt = buildInternalPrompt(transcription)
        return callLlm(prompt)
    }

    private fun callLlmForExternalEvaluation(
        transcription: String,
        criteria: List<PipelineCriterionInput>,
        scriptName: String,
    ): String {
        val prompt = buildExternalPrompt(transcription, criteria, scriptName)
        return callLlm(prompt)
    }

    private fun callLlm(prompt: String): String {
        return kotlinx.coroutines.runBlocking {
            val response = client.post("$llmBaseUrl/chat/completions") {
                header("Authorization", "Bearer $llmApiKey")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(LlmRequest(
                    model = llmModel,
                    messages = listOf(
                        LlmMessage("system", "Ты — эксперт по оценке качества телефонных разговоров. Отвечай ТОЛЬКО в формате JSON."),
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

    private fun buildInternalPrompt(transcription: String): String = """
Проанализируй следующий ВНУТРЕННИЙ телефонный разговор между сотрудниками компании.

Транскрипция:
---
$transcription
---

Оцени по следующим аспектам:
1. **Бизнес-процессы**: выяви узкие места, неэффективности, задержки, нарушения процедур.
2. **Качество коммуникации**: ясность изложения, конструктивность, наличие action items, профессионализм.

Ответ СТРОГО в JSON формате:
{
  "overall_score": <число от 0 до 100>,
  "business_processes": {
    "score": <число от 0 to 100>,
    "bottlenecks": ["описание узкого места 1", ...],
    "inefficiencies": ["описание неэффективности 1", ...],
    "procedure_violations": ["описание нарушения 1", ...]
  },
  "communication_quality": {
    "score": <число от 0 до 100>,
    "clarity": <число от 0 до 100>,
    "constructiveness": <число от 0 до 100>,
    "action_items_present": <true/false>,
    "issues": ["проблема коммуникации 1", ...]
  },
  "strengths": ["сильная сторона 1", ...],
  "weaknesses": ["слабая сторона 1", ...],
  "recommendations": ["рекомендация 1", ...]
}
""".trimIndent()

    private fun buildExternalPrompt(
        transcription: String,
        criteria: List<PipelineCriterionInput>,
        scriptName: String,
    ): String {
        val criteriaList = criteria.joinToString("\n") { "  ${it.id}. ${it.name}: ${it.description}" }
        return """
Проанализируй следующий разговор менеджера с клиентом по скрипту "$scriptName".

Транскрипция:
---
$transcription
---

Критерии оценки:
$criteriaList

Оцени каждый критерий. Ответ СТРОГО в JSON формате:
{
  "overall_score": <число от 0 до 100>,
  "criteria_evaluations": [
    {
      "id": <номер критерия>,
      "name": "<название>",
      "score": <0.0, 0.5 или 1.0>,
      "comment": "<комментарий>",
      "relevant": <true/false>
    }
  ],
  "strengths": ["сильная сторона 1", ...],
  "weaknesses": ["слабая сторона 1", ...],
  "recommendations": ["рекомендация 1", ...]
}
""".trimIndent()
    }

    private fun saveInternalQuality(schema: String, callId: UUID, qualityJson: String) =
        transaction {
            val qs = TQualityScores(schema)
            val parsed = try { json.parseToJsonElement(qualityJson) } catch (_: Exception) { null }

            val overallScore = parsed?.let {
                try {
                    val obj = it as kotlinx.serialization.json.JsonObject
                    (obj["overall_score"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                } catch (_: Exception) { null }
            }

            qs.insert {
                it[qs.callId] = callId
                it[qs.overallScore] = overallScore
                it[qs.criteria] = qualityJson
                it[qs.strengths] = extractJsonArray(qualityJson, "strengths")
                it[qs.weaknesses] = extractJsonArray(qualityJson, "weaknesses")
                it[qs.recommendations] = extractJsonArray(qualityJson, "recommendations")
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
