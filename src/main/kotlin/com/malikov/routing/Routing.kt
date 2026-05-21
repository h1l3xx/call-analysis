package com.malikov.routing

import com.malikov.auth.UserPrincipal
import com.malikov.config.AppConfig
import com.malikov.config.AppMetrics
import com.malikov.config.ServiceRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

fun Application.configureRouting(config: AppConfig, services: ServiceRegistry) {

    environment.monitor.subscribe(ApplicationStopped) {
        services.shutdown()
    }

    services.startBackgroundServices()

    routing {

        get("/metrics") {
            call.respond(AppMetrics.registry.scrape())
        }

        get("/health") {
            val pipelineOk = services.pipelineService.isAvailable()
            call.respond(mapOf(
                "status"      to "ok",
                "version"     to "1.0.0",
                "environment" to config.environment,
                "pipeline"    to if (pipelineOk) "available" else "unavailable",
                "telegram"    to if (config.telegram.enabled) "enabled" else "disabled",
                "timestamp"   to Instant.now().toString(),
            ))
        }

        route("/api/v1/auth") {
            authRoutes(services.authService)
        }

        authenticate("jwt") {

            // Обновляем lastActiveAt при каждом аутентифицированном запросе.
            // Запускается асинхронно, не блокирует обработку запроса.
            intercept(ApplicationCallPipeline.Call) {
                val userId = call.principal<UserPrincipal>()?.userId
                if (userId != null) {
                    launch(Dispatchers.IO) {
                        runCatching { services.userRepository.touchLastActive(UUID.fromString(userId)) }
                    }
                }
            }

            route("/api/v1") {
                managerRoutes(services.managerService, services.managerEvaluationService, services.userRepository)
                scriptRoutes(services.scriptService)
                callRoutes(services.callService, services.audioStorageService, services.batchExportService, services.batchProcessingService)
                batchRoutes(services.batchRepository, services.callRepository, services.managerRepository, services.batchSummaryService, services.batchExportService, services.callService)
                pipelineRoutes(services.pipelineService)
                telegramRoutes(services.telegramLinkService, config.telegram.linkCodeTtlMin)
                departmentLeadRoutes(services.departmentLeadRepository)
                departmentCallPolicyRoutes(services.departmentCallPolicyService)
                promptTemplateRoutes(services.promptTemplateService, services.internalCallEvaluator)
            }

            route("/api/v1/admin") {
                intercept(ApplicationCallPipeline.Call) {
                    val principal = call.principal<UserPrincipal>()!!
                    if (!principal.isSuperAdmin) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Superadmin only"))
                        finish()
                    }
                }
                adminRoutes(services.tenantAdminService)
            }
        }
    }
}
