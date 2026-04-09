package com.malikov.service

import com.malikov.config.NotFoundException
import com.malikov.db.DepartmentCallPolicyRepository
import com.malikov.db.PromptTemplateRepository
import com.malikov.db.ScriptRepository
import com.malikov.dto.DepartmentCallPolicyResponse
import com.malikov.dto.UpsertDepartmentCallPolicyRequest
import java.util.UUID

class DepartmentCallPolicyService(
    private val repo: DepartmentCallPolicyRepository,
    private val scriptRepo: ScriptRepository,
    private val promptRepo: PromptTemplateRepository,
) {
    private val allowedDirections = setOf("internal", "external_incoming", "external_outgoing", "unknown")

    fun list(schema: String): List<DepartmentCallPolicyResponse> =
        repo.list(schema).map {
            DepartmentCallPolicyResponse(
                id = it.id.toString(),
                departmentId = it.departmentId?.toString(),
                callDirection = it.callDirection,
                scriptId = it.scriptId.toString(),
                promptTemplateId = it.promptTemplateId,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }

    fun upsert(schema: String, request: UpsertDepartmentCallPolicyRequest): DepartmentCallPolicyResponse {
        require(request.callDirection in allowedDirections) {
            "Unsupported callDirection '${request.callDirection}'"
        }
        val scriptId = UUID.fromString(request.scriptId)
        scriptRepo.findById(schema, scriptId) ?: throw NotFoundException("Script not found")
        promptRepo.findById(schema, request.promptTemplateId) ?: throw NotFoundException("Prompt template not found")
        val departmentId = request.departmentId?.let { UUID.fromString(it) }

        val row = repo.upsert(
            schema = schema,
            departmentId = departmentId,
            callDirection = request.callDirection,
            scriptId = scriptId,
            promptTemplateId = request.promptTemplateId,
        )

        return DepartmentCallPolicyResponse(
            id = row.id.toString(),
            departmentId = row.departmentId?.toString(),
            callDirection = row.callDirection,
            scriptId = row.scriptId.toString(),
            promptTemplateId = row.promptTemplateId,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )
    }
}

