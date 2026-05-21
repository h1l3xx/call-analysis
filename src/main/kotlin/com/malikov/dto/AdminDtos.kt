package com.malikov.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateTenantRequest(
    val slug: String,
    val name: String,
    val planId: String,
    val adminEmail: String,
    val adminPassword: String,
    val adminFullName: String,
)

@Serializable
data class TenantResponse(
    val id: String,
    val slug: String,
    val name: String,
    val dbSchema: String,
    val isActive: Boolean,
    val createdAt: Long,
)

@Serializable
data class TenantUsageResponse(
    val tenantId: String,
    val tenantName: String,
    val planName: String,
    val minutesUsed: Int,
    val minutesLimit: Int,
    val periodStart: String,
    val periodEnd: String,
)

@Serializable
data class TenantUserResponse(
    val id: String,
    val email: String,
    val fullName: String,
    val role: String,
    val isActive: Boolean,
    val createdAt: Long,
    val lastActiveAt: Long?,
)
