package com.malikov.config

import com.malikov.auth.AuthService
import com.malikov.auth.JwtService
import com.malikov.db.*
import com.malikov.pipeline.PipelineClient
import com.malikov.pipeline.PipelineResultWriter
import com.malikov.pipeline.PipelineService
import com.malikov.service.*
import com.malikov.telegram.BatchNotificationService
import com.malikov.telegram.ReportScheduler
import com.malikov.telegram.TelegramBotService
import com.malikov.telegram.TelegramLinkService
import com.malikov.telegram.TelegramReportService

/**
 * Простой DI — создаём все сервисы один раз и передаём через routing.
 * Без Koin/Kodein — явные зависимости легче читать и тестировать.
 */
class ServiceRegistry(config: AppConfig) {
    // Repositories
    val userRepository          = UserRepository()
    val managerRepository       = ManagerRepository()
    val scriptRepository        = ScriptRepository()
    val callRepository          = CallRepository()
    val batchRepository         = BatchRepository()
    val tenantAdminRepository   = TenantAdminRepository()
    val departmentLeadRepository = DepartmentLeadRepository()
    val reportRepository        = ReportRepository()

    // Redis
    val redisService = RedisService(config.redis)

    // Auth
    val jwtService  = JwtService(config.jwt)
    val authService = AuthService(userRepository, jwtService)

    // Pipeline (AI module integration)
    val pipelineClient       = PipelineClient(config.pipeline, config.pipeline.apiKey)
    val pipelineResultWriter = PipelineResultWriter()

    // LLM evaluation services
    val internalCallEvaluator = InternalCallEvaluator(config.pipeline)

    val pipelineService      = PipelineService(pipelineClient, pipelineResultWriter, internalCallEvaluator)
    val batchSummaryService   = BatchSummaryService(batchRepository)
    val batchExportService    = BatchExportService(batchRepository, callRepository, managerRepository)

    // Telegram
    val telegramLinkService = TelegramLinkService(redisService, config.telegram)
    val telegramBotService  = TelegramBotService(config.telegram, telegramLinkService)
    val batchNotificationService = BatchNotificationService(telegramBotService, batchRepository)

    // Batch processing orchestrator
    val batchProcessingService = BatchProcessingService(
        pipelineClient, pipelineResultWriter, batchRepository,
        callRepository, scriptRepository, internalCallEvaluator, batchSummaryService,
        batchNotificationService,
    )

    // Audio storage
    val audioStorageService  = AudioStorageService(config.audio.storagePath)
    val audioCleanupScheduler = AudioCleanupScheduler(
        config.audio.retentionDays, audioStorageService, callRepository,
    )

    // Business services
    val managerService     = ManagerService(managerRepository)
    val scriptService      = ScriptService(scriptRepository)
    val callService        = CallService(
        callRepository, managerRepository, scriptRepository,
        pipelineService, batchRepository, batchProcessingService,
        audioStorageService,
    )
    val tenantAdminService = TenantAdminService(tenantAdminRepository)

    val telegramReportService = TelegramReportService(
        reportRepository, departmentLeadRepository, managerRepository, telegramBotService,
    )
    val reportScheduler = ReportScheduler(config.telegram, telegramReportService)

    fun startBackgroundServices() {
        telegramBotService.reportService = telegramReportService
        telegramBotService.reportRepo = reportRepository
        telegramBotService.start()
        reportScheduler.start()
        audioCleanupScheduler.start()
    }

    fun shutdown() {
        pipelineService.shutdown()
        batchProcessingService.shutdown()
        internalCallEvaluator.shutdown()
        batchSummaryService.shutdown()
        audioCleanupScheduler.shutdown()
        telegramBotService.shutdown()
        reportScheduler.shutdown()
        redisService.shutdown()
    }
}
