package com.malikov.service

import com.malikov.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Генерация суммаризирующих LLM-отчётов по батчу.
 *
 * Создаёт 2 отчёта:
 *   1. По внутренним звонкам: узкие места бизнес-процессов, коммуникация
 *   2. По внешним звонкам: работа менеджеров, тренды
 */
class BatchSummaryService(
    private val batchRepo: BatchRepository,
) {
    private val log = LoggerFactory.getLogger(BatchSummaryService::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val llmBaseUrl = System.getenv("LLM_API_BASE_URL") ?: "https://openrouter.ai/api/v1"
    private val llmApiKey = System.getenv("LLM_API_KEY") ?: ""
    private val llmModel = System.getenv("LLM_MODEL") ?: "openai/gpt-4.1"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 600_000
            socketTimeoutMillis = 600_000
        }
        expectSuccess = false
    }

    fun generateBatchSummary(schema: String, batchId: UUID) {
        log.info("Generating summary for batch {} in schema {}", batchId, schema)

        val callData = collectBatchData(schema, batchId)
        if (callData.isEmpty()) {
            log.warn("No evaluated calls in batch {}", batchId)
            return
        }

        batchRepo.deleteSummaries(schema, batchId)

        val internalCalls = callData.filter { it.callType == "internal" }
        val externalCalls = callData.filter { it.callType == "external" || it.callType == "unknown" }

        if (internalCalls.isNotEmpty()) {
            val summary = generateInternalSummary(internalCalls)
            batchRepo.saveSummary(schema, batchId, "internal", "batch", summary)
            log.info("Internal summary saved for batch {}", batchId)
        }

        if (externalCalls.isNotEmpty()) {
            val summary = generateExternalSummary(externalCalls)
            batchRepo.saveSummary(schema, batchId, "external", "batch", summary)
            log.info("External summary saved for batch {}", batchId)
        }
    }

    /**
     * Генерация отчёта за произвольный период / отдел.
     * @return UUID созданного batch_summary (сохраняется с scope=all, period_type=custom)
     */
    fun generatePeriodSummary(
        schema: String,
        sinceMs: Long,
        untilMs: Long,
        departmentId: UUID? = null,
    ): UUID {
        val callData = collectPeriodData(schema, sinceMs, untilMs, departmentId)
        if (callData.isEmpty()) {
            throw IllegalStateException("Нет обработанных звонков за указанный период")
        }

        val prompt = buildPeriodSummaryPrompt(callData, sinceMs, untilMs)
        val summary = callLlm(prompt)

        val dummyBatchId = UUID.randomUUID()
        return batchRepo.saveSummary(schema, dummyBatchId, "all", "custom", summary)
    }

    private fun collectBatchData(schema: String, batchId: UUID): List<CallEvalData> = transaction {
        val cl = TCalls(schema)
        val t = TTranscriptions(schema)
        val qs = TQualityScores(schema)
        val m = TManagers(schema)

        cl.join(t, org.jetbrains.exposed.sql.JoinType.LEFT, cl.id, t.callId)
            .join(qs, org.jetbrains.exposed.sql.JoinType.LEFT, cl.id, qs.callId)
            .join(m, org.jetbrains.exposed.sql.JoinType.LEFT, cl.managerId, m.id)
            .join(Users, org.jetbrains.exposed.sql.JoinType.LEFT, m.userId, Users.id)
            .selectAll()
            .where { (cl.batchId eq batchId) and (cl.status eq "done") }
            .map { row ->
                CallEvalData(
                    callId = row[cl.id].toString(),
                    callType = row[cl.callType] ?: "unknown",
                    managerName = row.getOrNull(Users.fullName),
                    filename = row[cl.audioFilename],
                    transcription = row.getOrNull(t.cleanedText) ?: row.getOrNull(t.rawText),
                    qualityCriteria = row.getOrNull(qs.criteria),
                    overallScore = row.getOrNull(qs.overallScore),
                    strengths = row.getOrNull(qs.strengths),
                    weaknesses = row.getOrNull(qs.weaknesses),
                    summary = row.getOrNull(qs.summary),
                )
            }
    }

    private fun collectPeriodData(
        schema: String,
        sinceMs: Long,
        untilMs: Long,
        departmentId: UUID?,
    ): List<CallEvalData> = transaction {
        val cl = TCalls(schema)
        val t = TTranscriptions(schema)
        val qs = TQualityScores(schema)
        val m = TManagers(schema)

        var base = cl.join(t, org.jetbrains.exposed.sql.JoinType.LEFT, cl.id, t.callId)
            .join(qs, org.jetbrains.exposed.sql.JoinType.LEFT, cl.id, qs.callId)
            .join(m, org.jetbrains.exposed.sql.JoinType.LEFT, cl.managerId, m.id)
            .join(Users, org.jetbrains.exposed.sql.JoinType.LEFT, m.userId, Users.id)

        val conditions = mutableListOf(
            Op.build { cl.status eq "done" },
            Op.build { cl.createdAt greaterEq sinceMs },
            Op.build { cl.createdAt lessEq untilMs },
        )
        if (departmentId != null) {
            conditions.add(Op.build { m.departmentId eq departmentId })
        }

        base.selectAll()
            .where { conditions.reduce { acc, op -> acc and op } }
            .map { row ->
                CallEvalData(
                    callId = row[cl.id].toString(),
                    callType = row[cl.callType] ?: "unknown",
                    managerName = row.getOrNull(Users.fullName),
                    filename = row[cl.audioFilename],
                    transcription = row.getOrNull(t.cleanedText) ?: row.getOrNull(t.rawText),
                    qualityCriteria = row.getOrNull(qs.criteria),
                    overallScore = row.getOrNull(qs.overallScore),
                    strengths = row.getOrNull(qs.strengths),
                    weaknesses = row.getOrNull(qs.weaknesses),
                    summary = row.getOrNull(qs.summary),
                )
            }
    }

    private fun generateInternalSummary(calls: List<CallEvalData>): String {
        val prompt = buildInternalSummaryPrompt(calls)
        return callLlm(prompt)
    }

    private fun generateExternalSummary(calls: List<CallEvalData>): String {
        val prompt = buildExternalSummaryPrompt(calls)
        return callLlm(prompt)
    }

    private fun buildInternalSummaryPrompt(calls: List<CallEvalData>): String {
        val callSummaries = calls.take(50).joinToString("\n\n") { call ->
            """
Звонок: ${call.filename ?: call.callId}
Менеджер: ${call.managerName ?: "Неизвестен"}
Описание: ${call.summary ?: "N/A"}
Оценка: ${call.overallScore ?: "N/A"}
Сильные стороны: ${call.strengths ?: "N/A"}
Слабые стороны: ${call.weaknesses ?: "N/A"}
""".trimIndent()
        }

        return """
Проанализируй результаты оценки ${calls.size} ВНУТРЕННИХ звонков компании.

Данные по каждому звонку:
$callSummaries

Сформируй АГРЕГИРОВАННЫЙ отчёт. Ответ СТРОГО в JSON формате:
{
  "total_calls": ${calls.size},
  "avg_score": <средний балл>,
  "business_process_issues": [
    {"issue": "<описание узкого места>", "frequency": <кол-во звонков>, "severity": "high/medium/low"}
  ],
  "communication_issues": [
    {"issue": "<описание проблемы>", "frequency": <кол-во звонков>, "severity": "high/medium/low"}
  ],
  "recurring_patterns": ["повторяющийся паттерн 1", ...],
  "top_recommendations": ["рекомендация 1", ...],
  "summary_text": "<текстовое описание общей картины, 3-5 предложений>"
}
""".trimIndent()
    }

    private fun buildExternalSummaryPrompt(calls: List<CallEvalData>): String {
        val callSummaries = calls.take(50).joinToString("\n\n") { call ->
            """
Звонок: ${call.filename ?: call.callId}
Менеджер: ${call.managerName ?: "Неизвестен"}
Описание: ${call.summary ?: "N/A"}
Оценка: ${call.overallScore ?: "N/A"}
Сильные стороны: ${call.strengths ?: "N/A"}
Слабые стороны: ${call.weaknesses ?: "N/A"}
""".trimIndent()
        }

        return """
Проанализируй результаты оценки ${calls.size} ВНЕШНИХ звонков менеджеров с клиентами.

Данные по каждому звонку:
$callSummaries

Сформируй АГРЕГИРОВАННЫЙ отчёт. Ответ СТРОГО в JSON формате:
{
  "total_calls": ${calls.size},
  "avg_score": <средний балл>,
  "manager_performance": [
    {"manager": "<имя>", "calls_count": <кол-во>, "avg_score": <средний балл>, "key_issues": ["проблема"]}
  ],
  "common_client_complaints": ["жалоба 1", ...],
  "script_adherence": {
    "avg_adherence_percent": <процент следования скрипту>,
    "most_skipped_criteria": ["критерий 1", ...]
  },
  "top_recommendations": ["рекомендация 1", ...],
  "summary_text": "<текстовое описание общей картины, 3-5 предложений>"
}
""".trimIndent()
    }

    private fun buildPeriodSummaryPrompt(calls: List<CallEvalData>, sinceMs: Long, untilMs: Long): String {
        val internalCount = calls.count { it.callType == "internal" }
        val externalCount = calls.size - internalCount

        val callSummaries = calls.take(50).joinToString("\n") { call ->
            val desc = call.summary?.let { " — $it" } ?: ""
            "${call.callType}: ${call.managerName ?: "?"} — score ${call.overallScore ?: "N/A"}$desc"
        }

        return """
Суммаризируй ${calls.size} звонков за период.
Внутренних: $internalCount, Внешних: $externalCount.

$callSummaries

Ответ СТРОГО в JSON формате:
{
  "period_total": ${calls.size},
  "internal_count": $internalCount,
  "external_count": $externalCount,
  "avg_score": <средний балл>,
  "key_findings": ["находка 1", ...],
  "recommendations": ["рекомендация 1", ...],
  "summary_text": "<общая картина, 3-5 предложений>"
}
""".trimIndent()
    }

    private fun callLlm(prompt: String): String {
        return kotlinx.coroutines.runBlocking {
            val response = client.post("$llmBaseUrl/chat/completions") {
                header("Authorization", "Bearer $llmApiKey")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(LlmRequest(
                    model = llmModel,
                    messages = listOf(
                        LlmMessage("system", "Ты — аналитик качества звонков. Отвечай ТОЛЬКО в формате JSON."),
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
                ?: throw RuntimeException("Empty LLM summary response")
        }
    }

    fun shutdown() { client.close() }

    private data class CallEvalData(
        val callId: String,
        val callType: String,
        val managerName: String?,
        val filename: String?,
        val transcription: String?,
        val qualityCriteria: String?,
        val overallScore: Double?,
        val strengths: String?,
        val weaknesses: String?,
        val summary: String?,
    )

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
