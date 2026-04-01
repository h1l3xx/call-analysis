package com.malikov.service

import com.malikov.config.NotFoundException
import com.malikov.db.ScriptDetailRow
import com.malikov.db.ScriptRepository
import com.malikov.db.ScriptRow
import com.malikov.db.CriterionRow
import com.malikov.dto.*
import java.util.UUID

class ScriptService(private val repo: ScriptRepository) {

    fun list(schema: String, params: PaginationParams, isActive: Boolean? = null): PaginatedResponse<ScriptResponse> {
        val (rows, total) = repo.list(schema, params.offset, params.pageSize, isActive)
        return paginated(rows.map { it.toResponse() }, total, params)
    }

    fun getById(schema: String, scriptId: UUID): ScriptDetailResponse {
        val detail = repo.findById(schema, scriptId)
            ?: throw NotFoundException("Script not found")
        return detail.toDetailResponse()
    }

    fun create(schema: String, request: CreateScriptRequest): ScriptDetailResponse {
        require(request.name.isNotBlank()) { "Script name is required" }
        require(request.callType.isNotBlank()) { "Call type is required" }

        val scriptId = repo.create(
            schema      = schema,
            name        = request.name,
            callType    = request.callType,
            description = request.description,
            criteria    = request.criteria,
        )
        return getById(schema, scriptId)
    }

    fun update(schema: String, scriptId: UUID, request: UpdateScriptRequest): ScriptDetailResponse {
        val updated = repo.update(
            schema      = schema,
            scriptId    = scriptId,
            name        = request.name,
            callType    = request.callType,
            description = request.description,
            isActive    = request.isActive,
            criteria    = request.criteria,
        )
        if (!updated) throw NotFoundException("Script not found")
        return getById(schema, scriptId)
    }

    private fun ScriptRow.toResponse() = ScriptResponse(
        id            = id.toString(),
        name          = name,
        callType      = callType,
        description   = description,
        isActive      = isActive,
        criteriaCount = criteriaCount,
        createdAt     = createdAt,
        updatedAt     = updatedAt,
    )

    private fun ScriptDetailRow.toDetailResponse() = ScriptDetailResponse(
        id          = script.id.toString(),
        name        = script.name,
        callType    = script.callType,
        description = script.description,
        isActive    = script.isActive,
        criteria    = criteria.map { it.toResponse() },
        createdAt   = script.createdAt,
        updatedAt   = script.updatedAt,
    )

    private fun CriterionRow.toResponse() = CriterionResponse(
        id          = id,
        orderNum    = orderNum,
        name        = name,
        description = description,
        groupType   = groupType,
        weight      = weight,
        scoringType = scoringType,
        isActive    = isActive,
    )
}
