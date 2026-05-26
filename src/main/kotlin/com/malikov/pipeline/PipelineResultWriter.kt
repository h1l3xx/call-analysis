package com.malikov.pipeline

import com.malikov.db.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Записывает результат анализа из AI pipeline в tenant-таблицы.
 *
 * Заполняет: transcriptions, speaker_metrics, quality_scores, error_events.
 * Обновляет: calls (status, duration, finished_at).
 * Вызывает: billing (public.bill_call_minutes).
 */
class PipelineResultWriter {

    private val log = LoggerFactory.getLogger(PipelineResultWriter::class.java)
    private val json = Json { encodeDefaults = true }

    /**
     * Сохраняет полный результат анализа в БД и вызывает биллинг.
     */
    fun saveResult(
        schema: String,
        callId: UUID,
        scriptId: UUID,
        response: PipelineAnalyzeResponse,
    ) = transaction {
        val now = System.currentTimeMillis()

        writeTranscription(schema, callId, response, now)
        writeSpeakerMetrics(schema, callId, response, now)
        writeQualityScore(schema, callId, scriptId, response, now)
        writeErrorEvents(schema, callId, response, now)
        updateCallStatus(schema, callId, response, now)

        val durationSec = response.asrMetrics?.audioDuration?.toInt()
        if (durationSec != null && durationSec > 0) {
            billMinutes(schema, callId, durationSec)
        }

        log.info("Pipeline results saved for call {} in schema {}", callId, schema)
    }

    fun markFailed(
        schema: String,
        callId: UUID,
        failedStep: String,
        errorMessage: String,
    ) = transaction {
        val friendly = humanizeError(errorMessage)
        val cl = TCalls(schema)
        cl.update({ cl.id eq callId }) {
            it[cl.status]       = "failed"
            it[cl.failedStep]   = failedStep
            it[cl.errorMessage] = friendly.take(2000)
            it[cl.finishedAt]   = System.currentTimeMillis()
        }
        log.warn("Call {} marked as failed at step '{}': {} (raw: {})", callId, failedStep, friendly, errorMessage)
    }

    companion object {
        fun humanizeError(raw: String?): String {
            if (raw.isNullOrBlank()) return "Неизвестная ошибка"
            return when {
                "NoBackendError" in raw ->
                    "Формат аудиофайла не поддерживается или файл повреждён"
                raw.startsWith("CORRUPTED_AUDIO") || "Битый аудиофайл" in raw ->
                    "Аудиофайл повреждён или имеет неверный формат"
                "NO_SPEECH" in raw ->
                    "В записи не обнаружена речь"
                raw.startsWith("Не удалось обработать аудио") ->
                    "Не удалось обработать аудиофайл — возможно, формат не поддерживается"
                raw.startsWith("Pipeline [") -> {
                    val code = raw.substringAfter("[").substringBefore("]")
                    "Ошибка сервиса обработки (код $code)"
                }
                else -> raw.take(300)
            }
        }
    }

    fun markNoSpeech(schema: String, callId: UUID, reason: String) = transaction {
        val cl = TCalls(schema)
        cl.update({ cl.id eq callId }) {
            it[cl.status] = "no_speech"
            it[cl.errorMessage] = reason.take(2000)
            it[cl.finishedAt] = System.currentTimeMillis()
        }
        log.info("Call {} marked as no_speech: {}", callId, reason)
    }

    fun markProcessing(schema: String, callId: UUID) = transaction {
        val cl = TCalls(schema)
        cl.update({ cl.id eq callId }) {
            it[cl.status] = "processing"
        }
    }

