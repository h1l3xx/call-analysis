package com.malikov.telegram

import com.malikov.config.TelegramConfig
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val log = KotlinLogging.logger {}

class ReportScheduler(
    private val config: TelegramConfig,
    private val reportService: TelegramReportService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val zone: ZoneId = try {
        ZoneId.of(config.timezone)
    } catch (_: Exception) {
        log.warn { "Invalid timezone '${config.timezone}', falling back to Europe/Moscow" }
        ZoneId.of("Europe/Moscow")
    }

    fun start() {
        if (!config.enabled || config.botToken.isBlank()) {
            log.info { "Telegram reports disabled, skipping scheduler" }
            return
        }

        if (config.dailyEnabled) {
            scope.launch { dailyLoop() }
            log.info { "Daily report scheduler started (time=${config.dailyTime}, tz=$zone)" }
        }

        if (config.weeklyEnabled) {
            scope.launch { weeklyLoop() }
            log.info { "Weekly report scheduler started (day=${config.weeklyDay}, time=${config.weeklyTime}, tz=$zone)" }
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    private suspend fun dailyLoop() {
        while (scope.isActive) {
            val delayMs = msUntilNextDaily()
            log.debug { "Next daily report in ${delayMs / 1000}s" }
            delay(delayMs)

            try {
                reportService.sendDailyReports()
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                log.error(e) { "Error sending daily reports" }
            }

            delay(60_000)
        }
    }

    private suspend fun weeklyLoop() {
        while (scope.isActive) {
            val delayMs = msUntilNextWeekly()
            log.debug { "Next weekly report in ${delayMs / 1000}s" }
            delay(delayMs)

            try {
                reportService.sendWeeklyReports()
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                log.error(e) { "Error sending weekly reports" }
            }

            delay(60_000)
        }
    }

    private fun msUntilNextDaily(): Long {
        val now = ZonedDateTime.now(zone)
        val targetTime = LocalTime.parse(config.dailyTime, timeFormatter)
        var next = now.toLocalDate().atTime(targetTime).atZone(zone)
        if (now >= next) next = next.plusDays(1)
        return Duration.between(now, next).toMillis().coerceAtLeast(1000)
    }

    private fun msUntilNextWeekly(): Long {
        val now = ZonedDateTime.now(zone)
        val targetDay = parseDayOfWeek(config.weeklyDay)
        val targetTime = LocalTime.parse(config.weeklyTime, timeFormatter)
        var next = now.toLocalDate()
            .with(TemporalAdjusters.nextOrSame(targetDay))
            .atTime(targetTime)
            .atZone(zone)
        if (now >= next) next = next.with(TemporalAdjusters.next(targetDay))
        return Duration.between(now, next).toMillis().coerceAtLeast(1000)
    }

    private fun parseDayOfWeek(day: String): DayOfWeek = when (day.lowercase()) {
        "monday", "mon" -> DayOfWeek.MONDAY
        "tuesday", "tue" -> DayOfWeek.TUESDAY
        "wednesday", "wed" -> DayOfWeek.WEDNESDAY
        "thursday", "thu" -> DayOfWeek.THURSDAY
        "friday", "fri" -> DayOfWeek.FRIDAY
        "saturday", "sat" -> DayOfWeek.SATURDAY
        "sunday", "sun" -> DayOfWeek.SUNDAY
        else -> DayOfWeek.MONDAY
    }
}
