package com.malikov.routing

import com.malikov.pipeline.PipelineException
import com.malikov.pipeline.PipelineService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Прокси-маршруты к Python AI pipeline.
 *
 * GET  /pipeline/health            — health-check pipeline
 * GET  /pipeline/analyses          — список обработанных анализов (на стороне pipeline)
 * GET  /pipeline/analyses/{id}     — детали конкретного анализа
 */
fun Route.pipelineRoutes(service: PipelineService) {
    route("/pipeline") {

        get("/health") {
            try {
                val health = service.checkHealth()
                call.respond(health)
            } catch (e: PipelineException) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to "Pipeline unavailable", "detail" to e.detail)
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to "Pipeline unreachable", "detail" to (e.message ?: "unknown"))
                )
            }
        }

        get("/analyses") {
            val p = requireTenantRole(
                com.malikov.auth.Role.TEAM_LEAD,
                com.malikov.auth.Role.CLIENT_ADMIN,
            )
            val limit  = call.parameters["limit"]?.toIntOrNull() ?: 20
            val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
            val query  = call.parameters["query"]
            val hasQuality = call.parameters["hasQuality"]?.toBooleanStrictOrNull()

            try {
                call.respond(service.listAnalyses(limit, offset, query, hasQuality))
            } catch (e: PipelineException) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to "Pipeline unavailable", "detail" to e.detail)
                )
            }
        }

        get("/analyses/{resultId}") {
            val p = requireTenantRole(
                com.malikov.auth.Role.TEAM_LEAD,
                com.malikov.auth.Role.CLIENT_ADMIN,
            )
            val resultId = call.parameters["resultId"]
                ?: throw IllegalArgumentException("Missing resultId")

            try {
                call.respond(service.getAnalysis(resultId))
            } catch (e: PipelineException) {
                if (e.statusCode == 404) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Analysis not found"))
                } else {
                    call.respond(
                        HttpStatusCode.BadGateway,
                        mapOf("error" to "Pipeline error", "detail" to e.detail)
                    )
                }
            }
        }
    }
}