    /**
     * Сохраняет только транскрипцию (Phase A — без quality analysis).
     */
    fun saveTranscriptionOnly(
        schema: String,
        callId: UUID,
        response: PipelineAnalyzeResponse,
    ) = transaction {
        val now = System.currentTimeMillis()
        writeTranscription(schema, callId, response, now)
        writeSpeakerMetrics(schema, callId, response, now)

        val cl = TCalls(schema)
        val durationSec = response.asrMetrics?.audioDuration?.toInt()
        cl.update({ cl.id eq callId }) {
            it[cl.status] = "transcribed_only"
            it[cl.durationSeconds] = durationSec
        }

        if (durationSec != null && durationSec > 0) {
            billMinutes(schema, callId, durationSec)
        }

        log.info("Transcription saved for call {} in schema {} (no quality)", callId, schema)
    }

    /**
     * Сохраняет quality score из JSON-строки (Phase B — LLM evaluation).
     */
    fun saveQualityFromJson(
        schema: String,
        callId: UUID,
        scriptId: UUID?,
        qualityJson: String,
    ) = transaction {
        val qs = TQualityScores(schema)

        // Validate strictly before inserting into jsonb column — PostgreSQL parser is stricter than kotlinx
        val safeJson = try {
            kotlinx.serialization.json.Json.Default.parseToJsonElement(qualityJson)
            qualityJson
        } catch (e: Exception) {
            log.error("LLM quality JSON is invalid for DB (call={}, script={}), using fallback. Error: {}. Snippet: {}…",
                callId, scriptId, e.message, qualityJson.take(200))
            """{"error":"LLM response was not valid JSON","overall_score":0}"""
        }

        val parsed = try {
            json.parseToJsonElement(safeJson) as? kotlinx.serialization.json.JsonObject
        } catch (_: Exception) { null }

        val overallScore = parsed?.get("overall_score")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }

        val summary = parsed?.get("summary")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

