package com.malikov.config

data class AppConfig(
    val environment: String,
    val db: DatabaseConfig,
    val jwt: JwtConfig,
    val redis: RedisConfig,
    val pipeline: PipelineConfig,
    val telegram: TelegramConfig,
    val audio: AudioConfig,
) {
    companion object {
        fun load() = AppConfig(
            environment = env("ENVIRONMENT", "development"),
            db = DatabaseConfig(
                url      = env("DB_URL", "jdbc:postgresql://localhost:5432/malikov"),
                user     = env("DB_USER", "malikov"),
                password = env("DB_PASSWORD", "malikov_dev"),
                poolSize = env("DB_POOL_SIZE", "10").toInt(),
            ),
            jwt = JwtConfig(
                secret         = env("JWT_SECRET", "dev_secret_min_32_chars_change_me!"),
                issuer         = env("JWT_ISSUER", "malikov.ai"),
                accessTtlMin   = env("JWT_ACCESS_TTL_MINUTES", "15").toLong(),
                refreshTtlDays = env("JWT_REFRESH_TTL_DAYS", "30").toLong(),
            ),
            redis = RedisConfig(
                url = env("REDIS_URL", "redis://localhost:6379"),
            ),
            pipeline = PipelineConfig(
                baseUrl = env("PYTHON_PIPELINE_URL", "http://localhost:8001"),
                timeoutSeconds = env("PIPELINE_TIMEOUT_SECONDS", "300").toLong(),
                apiKey = System.getenv("PIPELINE_API_KEY"),
            ),
            audio = AudioConfig(
                storagePath   = env("AUDIO_STORAGE_PATH", "/data/audio"),
                retentionDays = env("AUDIO_RETENTION_DAYS", "30").toInt(),
            ),
            telegram = TelegramConfig(
                botToken     = System.getenv("TELEGRAM_BOT_TOKEN") ?: "",
                enabled      = env("TELEGRAM_BOT_ENABLED", "false").toBooleanStrictOrNull() ?: false,
                dailyEnabled  = env("TELEGRAM_REPORT_DAILY_ENABLED", "true").toBooleanStrictOrNull() ?: true,
                weeklyEnabled = env("TELEGRAM_REPORT_WEEKLY_ENABLED", "true").toBooleanStrictOrNull() ?: true,
                dailyTime     = env("TELEGRAM_DAILY_TIME", "09:00"),
                weeklyDay     = env("TELEGRAM_WEEKLY_DAY", "monday"),
                weeklyTime    = env("TELEGRAM_WEEKLY_TIME", "10:00"),
                linkCodeTtlMin = env("TELEGRAM_LINK_CODE_TTL_MINUTES", "5").toLong(),
                timezone = env("TELEGRAM_TIMEZONE", "Europe/Moscow"),
            ),
        )

        private fun env(key: String, default: String) =
            System.getenv(key) ?: default
    }
}

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val poolSize: Int,
)

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val accessTtlMin: Long,
    val refreshTtlDays: Long,
)

data class RedisConfig(val url: String)

data class PipelineConfig(
    val baseUrl: String,
    val timeoutSeconds: Long,
    val apiKey: String? = null,
)

data class AudioConfig(
    val storagePath: String,
    val retentionDays: Int,
)

data class TelegramConfig(
    val botToken: String,
    val enabled: Boolean,
    val dailyEnabled: Boolean,
    val weeklyEnabled: Boolean,
    val dailyTime: String,
    val weeklyDay: String,
    val weeklyTime: String,
    val linkCodeTtlMin: Long,
    val timezone: String,
)
