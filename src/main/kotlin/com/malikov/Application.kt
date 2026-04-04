package com.malikov

import com.malikov.config.AppConfig
import com.malikov.config.ServiceRegistry
import com.malikov.config.configureDatabase
import com.malikov.config.configureMetrics
import com.malikov.config.configurePlugins
import com.malikov.routing.configureRouting
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    logger.info { "Starting Malikov Backend on port $port" }

    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val config   = AppConfig.load()
    val services = ServiceRegistry(config)

    configureDatabase(config)
    configurePlugins(config)
    configureMetrics()
    configureRouting(config, services)

    logger.info { "Malikov Backend started [env=${config.environment}]" }
}
