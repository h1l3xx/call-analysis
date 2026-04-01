package com.malikov.routing

import com.malikov.auth.Role
import com.malikov.config.NotFoundException
import com.malikov.db.BatchRepository
import com.malikov.dto.*
import com.malikov.service.BatchSummaryService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.util.UUID

fun Route.batchRoutes(
    batchRepo: BatchRepository,
    batchSummaryService: BatchSummaryService,
) {
    val json = Json { ignoreUnknownKeys = true }

    route("/batches") {

        get {
            val p = requireTenantRole(Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val params = paginationParams()
            val (items, total) = batchRepo.list(p.schema!!, params.offset, params.pageSize)
            val response = items.map { it.toBatchResponse() }
            call.respond(paginated(response, total, params))
        }

        get("/{id}") {
            val p = requireTenantRole(Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val batchId = pathUuid("id")
            val batch = batchRepo.findById(p.schema!!, batchId)
                ?: throw NotFoundException("Batch not found")
            val summaries = batchRepo.listSummaries(p.schema!!, batchId)
            call.respond(BatchDetailResponse(
                batch = batch.toBatchResponse(),
                summaries = summaries.map {
                    BatchSummaryResponse(
                        id = it.id.toString(),
                        batchId = it.batchId.toString(),
                        scope = it.scope,
                        periodType = it.periodType,
                        content = it.content,
                        createdAt = it.createdAt,
                    )
                },
            ))
        }

        get("/{id}/calls") {
            val p = requireTenantRole(Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val batchId = pathUuid("id")
            val params = paginationParams()
            val callType = call.parameters["callType"]
            val (items, total) = batchRepo.listCallsByBatch(
                p.schema!!, batchId, callType, params.offset, params.pageSize
            )
            val response = items.map {
                CallResponse(
                    id = it.id.toString(),
                    managerId = it.managerId?.toString(),
                    managerName = it.managerName,
                    scriptId = it.scriptId?.toString(),
                    scriptName = it.scriptName,
                    status = it.status,
                    source = it.source,
                    callType = it.callType,
                    batchId = it.batchId?.toString(),
                    durationSeconds = it.durationSeconds,
                    createdAt = it.createdAt,
                    finishedAt = it.finishedAt,
                )
            }
            call.respond(paginated(response, total, params))
        }

        get("/{id}/summary") {
            val p = requireTenantRole(Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val batchId = pathUuid("id")
            val summaries = batchRepo.listSummaries(p.schema!!, batchId)
            call.respond(summaries.map {
                BatchSummaryResponse(
                    id = it.id.toString(),
                    batchId = it.batchId.toString(),
                    scope = it.scope,
                    periodType = it.periodType,
                    content = it.content,
                    createdAt = it.createdAt,
                )
            })
        }

        post("/{id}/summarize") {
            val p = requireTenantRole(Role.CLIENT_ADMIN)
            val batchId = pathUuid("id")
            batchSummaryService.generateBatchSummary(p.schema!!, batchId)
            call.respond(mapOf("status" to "ok", "message" to "Summary regenerated"))
        }
    }

    route("/summaries") {
        post("/generate") {
            val p = requireTenantRole(Role.CLIENT_ADMIN)
            val req = call.receive<GenerateSummaryRequest>()
            val summaryId = batchSummaryService.generatePeriodSummary(
                schema = p.schema!!,
                sinceMs = req.sinceMs ?: 0L,
                untilMs = req.untilMs ?: System.currentTimeMillis(),
                departmentId = req.departmentId?.let { UUID.fromString(it) },
            )
            call.respond(HttpStatusCode.Created, mapOf("summaryId" to summaryId.toString()))
        }
    }
}

private fun com.malikov.db.BatchRow.toBatchResponse(): BatchResponse {
    val statsJson = callTypeStats?.let {
        try {
            Json.decodeFromString<CallTypeStatsResponse>(it)
        } catch (_: Exception) { null }
    }
    return BatchResponse(
        id = id.toString(),
        status = status,
        totalCalls = totalCalls,
        processedCalls = processedCalls,
        callTypeStats = statsJson,
        createdAt = createdAt,
        finishedAt = finishedAt,
    )
}
