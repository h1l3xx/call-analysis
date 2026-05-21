package com.malikov.service

import com.malikov.config.NotFoundException
import com.malikov.db.TenantAdminRepository
import com.malikov.db.TenantRow
import com.malikov.db.TenantUsageRow
import com.malikov.db.UserRow
import com.malikov.dto.*
import at.favre.lib.crypto.bcrypt.BCrypt
import java.util.UUID

class TenantAdminService(private val repo: TenantAdminRepository) {

    fun list(params: PaginationParams): PaginatedResponse<TenantResponse> {
        val (rows, total) = repo.list(params.offset, params.pageSize)
        return paginated(rows.map { it.toResponse() }, total, params)
    }

    fun create(request: CreateTenantRequest): TenantResponse {
        require(request.slug.isNotBlank()) { "Slug is required" }
        require(request.slug.matches(Regex("^[a-z0-9][a-z0-9-]*[a-z0-9]$"))) { "Slug must be lowercase alphanumeric with hyphens" }
        require(request.name.isNotBlank()) { "Name is required" }
        require(request.adminEmail.isNotBlank()) { "Admin email is required" }
        require(request.adminPassword.length >= 8) { "Admin password must be at least 8 characters" }

        val planId = UUID.fromString(request.planId)
        val tenant = repo.create(request.slug, request.name, planId)

        val passwordHash = BCrypt.withDefaults().hashToString(12, request.adminPassword.toCharArray())
        repo.createAdminUser(tenant.id, request.adminEmail, passwordHash, request.adminFullName)

        return tenant.toResponse()
    }

    fun listUsers(tenantId: UUID): List<TenantUserResponse> =
        repo.listUsers(tenantId).map { it.toUserResponse() }

    fun getUsage(tenantId: UUID): TenantUsageResponse {
        val usage = repo.getUsage(tenantId)
            ?: throw NotFoundException("Tenant or subscription not found")
        return usage.toUsageResponse()
    }

    private fun UserRow.toUserResponse() = TenantUserResponse(
        id           = id.toString(),
        email        = email,
        fullName     = fullName,
        role         = role,
        isActive     = isActive,
        createdAt    = createdAt,
        lastActiveAt = lastActiveAt,
    )

    private fun TenantRow.toResponse() = TenantResponse(
        id        = id.toString(),
        slug      = slug,
        name      = name,
        dbSchema  = dbSchema,
        isActive  = isActive,
        createdAt = createdAt,
    )

    private fun TenantUsageRow.toUsageResponse() = TenantUsageResponse(
        tenantId     = tenantId.toString(),
        tenantName   = tenantName,
        planName     = planName,
        minutesUsed  = minutesUsed,
        minutesLimit = minutesLimit,
        periodStart  = periodStart,
        periodEnd    = periodEnd,
    )
}
