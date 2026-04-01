package com.malikov.config

import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import mu.KotlinLogging
import java.time.Duration

private val log = KotlinLogging.logger {}

class RedisService(config: RedisConfig) {
    private val client: RedisClient = RedisClient.create(config.url)
    private val connection = client.connect()
    private val commands: RedisCommands<String, String> = connection.sync()

    fun setWithTtl(key: String, value: String, ttl: Duration) {
        commands.set(key, value, SetArgs().ex(ttl.seconds))
    }

    fun get(key: String): String? = commands.get(key)

    fun del(key: String) {
        commands.del(key)
    }

    fun shutdown() {
        runCatching {
            connection.close()
            client.shutdown()
        }.onFailure { log.warn(it) { "Redis shutdown error" } }
    }
}
