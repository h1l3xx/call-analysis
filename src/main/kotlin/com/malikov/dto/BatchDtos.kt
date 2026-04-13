package com.malikov.dto

import kotlinx.serialization.Serializable

@Serializable
data class BatchResponse(
    val id: String,
    val status: String,
    val totalCalls: Int,
    val processedCalls: Int,
    val callTypeStats: CallTypeStatsResponse?,
    val noSpeechCount: Int = 0,
    val failedCount: Int = 0,
    val transcribedOnlyCount: Int = 0,
    val createdAt: Long,
    val finishedAt: Long?,
)

@Serializable
data class CallTypeStatsResponse(
    val internal: Int = 0,
    val externalIncoming: Int = 0,
    val externalOutgoing: Int = 0,
    val unknown: Int = 0,
)

@Serializable
data class BatchSummaryResponse(
    val id: String,
    val batchId: String,
    val scope: String,
    val periodType: String,
    val content: String?,
    val createdAt: Long,
)

@Serializable
data class BatchDetailResponse(
    val batch: BatchResponse,
    val summaries: List<BatchSummaryResponse>,
)

@Serializable
data class GenerateSummaryRequest(
    val sinceMs: Long? = null,
    val untilMs: Long? = null,
    val departmentId: String? = null,
)
