package com.malikov.dto

import kotlinx.serialization.Serializable

@Serializable
data class ManagerPhoneResponse(
    val id: String,
    val phoneNumber: String,
    val label: String?,
    val isPrimary: Boolean,
)

@Serializable
data class ManagerResponse(
    val id: String,
    val userId: String,
    val fullName: String,
    val email: String,
    val departmentId: String?,
    val departmentName: String?,
    val extension: String?,
    val phoneNumber: String?,           // primary phone (backward compat)
    val phoneNumbers: List<ManagerPhoneResponse> = emptyList(),
    val isActive: Boolean,
    val createdAt: Long,
)

@Serializable
data class AddPhoneRequest(
    val phoneNumber: String,
    val label: String? = null,
    val isPrimary: Boolean = false,
)
