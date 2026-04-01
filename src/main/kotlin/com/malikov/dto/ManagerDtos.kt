package com.malikov.dto

import kotlinx.serialization.Serializable

@Serializable
data class ManagerResponse(
    val id: String,
    val userId: String,
    val fullName: String,
    val email: String,
    val departmentId: String?,
    val departmentName: String?,
    val extension: String?,
    val phoneNumber: String?,
    val isActive: Boolean,
    val createdAt: Long,
)