        fun extractArray(key: String): String? =
            parsed?.get(key)?.let { json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it) }

        qs.insert {
            it[qs.callId] = callId
            it[qs.scriptId] = scriptId
            it[qs.overallScore] = overallScore
            it[qs.criteria] = safeJson
            it[qs.strengths] = extractArray("strengths")
            it[qs.weaknesses] = extractArray("weaknesses")
            it[qs.recommendations] = extractArray("recommendations")
            it[qs.summary] = summary
            it[qs.processedAt] = System.currentTimeMillis()
        }

        if (scriptId != null) {
            val cl = TCalls(schema)
            cl.update({ cl.id eq callId }) { it[cl.scriptId] = scriptId }
        }

        log.info("Quality from LLM saved for call {} [script={}, score={}]", callId, scriptId ?: "none", overallScore)
    }

    // ── private writers ─────────────────────────────────────────────

    private fun writeTranscription(
        schema: String,
        callId: UUID,
        response: PipelineAnalyzeResponse,
        now: Long,
    ) {
        val t = TTranscriptions(schema)
        t.insert {
            it[t.callId]         = callId
            it[t.rawText]        = response.rawTranscription
            it[t.cleanedText]    = response.cleanedText
            it[t.language]       = response.asrMetrics?.language ?: "ru"
            it[t.languageProb]   = response.asrMetrics?.languageProbability
            it[t.classification] = response.classification?.let { cls ->
                json.encodeToString(cls)
            }
            it[t.speakerTurns]   = response.speakerTurns?.let { turns ->
                json.encodeToString(turns)
            }
            it[t.createdAt]      = now
        }
    }

    private fun writeSpeakerMetrics(
        schema: String,
        callId: UUID,
        response: PipelineAnalyzeResponse,
        now: Long,
    ) {
        val sm = TSpeakerMetrics(schema)
        val pipelineMetrics = response.speakerMetrics

        sm.insert {
            it[sm.callId]              = callId
            it[sm.managerTalkRatio]    = pipelineMetrics?.managerTalkRatio
            it[sm.clientTalkRatio]     = pipelineMetrics?.clientTalkRatio
            it[sm.silenceRatio]        = pipelineMetrics?.silenceRatio
            it[sm.interruptionsCount]  = pipelineMetrics?.interruptionsCount
            it[sm.avgPauseSeconds]     = pipelineMetrics?.avgPauseSeconds
            it[sm.managerWpm]          = pipelineMetrics?.managerWpm
            it[sm.clientWpm]           = pipelineMetrics?.clientWpm
            it[sm.longestMonologueSec] = pipelineMetrics?.longestMonologueSec
            it[sm.createdAt]           = now
        }
    }

    private fun writeQualityScore(
        schema: String,
        callId: UUID,
        scriptId: UUID,
        response: PipelineAnalyzeResponse,
        now: Long,
    ) {
        val quality = response.quality ?: return
        val qs = TQualityScores(schema)

        qs.insert {
            it[qs.callId]          = callId
            it[qs.scriptId]        = scriptId
            it[qs.overallScore]    = quality.overallScore
            it[qs.criteria]        = json.encodeToString(quality.criteriaEvaluations)
            it[qs.strengths]       = json.encodeToString(quality.strengths)
            it[qs.weaknesses]      = json.encodeToString(quality.weaknesses)
            it[qs.recommendations] = json.encodeToString(quality.recommendations)
            it[qs.processedAt]     = now
        }
    }

    private fun writeErrorEvents(
        schema: String,
        callId: UUID,
        response: PipelineAnalyzeResponse,
        now: Long,
    ) {
        val quality = response.quality ?: return
        val ee = TErrorEvents(schema)

        quality.criteriaEvaluations
            .filter { it.relevant && (it.score == null || it.score < 1.0) }
            .forEach { criterion ->
                ee.insert {
                    it[ee.callId]        = callId
                    it[ee.criterionId]   = criterion.id
                    it[ee.criterionName] = criterion.name
                    it[ee.severity]      = when {
                        criterion.score == null -> "medium"
                        criterion.score == 0.0  -> "high"
                        criterion.score == 0.5  -> "medium"
                        else                    -> "low"
                    }
                    it[ee.score]   = criterion.score
                    it[ee.comment] = criterion.comment
                    it[ee.status]  = if (criterion.score == 0.0) "failed" else "partial"
                    it[ee.createdAt] = now
                }
            }
    }

    private fun updateCallStatus(
        schema: String,
        callId: UUID,
        response: PipelineAnalyzeResponse,
        now: Long,
    ) {
        val cl = TCalls(schema)
        val hasQuality = response.quality != null

        cl.update({ cl.id eq callId }) {
            it[cl.status]          = if (hasQuality) "done" else "transcribed_only"
            it[cl.durationSeconds] = response.asrMetrics?.audioDuration?.toInt()
            it[cl.finishedAt]      = now
        }
    }

    /**
     * Вызывает SQL-функцию public.bill_call_minutes для списания минут
     * из лимита подписки тенанта.
     *
     * Должен вызываться внутри [transaction] (вызывается из [saveResult]).
     */
    private fun billMinutes(schema: String, callId: UUID, durationSeconds: Int) {
        try {
            val tenantId = Tenants
                .selectAll()
                .where { Tenants.dbSchema eq schema }
                .singleOrNull()
                ?.get(Tenants.id)

            if (tenantId != null) {
                TransactionManager.current().exec(
                    "SELECT public.bill_call_minutes('$tenantId', '$callId', $durationSeconds)"
                )

                val ul = TUsageLog(schema)
                val minutesBilled = kotlin.math.ceil(durationSeconds.toDouble() / 60.0)
                ul.insert {
                    it[ul.callId]        = callId
                    it[ul.minutesBilled] = minutesBilled
                    it[ul.billedAt]      = System.currentTimeMillis()
                }

                log.info("Billed {} minutes for call {} [tenant={}]", minutesBilled.toInt(), callId, tenantId)
            } else {
                log.warn("Cannot bill: tenant not found for schema {}", schema)
            }
        } catch (e: Exception) {
            log.error("Billing error for call {} in schema {}: {}", callId, schema, e.message)
        }
    }
}
