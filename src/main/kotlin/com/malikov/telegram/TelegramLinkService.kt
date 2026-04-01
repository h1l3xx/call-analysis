package com.malikov.telegram

import com.malikov.config.RedisService
import com.malikov.config.TelegramConfig
import com.malikov.db.Users
import mu.KotlinLogging
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

private val log = KotlinLogging.logger {}

class TelegramLinkService(
    private val redis: RedisService,
    private val config: TelegramConfig,
) {
    companion object {
        private const val CODE_PREFIX = "tg:link:"
        private const val REVERSE_PREFIX = "tg:user:"
    }

    fun generateCode(userId: UUID): String {
        redis.del("$REVERSE_PREFIX$userId")

        val code = (100_000 + Random.nextInt(900_000)).toString()
        val ttl = Duration.ofMinutes(config.linkCodeTtlMin)

        redis.setWithTtl("$CODE_PREFIX$code", userId.toString(), ttl)
        redis.setWithTtl("$REVERSE_PREFIX$userId", code, ttl)

        log.info { "Generated Telegram link code for user=$userId" }
        return code
    }

    fun verifyAndLink(code: String, chatId: Long): LinkResult {
        val userIdStr = redis.get("$CODE_PREFIX$code")
            ?: return LinkResult.InvalidCode

        val userId = UUID.fromString(userIdStr)

        val updated = transaction {
            Users.update({ Users.id eq userId }) {
                it[telegramChatId] = chatId
            }
        }

        if (updated == 0) return LinkResult.UserNotFound

        redis.del("$CODE_PREFIX$code")
        redis.del("$REVERSE_PREFIX$userId")

        log.info { "Linked Telegram chatId=$chatId to user=$userId" }
        return LinkResult.Success(userId)
    }

    fun unlink(userId: UUID): Boolean {
        val updated = transaction {
            Users.update({ Users.id eq userId }) {
                it[telegramChatId] = null
            }
        }
        if (updated > 0) log.info { "Unlinked Telegram for user=$userId" }
        return updated > 0
    }

    fun isLinked(userId: UUID): Boolean = transaction {
        Users.selectAll()
            .where { Users.id eq userId }
            .singleOrNull()
            ?.get(Users.telegramChatId) != null
    }

    fun getChatId(userId: UUID): Long? = transaction {
        Users.selectAll()
            .where { Users.id eq userId }
            .singleOrNull()
            ?.get(Users.telegramChatId)
    }

    fun getPendingCode(userId: UUID): String? =
        redis.get("$REVERSE_PREFIX$userId")

    sealed interface LinkResult {
        data class Success(val userId: UUID) : LinkResult
        data object InvalidCode : LinkResult
        data object UserNotFound : LinkResult
    }
}
