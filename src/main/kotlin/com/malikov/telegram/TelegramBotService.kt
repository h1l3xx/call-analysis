package com.malikov.telegram

import com.malikov.config.TelegramConfig
import com.malikov.db.ReportRepository
import com.malikov.db.TenantUserInfo
import com.malikov.db.Users
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

private val log = KotlinLogging.logger {}

@Serializable
data class TgResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
)

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
)

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val chat: TgChat,
    val text: String? = null,
    val from: TgUser? = null,
)

@Serializable
data class TgChat(
    val id: Long,
    val type: String,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
)

@Serializable
data class TgUser(
    val id: Long,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
)

@Serializable
data class TgSendMessage(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    @SerialName("parse_mode") val parseMode: String = "HTML",
)

class TelegramBotService(
    private val config: TelegramConfig,
    private val linkService: TelegramLinkService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val apiBase = "https://api.telegram.org/bot${config.botToken}"

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private var offset: Long = 0

    var reportService: TelegramReportService? = null
    var reportRepo: ReportRepository? = null

    fun start() {
        if (!config.enabled || config.botToken.isBlank()) {
            log.info { "Telegram bot disabled or token not set, skipping" }
            return
        }
        log.info { "Starting Telegram bot polling..." }
        scope.launch { pollLoop() }
    }

    fun shutdown() {
        scope.cancel()
        httpClient.close()
    }

    suspend fun sendMessage(chatId: Long, text: String) {
        try {
            httpClient.post("$apiBase/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(TgSendMessage(chatId = chatId, text = text))
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to send Telegram message to chatId=$chatId" }
        }
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            try {
                val updates = getUpdates()
                for (update in updates) {
                    offset = update.updateId + 1
                    handleUpdate(update)
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                log.error(e) { "Telegram polling error, retrying in 5s" }
                delay(5_000)
            }
        }
    }

    private suspend fun getUpdates(): List<TgUpdate> {
        val response = httpClient.get("$apiBase/getUpdates") {
            parameter("offset", offset)
            parameter("timeout", 30)
            parameter("allowed_updates", "[\"message\"]")
        }
        val body = response.body<TgResponse<List<TgUpdate>>>()
        return if (body.ok) body.result ?: emptyList() else emptyList()
    }

    private suspend fun handleUpdate(update: TgUpdate) {
        val msg = update.message ?: return
        val text = msg.text?.trim() ?: return
        val chatId = msg.chat.id

        when {
            text == "/start" -> handleStart(chatId, msg.from)
            text.startsWith("/link") -> handleLink(chatId, text)
            text == "/unlink" -> handleUnlink(chatId)
            text == "/status" -> handleStatus(chatId)
            text == "/report" -> handleReport(chatId)
            text == "/week" -> handleWeek(chatId)
            text == "/department" -> handleDepartment(chatId)
            text == "/help" -> handleHelp(chatId)
            text.matches(Regex("^\\d{6}$")) -> handleLink(chatId, "/link $text")
            else -> handleHelp(chatId)
        }
    }

    private suspend fun handleStart(chatId: Long, from: TgUser?) {
        val name = from?.firstName ?: "пользователь"
        sendMessage(chatId,
            "Привет, $name! Я бот <b>Malikov</b>.\n\n" +
            "Чтобы привязать аккаунт и получать отчёты:\n" +
            "1. Откройте настройки профиля на сайте\n" +
            "2. Нажмите «Привязать Telegram» и скопируйте код\n" +
            "3. Отправьте мне: <code>/link ВАШ_КОД</code>\n\n" +
            "Или просто отправьте 6-значный код.\n\n" +
            "Команды: /help"
        )
    }

    private suspend fun handleLink(chatId: Long, text: String) {
        val code = text.removePrefix("/link").trim()
        if (code.isEmpty() || !code.matches(Regex("^\\d{6}$"))) {
            sendMessage(chatId, "Укажите 6-значный код: <code>/link 123456</code>")
            return
        }

        when (linkService.verifyAndLink(code, chatId)) {
            is TelegramLinkService.LinkResult.Success ->
                sendMessage(chatId, "Аккаунт успешно привязан! Теперь вы будете получать отчёты в этот чат.")
            is TelegramLinkService.LinkResult.InvalidCode ->
                sendMessage(chatId, "Код недействителен или истёк. Получите новый код в настройках профиля.")
            is TelegramLinkService.LinkResult.UserNotFound ->
                sendMessage(chatId, "Пользователь не найден. Проверьте код и попробуйте снова.")
        }
    }

    private suspend fun handleUnlink(chatId: Long) {
        val userId = findUserByChatId(chatId)
        if (userId == null) {
            sendMessage(chatId, "Ваш аккаунт не привязан.")
            return
        }
        linkService.unlink(userId)
        sendMessage(chatId, "Аккаунт отвязан. Вы больше не будете получать отчёты.")
    }

    private suspend fun handleStatus(chatId: Long) {
        val userId = findUserByChatId(chatId)
        if (userId == null) {
            sendMessage(chatId, "Ваш Telegram не привязан к аккаунту Malikov.")
        } else {
            sendMessage(chatId, "Ваш Telegram привязан к аккаунту Malikov. Отчёты включены.")
        }
    }

    private suspend fun handleReport(chatId: Long) {
        val user = resolveUser(chatId) ?: return
        val sinceMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val text = reportService?.buildReportForUser(user, sinceMs, "за последние 24 часа", "Личный")
        sendMessage(chatId, text ?: "Нет данных за последние 24 часа.")
    }

    private suspend fun handleWeek(chatId: Long) {
        val user = resolveUser(chatId) ?: return
        val sinceMs = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val text = reportService?.buildReportForUser(user, sinceMs, "за последние 7 дней", "Недельный")
        sendMessage(chatId, text ?: "Нет данных за последнюю неделю.")
    }

    private suspend fun handleDepartment(chatId: Long) {
        val user = resolveUser(chatId) ?: return
        if (user.role != "TEAM_LEAD" && user.role != "CLIENT_ADMIN") {
            sendMessage(chatId, "Эта команда доступна только руководителям и администраторам.")
            return
        }
        val sinceMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val text = reportService?.buildReportForUser(user, sinceMs, "за последние 24 часа", "Отдел")
        sendMessage(chatId, text ?: "Нет данных по отделу.")
    }

    private suspend fun handleHelp(chatId: Long) {
        sendMessage(chatId,
            "\uD83D\uDCCB <b>Доступные команды:</b>\n\n" +
            "/report — личный отчёт за 24 часа\n" +
            "/week — личный отчёт за неделю\n" +
            "/department — статистика отдела (руководители)\n" +
            "/status — статус привязки аккаунта\n" +
            "/link <code>КОД</code> — привязать аккаунт\n" +
            "/unlink — отвязать аккаунт\n" +
            "/help — эта справка"
        )
    }

    private suspend fun resolveUser(chatId: Long): TenantUserInfo? {
        val user = reportRepo?.findUserByTelegramChat(chatId)
        if (user == null) {
            sendMessage(chatId, "Ваш аккаунт не привязан. Используйте /link <код> для привязки.")
        }
        return user
    }

    private fun findUserByChatId(chatId: Long): java.util.UUID? = transaction {
        Users.selectAll()
            .where { Users.telegramChatId eq chatId }
            .singleOrNull()
            ?.get(Users.id)
    }
}
