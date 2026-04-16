package com.malikov.service

import com.malikov.config.NotFoundException
import com.malikov.db.ManagerPhoneRow
import com.malikov.db.ManagerRepository
import com.malikov.db.ManagerRow
import com.malikov.dto.AddPhoneRequest
import com.malikov.dto.ManagerPhoneResponse
import com.malikov.dto.ManagerResponse
import com.malikov.dto.PaginationParams
import com.malikov.dto.PaginatedResponse
import com.malikov.dto.paginated
import java.util.UUID

class ManagerService(private val repo: ManagerRepository) {

    fun list(
        schema: String,
        params: PaginationParams,
        isActive: Boolean? = null,
        search: String? = null,
        departmentId: UUID? = null,
    ): PaginatedResponse<ManagerResponse> {
        val (rows, total) = repo.list(schema, params.offset, params.pageSize, isActive, search, departmentId)
        return paginated(rows.map { it.toResponse() }, total, params)
    }

    fun getById(schema: String, managerId: UUID): ManagerResponse =
        repo.findById(schema, managerId)?.toResponse()
            ?: throw NotFoundException("Manager not found")

    fun getByUserId(schema: String, userId: UUID): ManagerResponse? =
        repo.findByUserId(schema, userId)?.toResponse()

    // ── Phone management ────────────────────────────────────────────────────

    fun addPhone(schema: String, managerId: UUID, req: AddPhoneRequest): ManagerPhoneResponse {
        // Verify manager exists
        repo.findById(schema, managerId) ?: throw NotFoundException("Manager not found")
        return repo.addPhone(schema, managerId, req.phoneNumber, req.label, req.isPrimary).toResponse()
    }

    fun removePhone(schema: String, managerId: UUID, phoneId: UUID) {
        repo.findById(schema, managerId) ?: throw NotFoundException("Manager not found")
        if (!repo.removePhone(schema, phoneId)) throw NotFoundException("Phone not found")
    }

    fun listPhones(schema: String, managerId: UUID): List<ManagerPhoneResponse> {
        repo.findById(schema, managerId) ?: throw NotFoundException("Manager not found")
        return repo.listPhones(schema, managerId).map { it.toResponse() }
    }

    // ── Mappers ─────────────────────────────────────────────────────────────

    private fun ManagerRow.toResponse() = ManagerResponse(
        id             = id.toString(),
        userId         = userId.toString(),
        fullName       = fullName,
        email          = email,
        departmentId   = departmentId?.toString(),
        departmentName = departmentName,
        extension      = extension,
        phoneNumber    = phoneNumber,
        phoneNumbers   = phoneNumbers.map { it.toResponse() },
        isActive       = isActive,
        createdAt      = createdAt,
    )

    private fun ManagerPhoneRow.toResponse() = ManagerPhoneResponse(
        id          = id.toString(),
        phoneNumber = phoneNumber,
        label       = label,
        isPrimary   = isPrimary,
    )
}
