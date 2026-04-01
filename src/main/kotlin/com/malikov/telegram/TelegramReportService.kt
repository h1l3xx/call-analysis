package com.malikov.telegram

import com.malikov.db.*
import mu.KotlinLogging
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val log = KotlinLogging.logger {}

class TelegramReportService(
    private val reportRepo: ReportRepository,
    private val deptLeadRepo: DepartmentLeadRepository,
    private val managerRepo: ManagerRepository,
    private val botService: TelegramBotService,
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        .withZone(ZoneId.systemDefault())

    suspend fun sendDailyReports() {
        val sinceMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val periodLabel = "за последние 24 часа"
        sendReports(sinceMs, periodLabel, "Ежедневный")
    }

    suspend fun sendWeeklyReports() {
        val sinceMs = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val periodLabel = "за последние 7 дней"
        sendReports(sinceMs, periodLabel, "Еженедельный")
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
                val text = when (user.role) {
                    "MANAGER" -> buildManagerReport(user, sinceMs, periodLabel, reportType)
                    "TEAM_LEAD" -> buildTeamLeadReport(user, sinceMs, periodLabel, reportType)
                    "CLIENT_ADMIN" -> buildAdminReport(user, sinceMs, periodLabel, reportType)
                    else -> null
                }
                if (text != null) {
                    botService.sendMessage(user.chatId, text)
                }
            } catch (e: Exception) {
                log.error(e) { "Failed to send $reportType report to ${user.fullName} (${user.role})" }
            }
        }
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
            return "$REPORT_ICON <b>$reportType отчёт</b>\n\n" +
                "Привет, ${user.fullName}!\n" +
                "За период $periodLabel звонков не зафиксировано."
        }

        return buildString {
            appendLine("$REPORT_ICON <b>$reportType отчёт</b>")
            appendLine()
            appendLine("Привет, <b>${user.fullName}</b>!")
            appendLine("Статистика $periodLabel:")
            appendLine()
            appendLine("$CALLS_ICON Всего звонков: <b>${stats.totalCalls}</b>")
            appendLine("$DONE_ICON Обработано: <b>${stats.doneCalls}</b>")
            if (stats.failedCalls > 0) appendLine("$FAIL_ICON Ошибки: <b>${stats.failedCalls}</b>")
            appendLine()
            if (stats.avgScore != null) {
                appendLine("$SCORE_ICON Средний балл: <b>${formatScore(stats.avgScore)}</b>")
                appendLine("   Мин: ${formatScore(stats.minScore)} | Макс: ${formatScore(stats.maxScore)}")
            } else {
                appendLine("$SCORE_ICON Оценки пока нет")
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
            appendLine("$REPORT_ICON <b>$reportType отчёт — Руководитель</b>")
            appendLine()
            appendLine("Привет, <b>${user.fullName}</b>!")
            appendLine("Статистика $periodLabel:")

            for (dept in departments) {
                val stats = reportRepo.departmentStats(user.schema, dept.departmentId, sinceMs)
                    ?: continue

                appendLine()
                appendLine("$DEPT_ICON <b>${stats.departmentName}</b>")
                appendLine("   Звонков: <b>${stats.totalCalls}</b> | Обработано: <b>${stats.doneCalls}</b>")
                if (stats.avgScore != null) {
                    appendLine("   Средний балл: <b>${formatScore(stats.avgScore)}</b>")
                }
                appendLine("   Менеджеров: ${stats.managerCount}")

                if (stats.managers.isNotEmpty()) {
                    appendLine()
                    appendLine("   <b>Рейтинг менеджеров:</b>")
                    for ((i, mgr) in stats.managers.withIndex()) {
                        val medal = when (i) { 0 -> GOLD; 1 -> SILVER; 2 -> BRONZE; else -> "   " }
                        val score = mgr.avgScore?.let { formatScore(it) } ?: "—"
                        appendLine("   $medal ${mgr.managerName}: $score (${mgr.doneCalls}/${mgr.totalCalls})")
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
            appendLine("$REPORT_ICON <b>$reportType отчёт — Администратор</b>")
            appendLine()
            appendLine("Привет, <b>${user.fullName}</b>!")
            appendLine("Сводка по компании $periodLabel:")
            appendLine()
            appendLine("$CALLS_ICON Всего звонков: <b>${stats.totalCalls}</b>")
            appendLine("$DONE_ICON Обработано: <b>${stats.doneCalls}</b>")
            if (stats.failedCalls > 0) appendLine("$FAIL_ICON Ошибки: <b>${stats.failedCalls}</b>")
            if (stats.avgScore != null) {
                appendLine("$SCORE_ICON Средний балл: <b>${formatScore(stats.avgScore)}</b>")
            }
            appendLine()
            appendLine("<b>По отделам:</b>")

            for (dept in stats.departments) {
                val score = dept.avgScore?.let { formatScore(it) } ?: "—"
                appendLine()
                appendLine("$DEPT_ICON <b>${dept.departmentName}</b>: $score")
                appendLine("   Звонков: ${dept.totalCalls} | Менеджеров: ${dept.managerCount}")

                val top = dept.managers.take(3)
                if (top.isNotEmpty()) {
                    for ((i, mgr) in top.withIndex()) {
                        val medal = when (i) { 0 -> GOLD; 1 -> SILVER; 2 -> BRONZE; else -> "" }
                        val s = mgr.avgScore?.let { formatScore(it) } ?: "—"
                        appendLine("   $medal ${mgr.managerName}: $s")
                    }
                }
            }
        }
    }

    private fun formatScore(score: Double?): String {
        if (score == null) return "—"
        return "%.1f".format(score)
    }

    companion object {
        private const val REPORT_ICON = "\uD83D\uDCCA" // chart
        private const val CALLS_ICON = "\uD83D\uDCDE" // telephone
        private const val DONE_ICON = "\u2705" // checkmark
        private const val FAIL_ICON = "\u274C" // cross
        private const val SCORE_ICON = "\u2B50" // star
        private const val DEPT_ICON = "\uD83C\uDFE2" // building
        private const val GOLD = "\uD83E\uDD47"
        private const val SILVER = "\uD83E\uDD48"
        private const val BRONZE = "\uD83E\uDD49"
    }
}
