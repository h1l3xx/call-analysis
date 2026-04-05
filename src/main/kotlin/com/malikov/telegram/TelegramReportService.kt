package com.malikov.telegram

import com.malikov.db.*
import mu.KotlinLogging
import java.util.UUID

private val log = KotlinLogging.logger {}

class TelegramReportService(
    private val reportRepo: ReportRepository,
    private val deptLeadRepo: DepartmentLeadRepository,
    private val managerRepo: ManagerRepository,
    private val botService: TelegramBotService,
) {

    suspend fun sendDailyReports(
        sinceMs: Long = System.currentTimeMillis() - 24 * 60 * 60 * 1000L,
        periodLabel: String = "за последние 24 часа",
    ) {
        sendReports(sinceMs, periodLabel, "Ежедневный")
    }

    suspend fun sendWeeklyReports() {
        val sinceMs = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        sendReports(sinceMs, "за последние 7 дней", "Еженедельный")
    }

    private suspend fun sendReports(sinceMs: Long, periodLabel: String, reportType: String) {
        val users = reportRepo.allTenantsWithLinkedUsers()
        if (users.isEmpty()) {
            log.debug { "No users with linked Telegram, skipping $reportType report" }
            return
        }

        log.info { "$reportType отчёт: ${users.size} получателей" }

        for (user in users) {
            try {
                val text = buildReportForUser(user, sinceMs, periodLabel, reportType)
                if (text != null) {
                    botService.sendMessage(user.chatId, text)
                }
            } catch (e: Exception) {
                log.error(e) { "Failed to send $reportType report to ${user.fullName} (${user.role})" }
            }
        }
    }

    fun buildReportForUser(
        user: TenantUserInfo,
        sinceMs: Long,
        periodLabel: String,
        reportType: String,
    ): String? = when (user.role) {
        "MANAGER" -> buildManagerReport(user, sinceMs, periodLabel, reportType)
        "TEAM_LEAD" -> buildTeamLeadReport(user, sinceMs, periodLabel, reportType)
        "CLIENT_ADMIN" -> buildAdminReport(user, sinceMs, periodLabel, reportType)
        else -> null
    }

    private fun buildManagerReport(
        user: TenantUserInfo,
        sinceMs: Long,
        periodLabel: String,
        reportType: String,
    ): String? {
        val manager = managerRepo.findByUserId(user.schema, user.userId) ?: return null
        val stats = reportRepo.managerStats(user.schema, manager.id, sinceMs) ?: return null

        if (stats.totalCalls == 0L) {
            return "$REPORT <b>$reportType отчёт</b>\n\n" +
                "Привет, ${user.fullName}!\n" +
                "За период $periodLabel звонков не зафиксировано."
        }

        return buildString {
            appendLine("$REPORT <b>$reportType отчёт</b>")
            appendLine()
            appendLine("Привет, <b>${user.fullName}</b>!")
            appendLine("Статистика $periodLabel:")
            appendLine()

            appendLine("$PHONE Звонков: <b>${stats.totalCalls}</b>")
            if (stats.internalCalls > 0 || stats.externalCalls > 0) {
                appendLine("   внутренних: ${stats.internalCalls} | внешних: ${stats.externalCalls}")
            }
            appendLine("$OK Обработано: <b>${stats.doneCalls}</b>")
            if (stats.failedCalls > 0) appendLine("$FAIL Ошибки: <b>${stats.failedCalls}</b>")
            appendLine()

            if (stats.avgScore != null) {
                append("$STAR Средний балл: <b>${fmt(stats.avgScore)}</b>")
                appendTrend(this, stats.avgScore, stats.prevAvgScore)
                appendLine()
                appendLine("   мин: ${fmt(stats.minScore)} | макс: ${fmt(stats.maxScore)} | оценено: ${stats.scoredCalls}/${stats.totalCalls}")
            } else {
                appendLine("$STAR Оценки пока нет")
            }

            if (stats.topWeaknesses.isNotEmpty()) {
                appendLine()
                appendLine("$WARN <b>Основные замечания:</b>")
                stats.topWeaknesses.forEach { appendLine("  • $it") }
            }
        }
    }

    private fun buildTeamLeadReport(
        user: TenantUserInfo,
        sinceMs: Long,
        periodLabel: String,
        reportType: String,
    ): String? {
        val departments = deptLeadRepo.listByUser(user.schema, user.userId)
        if (departments.isEmpty()) return null

        return buildString {
            appendLine("$REPORT <b>$reportType отчёт — Руководитель</b>")
            appendLine()
            appendLine("Привет, <b>${user.fullName}</b>!")
            appendLine("Статистика $periodLabel:")

            for (dept in departments) {
                val stats = reportRepo.departmentStats(user.schema, dept.departmentId, sinceMs)
                    ?: continue

                appendLine()
                appendLine("$DEPT <b>${stats.departmentName}</b>")
                append("   Звонков: <b>${stats.totalCalls}</b>")
                if (stats.internalCalls > 0 || stats.externalCalls > 0) {
                    append(" (вн: ${stats.internalCalls}, вш: ${stats.externalCalls})")
                }
                appendLine()
                appendLine("   Обработано: <b>${stats.doneCalls}</b> | Менеджеров: ${stats.managerCount}")

                if (stats.avgScore != null) {
                    append("   Средний балл: <b>${fmt(stats.avgScore)}</b>")
                    appendTrend(this, stats.avgScore, stats.prevAvgScore)
                    appendLine()
                }

                if (stats.lowScoreCalls > 0) {
                    appendLine("   $WARN Проблемных звонков (< 50): <b>${stats.lowScoreCalls}</b>")
                }

                if (stats.managers.isNotEmpty()) {
                    appendLine()
                    appendLine("   <b>Рейтинг:</b>")
                    for ((i, mgr) in stats.managers.withIndex()) {
                        val medal = when (i) { 0 -> GOLD; 1 -> SILVER; 2 -> BRONZE; else -> "   " }
                        val score = mgr.avgScore?.let { fmt(it) } ?: "—"
                        val trend = trendArrow(mgr.avgScore, mgr.prevAvgScore)
                        appendLine("   $medal ${mgr.managerName}: $score$trend (${mgr.doneCalls}/${mgr.totalCalls})")
                    }
                }
            }
        }
    }

    private fun buildAdminReport(
        user: TenantUserInfo,
        sinceMs: Long,
        periodLabel: String,
        reportType: String,
    ): String? {
        val stats = reportRepo.tenantStats(user.schema, sinceMs)

        return buildString {
            appendLine("$REPORT <b>$reportType отчёт — Администратор</b>")
            appendLine()
            appendLine("Привет, <b>${user.fullName}</b>!")
            appendLine("Сводка по компании $periodLabel:")
            appendLine()

            appendLine("$PHONE Звонков: <b>${stats.totalCalls}</b>")
            if (stats.internalCalls > 0 || stats.externalCalls > 0) {
                appendLine("   внутренних: ${stats.internalCalls} | внешних: ${stats.externalCalls}")
            }
            appendLine("$OK Обработано: <b>${stats.doneCalls}</b>")
            if (stats.failedCalls > 0) appendLine("$FAIL Ошибки: <b>${stats.failedCalls}</b>")

            if (stats.avgScore != null) {
                append("$STAR Средний балл: <b>${fmt(stats.avgScore)}</b>")
                appendTrend(this, stats.avgScore, stats.prevAvgScore)
                appendLine()
            }

            appendLine()
            appendLine("<b>По отделам:</b>")

            for (dept in stats.departments) {
                val score = dept.avgScore?.let { fmt(it) } ?: "—"
                val trend = trendArrow(dept.avgScore, dept.prevAvgScore)

                appendLine()
                appendLine("$DEPT <b>${dept.departmentName}</b>: $score$trend")
                appendLine("   Звонков: ${dept.totalCalls} | Менеджеров: ${dept.managerCount}")
                if (dept.lowScoreCalls > 0) {
                    appendLine("   $WARN Проблемных: ${dept.lowScoreCalls}")
                }

                val top = dept.managers.take(3)
                if (top.isNotEmpty()) {
                    for ((i, mgr) in top.withIndex()) {
                        val medal = when (i) { 0 -> GOLD; 1 -> SILVER; 2 -> BRONZE; else -> "" }
                        val s = mgr.avgScore?.let { fmt(it) } ?: "—"
                        appendLine("   $medal ${mgr.managerName}: $s")
                    }
                }
            }
        }
    }

    private fun fmt(score: Double?): String =
        if (score == null) "—" else "%.1f".format(score)

    private fun appendTrend(sb: StringBuilder, current: Double?, prev: Double?) {
        val arrow = trendArrow(current, prev)
        if (arrow.isNotEmpty()) sb.append(" $arrow")
    }

    private fun trendArrow(current: Double?, prev: Double?): String {
        if (current == null || prev == null) return ""
        val diff = current - prev
        return when {
            diff > 2.0 -> UP
            diff < -2.0 -> DOWN
            else -> SAME
        }
    }

    companion object {
        private const val REPORT = "\uD83D\uDCCA"
        private const val PHONE = "\uD83D\uDCDE"
        private const val OK = "\u2705"
        private const val FAIL = "\u274C"
        private const val STAR = "\u2B50"
        private const val DEPT = "\uD83C\uDFE2"
        private const val WARN = "\u26A0\uFE0F"
        private const val GOLD = "\uD83E\uDD47"
        private const val SILVER = "\uD83E\uDD48"
        private const val BRONZE = "\uD83E\uDD49"
        private const val UP = "\u2197\uFE0F"
        private const val DOWN = "\u2198\uFE0F"
        private const val SAME = "\u2796"
    }
}
