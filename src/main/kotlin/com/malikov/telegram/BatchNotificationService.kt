package com.malikov.telegram

import com.malikov.db.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

private val log = KotlinLogging.logger {}

class BatchNotificationService(
    private val botService: TelegramBotService,
    private val batchRepo: BatchRepository,
) {

    suspend fun notifyBatchCompleted(schema: String, batchId: UUID) {
        try {
            val batch = batchRepo.findById(schema, batchId) ?: return
            val recipients = findRecipients(schema)
            if (recipients.isEmpty()) return

            val summaries = batchRepo.listSummaries(schema, batchId)
            val avgScore = computeAvgScore(schema, batchId)
            val lowScoreCount = countLowScore(schema, batchId)

            val text = buildNotification(batch, avgScore, lowScoreCount, summaries)

            for ((chatId, name) in recipients) {
                try {
                    botService.sendMessage(chatId, text)
                } catch (e: Exception) {
                    log.error(e) { "Failed to send batch notification to $name" }
                }
            }

            log.info { "Batch $batchId completion notification sent to ${recipients.size} recipients" }
        } catch (e: Exception) {
            log.error(e) { "Failed to send batch completion notifications for $batchId" }
        }
    }

    private fun findRecipients(schema: String): List<Pair<Long, String>> = transaction {
        Users.join(Tenants, JoinType.INNER, Users.tenantId, Tenants.id)
            .selectAll()
            .where {
                (Tenants.dbSchema eq schema) and
                Users.telegramChatId.isNotNull() and
                Users.isActive.eq(true) and
                (Users.role inList listOf("CLIENT_ADMIN", "TEAM_LEAD"))
            }
            .map { row -> row[Users.telegramChatId]!! to row[Users.fullName] }
    }

    private fun computeAvgScore(schema: String, batchId: UUID): Double? = transaction {
        val cl = TCalls(schema)
        val qs = TQualityScores(schema)
        val scores = cl.join(qs, JoinType.INNER, cl.id, qs.callId)
            .selectAll()
            .where { cl.batchId eq batchId }
            .mapNotNull { it[qs.overallScore] }
        scores.takeIf { it.isNotEmpty() }?.average()
    }

    private fun countLowScore(schema: String, batchId: UUID): Long = transaction {
        val cl = TCalls(schema)
        val qs = TQualityScores(schema)
        cl.join(qs, JoinType.INNER, cl.id, qs.callId)
            .selectAll()
            .where {
                (cl.batchId eq batchId) and
                (qs.overallScore less 50.0)
            }
            .count()
    }

    private fun buildNotification(
        batch: BatchRow,
        avgScore: Double?,
        lowScoreCount: Long,
        summaries: List<BatchSummaryRow>,
    ): String = buildString {
        appendLine("$BELL <b>Батч обработан</b>")
        appendLine()
        appendLine("$PHONE Звонков: <b>${batch.totalCalls}</b>")
        appendLine("$OK Обработано: <b>${batch.processedCalls}</b>")

        if (avgScore != null) {
            appendLine("$STAR Средний балл: <b>${"%.1f".format(avgScore)}</b>")
        }
        if (lowScoreCount > 0) {
            appendLine("$WARN Проблемных (< 50): <b>$lowScoreCount</b>")
        }

        val mainSummary = summaries.firstOrNull { it.scope == "batch" }
        if (mainSummary?.content != null) {
            val short = mainSummary.content
                .take(500)
                .let { if (mainSummary.content.length > 500) "$it..." else it }
            appendLine()
            appendLine("$DOC <b>Резюме:</b>")
            appendLine(short)
        }
    }

    companion object {
        private const val BELL = "\uD83D\uDD14"
        private const val PHONE = "\uD83D\uDCDE"
        private const val OK = "\u2705"
        private const val STAR = "\u2B50"
        private const val WARN = "\u26A0\uFE0F"
        private const val DOC = "\uD83D\uDCDD"
    }
}
