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

class InternalCallEvaluator(
    private val config: PipelineConfig,
    private val promptTemplateService: PromptTemplateService? = null,
) {
    private val log = LoggerFactory.getLogger(InternalCallEvaluator::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val llmBaseUrl = System.getenv("LLM_API_BASE_URL") ?: "https://openrouter.ai/api/v1"
    private val llmApiKey = System.getenv("LLM_API_KEY") ?: ""
    private val llmModel = System.getenv("LLM_MODEL") ?: "openai/gpt-4.1"

    companion object {
        const val SYSTEM_PROMPT = "Ты — эксперт по оценке качества телефонных разговоров. Отвечай ТОЛЬКО в формате JSON."

        private val INTERNAL_JSON_FORMAT = """
Ответ СТРОГО в JSON формате:
{
  "summary": "<краткое описание: кто звонил, тема, итог — 2-3 предложения>",
  "overall_score": <число от 0 до 100>,
  "criteria_scores": {
    "clarity": {"score": <0–100>, "comment": "<пояснение>"},
    "effectiveness": {"score": <0–100>, "comment": "<пояснение>"},
    "professionalism": {"score": <0–100>, "comment": "<пояснение>"},
    "time_efficiency": {"score": <0–100>, "comment": "<пояснение>"},
    "procedures": {"score": <0–100>, "comment": "<пояснение>"}
  },
  "action_items": ["конкретный action item 1", ...],
  "strengths": ["сильная сторона 1", ...],
  "weaknesses": ["слабая сторона 1", ...],
  "recommendations": ["рекомендация 1", ...]
}""".trimIndent()

        private val EXTERNAL_JSON_FORMAT = """
Ответ СТРОГО в JSON формате:
{
  "summary": "<краткое описание: кто обратился, цель, итог — 2-3 предложения>",
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
}""".trimIndent()
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 300_000
            socketTimeoutMillis = 300_000
        }
        expectSuccess = false
    }

    fun evaluate(schema: String, callId: UUID, transcription: String, templateId: String = "internal_eval") {
        val qualityJson = callLlmForInternalEvaluation(schema, transcription, templateId)
        saveInternalQuality(schema, callId, qualityJson)
    }

    fun evaluateWithCriteria(
        schema: String,
        transcription: String,
        criteria: List<PipelineCriterionInput>,
        scriptName: String,
        templateId: String = "external_eval",
    ): String {
        return callLlmForExternalEvaluation(schema, transcription, criteria, scriptName, templateId)
    }

    fun generateSuggestions(templateId: String, description: String): List<String> {
        val typeLabel = when (templateId) {
            "internal_eval" -> "внутренних звонков (между сотрудниками)"
            "external_eval" -> "внешних звонков (менеджер — клиент)"
            else -> "звонков"
        }

        val metaPrompt = """
Ты помогаешь настроить систему оценки качества телефонных звонков.
Пользователь хочет настроить инструкции для оценки $typeLabel.

Описание от пользователя: "$description"

Сгенерируй 3 варианта инструкций для оценки. Каждый вариант должен содержать:
- Чёткие критерии оценки с описаниями
- Указания на что обратить внимание
- Просьбу написать краткое описание звонка

НЕ включай в варианты: JSON-формат ответа, технические плейсхолдеры, текст транскрипции, слова "транскрипция" или "Проанализируй".
Пиши только содержательную часть — что именно оценивать и как.

Ответ строго в JSON:
{"suggestions": ["вариант 1", "вариант 2", "вариант 3"]}
""".trimIndent()

        val responseJson = callLlmRaw(metaPrompt, temperature = 0.7)

        return try {
            val parsed = json.parseToJsonElement(responseJson) as kotlinx.serialization.json.JsonObject
            val arr = parsed["suggestions"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        } catch (e: Exception) {
            log.error("Failed to parse suggestion response: {}", responseJson.take(500), e)
            emptyList()
        }
    }

    private fun callLlmForInternalEvaluation(schema: String, transcription: String, templateId: String): String {
        val userInstructions = getUserInstructions(schema, templateId, PromptTemplateService.DEFAULT_INTERNAL_INSTRUCTIONS)
        val prompt = """
Проанализируй следующий ВНУТРЕННИЙ телефонный разговор между сотрудниками компании.

Транскрипция:
---
$transcription
---

$userInstructions

$INTERNAL_JSON_FORMAT
""".trimIndent()
        return callLlm(prompt)
    }

    private fun callLlmForExternalEvaluation(
        schema: String,
        transcription: String,
        criteria: List<PipelineCriterionInput>,
        scriptName: String,
        templateId: String,
    ): String {
        val userInstructions = getUserInstructions(schema, templateId, PromptTemplateService.DEFAULT_EXTERNAL_INSTRUCTIONS)
        val criteriaList = criteria.joinToString("\n") { "  ${it.id}. ${it.name}: ${it.description}" }
        val prompt = """
Проанализируй следующий разговор менеджера с клиентом по скрипту "$scriptName".

Транскрипция:
---
$transcription
---

Критерии оценки:
$criteriaList

$userInstructions

$EXTERNAL_JSON_FORMAT
""".trimIndent()
        return callLlm(prompt)
    }

    private fun getUserInstructions(schema: String, id: String, fallback: String): String =
        try {
            promptTemplateService?.getContent(schema, id)?.takeIf { it.isNotBlank() } ?: fallback
        } catch (e: Exception) {
            log.warn("Failed to load instructions '{}' for schema '{}', using default", id, schema, e)
            fallback
        }

    private fun callLlm(prompt: String): String = callLlmRaw(prompt, temperature = 0.1)

    private fun callLlmRaw(prompt: String, temperature: Double = 0.1): String {
        return kotlinx.coroutines.runBlocking {
            val response = client.post("$llmBaseUrl/chat/completions") {
                header("Authorization", "Bearer $llmApiKey")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(LlmRequest(
                    model = llmModel,
                    messages = listOf(
                        LlmMessage("system", SYSTEM_PROMPT),
                        LlmMessage("user", prompt),
                    ),
                    responseFormat = LlmResponseFormat("json_object"),
                    temperature = temperature,
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
