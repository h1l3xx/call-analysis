package com.malikov.dto

import kotlinx.serialization.Serializable

@Serializable
data class DepartmentCallPolicyResponse(
    val id: String,
    val departmentId: String?,
    val secondDepartmentId: String? = null,
    val callDirection: String,
    val scriptId: String?,
    val promptTemplateId: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class UpsertDepartmentCallPolicyRequest(
    val departmentId: String? = null,
    val secondDepartmentId: String? = null,
    val callDirection: String,
    val scriptId: String? = null,
    val promptTemplateId: String,
)

