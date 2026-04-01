package com.malikov.service

import com.malikov.db.CallRepository
import com.malikov.db.Tenants
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class AudioCleanupScheduler(
    private val retentionDays: Int,
    private val audioStorage: AudioStorageService,
    private val callRepo: CallRepository,
) {
    private val log = LoggerFactory.getLogger(AudioCleanupScheduler::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (retentionDays <= 0) {
            log.info("Audio cleanup disabled (AUDIO_RETENTION_DAYS={})", retentionDays)
            return
        }
        log.info("Audio cleanup scheduler started: retention={} days, runs daily at 03:00", retentionDays)
        scope.launch { loop() }
    }

    private suspend fun loop() {
        while (true) {
            val now = LocalDateTime.now()
            val nextRun = LocalDateTime.of(
                if (now.hour >= 3) LocalDate.now().plusDays(1) else LocalDate.now(),
                LocalTime.of(3, 0),
            )
            val delayMs = ChronoUnit.MILLIS.between(now, nextRun)
            log.debug("Next audio cleanup in {} ms (at {})", delayMs, nextRun)
            delay(delayMs)

            try {
                runCleanup()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Audio cleanup failed", e)
            }
        }
    }

    private fun runCleanup() {
        val cutoffMs = System.currentTimeMillis() - retentionDays.toLong() * 24 * 60 * 60 * 1000
        val schemas = transaction {
            Tenants.selectAll().map { it[Tenants.dbSchema] }
        }

        var totalDeleted = 0
        for (schema in schemas) {
            try {
                val expired = callRepo.findExpiredAudio(schema, cutoffMs)
                for ((callId, audioKey) in expired) {
                    audioStorage.delete(audioKey)
                    callRepo.clearAudioKey(schema, callId)
                    totalDeleted++
                }
            } catch (e: Exception) {
                log.warn("Cleanup error in schema {}: {}", schema, e.message)
            }
        }

        if (totalDeleted > 0) {
            log.info("Audio cleanup complete: deleted {} files (retention={} days)", totalDeleted, retentionDays)
        }
    }

    fun shutdown() {
        scope.cancel("Application shutdown")
    }
}
