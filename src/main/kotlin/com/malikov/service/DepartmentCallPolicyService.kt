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
    private val allowedDirections = setOf(
        "internal_incoming",
        "internal_outgoing",
        "external_incoming",
        "external_outgoing",
        "unknown",
    )

    fun list(schema: String): List<DepartmentCallPolicyResponse> =
        repo.list(schema).map {
            DepartmentCallPolicyResponse(
                id = it.id.toString(),
                departmentId = it.departmentId?.toString(),
                secondDepartmentId = it.secondDepartmentId?.toString(),
                callDirection = it.callDirection,
                scriptId = it.scriptId?.toString(),
                promptTemplateId = it.promptTemplateId,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }

    fun upsert(schema: String, request: UpsertDepartmentCallPolicyRequest): DepartmentCallPolicyResponse {
        require(request.callDirection in allowedDirections) {
            "Unsupported callDirection '${request.callDirection}'"
        }
        val scriptId = request.scriptId?.let(UUID::fromString)
        if (scriptId != null) {
            scriptRepo.findById(schema, scriptId) ?: throw NotFoundException("Script not found")
        }
        val prompt = promptRepo.findById(schema, request.promptTemplateId) ?: throw NotFoundException("Prompt template not found")
        require(prompt.kind == "evaluation" || prompt.id.startsWith("eval_")) {
            "Template '${request.promptTemplateId}' is not an evaluation template"
        }
        val departmentId = request.departmentId?.let { UUID.fromString(it) }
        val secondDepartmentId = request.secondDepartmentId?.let { UUID.fromString(it) }
        require(!(departmentId == null && secondDepartmentId != null)) {
            "secondDepartmentId requires departmentId"
        }
        if (departmentId != null && secondDepartmentId != null) {
            require(departmentId != secondDepartmentId) { "departmentId and secondDepartmentId must differ" }
        }
        val normalizedPair = if (departmentId != null && secondDepartmentId != null) {
            val a = departmentId.toString()
            val b = secondDepartmentId.toString()
            if (a <= b) departmentId to secondDepartmentId else secondDepartmentId to departmentId
        } else {
            departmentId to secondDepartmentId
        }

        val row = repo.upsert(
            schema = schema,
            departmentId = normalizedPair.first,
            secondDepartmentId = normalizedPair.second,
            callDirection = request.callDirection,
            scriptId = scriptId,
            promptTemplateId = request.promptTemplateId,
        )

        return DepartmentCallPolicyResponse(
            id = row.id.toString(),
            departmentId = row.departmentId?.toString(),
            secondDepartmentId = row.secondDepartmentId?.toString(),
            callDirection = row.callDirection,
            scriptId = row.scriptId?.toString(),
            promptTemplateId = row.promptTemplateId,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )
    }

    fun deleteDepartmentOverride(schema: String, departmentId: String, callDirection: String): Boolean {
        require(callDirection in allowedDirections) {
            "Unsupported callDirection '$callDirection'"
        }
        return repo.deleteDepartmentOverride(schema, UUID.fromString(departmentId), callDirection)
    }
}

