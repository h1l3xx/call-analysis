package com.malikov.service

import com.malikov.db.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID

@Serializable
data class ManagerEvaluationResponse(
    val id: String,
    val managerId: String,
    val periodFrom: Long?,
    val periodTo: Long?,
    val callCount: Int,
    val avgScore: Double?,
    val assessment: String?,   // raw JSON from LLM
    val createdAt: Long,
)

class ManagerEvaluationService(
    private val callRepo: CallRepository,
    private val managerRepo: ManagerRepository,
    private val llmEvaluator: InternalCallEvaluator,
) {
    private val log = LoggerFactory.getLogger(ManagerEvaluationService::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Generate (and persist) a period-level evaluation for a manager. */
    fun generate(
        schema: String,
        managerId: UUID,
        since: Long? = null,
        until: Long? = null,
    ): ManagerEvaluationResponse {
        val manager = managerRepo.findById(schema, managerId)
            ?: error("Manager $managerId not found in schema $schema")

        // Gather all quality scores for the manager in the period
        val scores = callRepo.findResultsByFilters(
            schema = schema,
            managerIds = listOf(managerId),
            status = "done",
            sinceMs = since,
            untilMs = until,
        ).mapNotNull { row ->
            val qs = row.qualityScore ?: return@mapNotNull null
            CallScoreEntry(
                callId       = row.call.id.toString().take(8),
                score        = qs.overallScore,
                callType     = row.call.callType,
                weaknesses   = parseJsonArray(qs.weaknesses),
                strengths    = parseJsonArray(qs.strengths),
                summary      = qs.summary,
            )
        }

        val callCount = scores.size
        val avgScore = if (scores.isNotEmpty())
            scores.mapNotNull { it.score }.average().takeIf { !it.isNaN() }
        else null

        log.info(
            "Generating period evaluation for manager {} ({}) — {} calls, avg={:.1f}",
            managerId, manager.fullName, callCount, avgScore ?: 0.0,
        )

        val assessmentJson = if (callCount == 0) {
            buildEmptyAssessment()
        } else {
            buildLlmAssessment(manager.fullName, callCount, avgScore, scores)
        }

        val id = saveEvaluation(schema, managerId, since, until, callCount, avgScore, assessmentJson)

        return ManagerEvaluationResponse(
            id         = id.toString(),
            managerId  = managerId.toString(),
            periodFrom = since,
            periodTo   = until,
            callCount  = callCount,
            avgScore   = avgScore,
            assessment = assessmentJson,
            createdAt  = System.currentTimeMillis(),
        )
    }

    /** Fetch past evaluations for a manager (most recent first). */
    fun list(schema: String, managerId: UUID, limit: Int = 5): List<ManagerEvaluationResponse> =
        transaction {
            val t = TManagerEvaluations(schema)
            t.selectAll()
                .where { t.managerId eq managerId }
                .orderBy(t.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    ManagerEvaluationResponse(
                        id         = row[t.id].toString(),
                        managerId  = row[t.managerId].toString(),
                        periodFrom = row[t.periodFrom],
                        periodTo   = row[t.periodTo],
                        callCount  = row[t.callCount],
                        avgScore   = row[t.avgScore],
                        assessment = row[t.assessment],
                        createdAt  = row[t.createdAt],
                    )
                }
        }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun buildLlmAssessment(
        managerName: String,
        callCount: Int,
        avgScore: Double?,
        scores: List<CallScoreEntry>,
    ): String {
        val scoreStr = avgScore?.let { "%.1f".format(it) } ?: "н/д"

        val callLines = scores.joinToString("\n") { e ->
            val type = if (e.callType == "internal") "внутр" else "внеш"
            val ws = e.weaknesses.joinToString("; ").take(120)
            val sc = e.score?.let { "%.0f".format(it) } ?: "?"
            "[${e.callId}] $type score=$sc | ${ws.ifBlank { e.summary?.take(80) ?: "—" }}"
        }

        val prompt = """
Оцени работу сотрудника «$managerName» на основе анализа $callCount звонков.
Средний балл: $scoreStr из 100.

Список звонков (ID, тип, балл, недостатки):
$callLines

Проанализируй паттерны и сильные/слабые стороны сотрудника. Дай конкретные рекомендации по развитию.

Ответ СТРОГО в JSON:
{
  "summary_text": "<общее описание работы сотрудника за период — 3-5 предложений>",
  "strengths": ["сильная сторона 1", "сильная сторона 2"],
  "weaknesses": ["систематическая проблема 1", "систематическая проблема 2"],
  "top_recommendations": ["рекомендация 1", "рекомендация 2", "рекомендация 3"],
  "performance_level": "high/medium/low"
}
""".trimIndent()

        return try {
            llmEvaluator.callLlmForManagerEvaluation(prompt)
        } catch (e: Exception) {
            log.error("LLM failed to generate manager evaluation for {}: {}", managerName, e.message)
            buildFallbackAssessment(avgScore, scores)
        }
    }

    private fun buildEmptyAssessment() = """
        {"summary_text":"Нет оценённых звонков за выбранный период.","strengths":[],"weaknesses":[],"top_recommendations":[],"performance_level":"medium"}
    """.trimIndent()

    private fun buildFallbackAssessment(avgScore: Double?, scores: List<CallScoreEntry>): String {
        val level = when {
            avgScore == null -> "medium"
            avgScore >= 70   -> "high"
            avgScore >= 50   -> "medium"
            else             -> "low"
        }
        val allWeaknesses = scores.flatMap { it.weaknesses }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(3).map { it.key }
        return json.encodeToString(FallbackAssessment(
            summaryText = "Анализ основан на ${scores.size} звонках. Средний балл: ${avgScore?.let { "%.1f".format(it) } ?: "—"}.",
            strengths = emptyList(),
            weaknesses = allWeaknesses,
            topRecommendations = emptyList(),
            performanceLevel = level,
        ))
    }

    private fun saveEvaluation(
        schema: String,
        managerId: UUID,
        since: Long?,
        until: Long?,
        callCount: Int,
        avgScore: Double?,
        assessmentJson: String,
    ): UUID = transaction {
        val t = TManagerEvaluations(schema)
        val newId = UUID.randomUUID()
        t.insert {
            it[t.id]         = newId
            it[t.managerId]  = managerId
            it[t.periodFrom] = since
            it[t.periodTo]   = until
            it[t.callCount]  = callCount
            it[t.avgScore]   = avgScore
            it[t.assessment] = assessmentJson
            it[t.createdAt]  = System.currentTimeMillis()
        }
        newId
    }

    private fun parseJsonArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray
                ?: return emptyList()
            arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        } catch (_: Exception) { emptyList() }
    }

    private data class CallScoreEntry(
        val callId: String,
        val score: Double?,
        val callType: String?,
        val weaknesses: List<String>,
        val strengths: List<String>,
        val summary: String?,
    )

    @Serializable
    private data class FallbackAssessment(
        val summaryText: String,
        val strengths: List<String>,
        val weaknesses: List<String>,
        val topRecommendations: List<String>,
        val performanceLevel: String,
    )
}
