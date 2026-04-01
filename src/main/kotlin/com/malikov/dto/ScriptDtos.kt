package com.malikov.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateScriptRequest(
    val name: String,
    val callType: String,
    val description: String? = null,
    val criteria: List<CreateCriterionRequest> = emptyList(),
)

@Serializable
data class CreateCriterionRequest(
    val orderNum: Int,
    val name: String,
    val description: String,
    val groupType: String = "required",
    val weight: Double = 1.0,
    val scoringType: String = "binary",
)

@Serializable
data class UpdateScriptRequest(
    val name: String? = null,
    val callType: String? = null,
    val description: String? = null,
    val isActive: Boolean? = null,
    val criteria: List<CreateCriterionRequest>? = null,
)

@Serializable
data class ScriptResponse(
    val id: String,
    val name: String,
    val callType: String,
    val description: String?,
    val isActive: Boolean,
    val criteriaCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ScriptDetailResponse(
    val id: String,
    val name: String,
    val callType: String,
    val description: String?,
    val isActive: Boolean,
    val criteria: List<CriterionResponse>,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class CriterionResponse(
    val id: Int,
    val orderNum: Int,
    val name: String,
    val description: String,
    val groupType: String,
    val weight: Double,
    val scoringType: String,
    val isActive: Boolean,
)
