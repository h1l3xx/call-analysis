package com.malikov.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import mu.KotlinLogging
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

private val logger = KotlinLogging.logger {}

fun Application.configureDatabase(config: AppConfig) {
    val dataSource = buildDataSource(config.db)

    runMigrations(dataSource, config)

    Database.connect(dataSource)
    logger.info { "Database connected: ${config.db.url}" }
}

private fun buildDataSource(config: DatabaseConfig): HikariDataSource {
    val hikari = HikariConfig().apply {
        jdbcUrl         = config.url
        username        = config.user
        password        = config.password
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = config.poolSize
        minimumIdle     = 2
        idleTimeout     = 600_000
        connectionTimeout = 30_000
        validationTimeout = 5_000
        connectionTestQuery = "SELECT 1"
        poolName        = "MalikovPool"

        // Производительность PostgreSQL
        addDataSourceProperty("reWriteBatchedInserts", "true")
        addDataSourceProperty("cachePrepStmts", "true")
        addDataSourceProperty("prepStmtCacheSize", "250")
    }
    return HikariDataSource(hikari)
}

private fun runMigrations(dataSource: HikariDataSource, config: AppConfig) {
    logger.info { "Running Flyway migrations..." }

    val flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .validateOnMigrate(true)
        .outOfOrder(false)
        .load()

    val result = flyway.migrate()
    logger.info {
        "Migrations complete: ${result.migrationsExecuted} executed, " +
        "success=${result.success}"
    }
}
