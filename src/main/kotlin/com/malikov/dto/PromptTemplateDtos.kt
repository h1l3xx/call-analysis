package com.malikov.dto

import kotlinx.serialization.Serializable

@Serializable
data class PromptTemplateResponse(
    val id: String,
    val name: String,
    val description: String?,
    val content: String,
    val kind: String = "evaluation",
    val isSystem: Boolean = false,
    val updatedAt: Long,
)

@Serializable
data class UpdatePromptTemplateRequest(
    val content: String,
)

@Serializable
data class CreatePromptTemplateRequest(
    val name: String,
    val description: String? = null,
    val content: String? = null,
)

@Serializable
data class SuggestRequest(
    val description: String,
)

@Serializable
data class SuggestResponse(
    val suggestions: List<String>,
)
