package com.malikov.service

import com.malikov.config.NotFoundException
import com.malikov.db.ManagerRepository
import com.malikov.db.ManagerRow
import com.malikov.dto.ManagerResponse
import com.malikov.dto.PaginationParams
import com.malikov.dto.PaginatedResponse
import com.malikov.dto.paginated
import java.util.UUID

class ManagerService(private val repo: ManagerRepository) {

    fun list(schema: String, params: PaginationParams, isActive: Boolean? = null): PaginatedResponse<ManagerResponse> {
        val (rows, total) = repo.list(schema, params.offset, params.pageSize, isActive)
        return paginated(rows.map { it.toResponse() }, total, params)
    }

    fun getById(schema: String, managerId: UUID): ManagerResponse =
        repo.findById(schema, managerId)?.toResponse()
            ?: throw NotFoundException("Manager not found")

    fun getByUserId(schema: String, userId: UUID): ManagerResponse? =
        repo.findByUserId(schema, userId)?.toResponse()

    private fun ManagerRow.toResponse() = ManagerResponse(
        id             = id.toString(),
        userId         = userId.toString(),
        fullName       = fullName,
        email          = email,
        departmentId   = departmentId?.toString(),
        departmentName = departmentName,
        extension      = extension,
        phoneNumber    = phoneNumber,
        isActive       = isActive,
        createdAt      = createdAt,
    )
}
