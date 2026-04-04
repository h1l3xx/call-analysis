package com.malikov.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateCallRequest(
    val managerId: String,
    val scriptId: String,
    val source: String = "direct",
    val audioS3Key: String? = null,
    val audioFilename: String? = null,
)

@Serializable
data class CallResponse(
    val id: String,
    val managerId: String?,
    val managerName: String?,
    val secondManagerId: String? = null,
    val secondManagerName: String? = null,
    val participantNames: List<String>? = null,
    val secondParticipantNames: List<String>? = null,
    val scriptId: String?,
    val scriptName: String?,
    val status: String,
    val source: String,
    val callType: String? = null,
    val batchId: String? = null,
    val durationSeconds: Int?,
    val createdAt: Long,
    val finishedAt: Long?,
)

@Serializable
data class CallDetailResponse(
    val id: String,
    val managerId: String?,
    val managerName: String?,
    val secondManagerId: String? = null,
    val secondManagerName: String? = null,
    val participantNames: List<String>? = null,
    val secondParticipantNames: List<String>? = null,
    val scriptId: String?,
    val scriptName: String?,
    val status: String,
    val source: String,
    val callType: String? = null,
    val batchId: String? = null,
    val audioS3Key: String?,
    val audioFilename: String?,
    val durationSeconds: Int?,
    val failedStep: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val finishedAt: Long?,
)

@Serializable
data class CallResultResponse(
    val callId: String,
    val status: String,
    val transcription: TranscriptionResponse?,
    val speakerMetrics: SpeakerMetricsResponse?,
    val qualityScore: QualityScoreResponse?,
    val errors: List<ErrorEventResponse>,
)

@Serializable
data class SpeakerTurnDto(
    val speaker: String,
    val text: String,
    val start: Double,
    val end: Double,
)

@Serializable
data class TranscriptionResponse(
    val rawText: String?,
    val cleanedText: String?,
    val language: String?,
    val languageProb: Double?,
    val classification: String?,
    val speakerTurns: List<SpeakerTurnDto>? = null,
)

@Serializable
data class SpeakerMetricsResponse(
    val managerTalkRatio: Double?,
    val clientTalkRatio: Double?,
    val silenceRatio: Double?,
    val interruptionsCount: Int?,
    val avgPauseSeconds: Double?,
    val managerWpm: Double?,
    val clientWpm: Double?,
    val longestMonologueSec: Double?,
)

@Serializable
data class QualityScoreResponse(
    val overallScore: Double?,
    val requiredScore: Double?,
    val optionalScore: Double?,
    val criteria: String?,
    val strengths: String?,
    val weaknesses: String?,
    val recommendations: String?,
    val summary: String?,
)

@Serializable
data class ErrorEventResponse(
    val id: Int,
    val criterionName: String?,
    val severity: String,
    val status: String?,
    val score: Double?,
    val comment: String?,
    val quote: String?,
)

@Serializable
data class BulkUploadItemResult(
    val filename: String,
    val status: String,
    val callId: String? = null,
    val managerId: String? = null,
    val managerName: String? = null,
    val phone: String? = null,
    val callType: String? = null,
    val error: String? = null,
)

@Serializable
data class BulkUploadResponse(
    val batchId: String,
    val total: Int,
    val queued: Int,
    val failed: Int,
    val items: List<BulkUploadItemResult>,
)
