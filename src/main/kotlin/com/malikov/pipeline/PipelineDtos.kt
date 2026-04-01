package com.malikov.pipeline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── POST /analyze request (criteria sent as form-data JSON) ─────────

@Serializable
data class PipelineCriterionInput(
    val id: Int,
    val name: String,
    val description: String,
    val block: String = "main",
)

// ── POST /analyze response ──────────────────────────────────────────

@Serializable
data class PipelineAnalyzeResponse(
    @SerialName("result_id")       val resultId: String,
    val filename: String,
    @SerialName("raw_transcription") val rawTranscription: String? = null,
    @SerialName("cleaned_text")    val cleanedText: String? = null,
    val classification: PipelineClassification? = null,
    @SerialName("asr_metrics")     val asrMetrics: PipelineAsrMetrics? = null,
    @SerialName("speaker_turns")   val speakerTurns: List<PipelineSpeakerTurn>? = null,
    @SerialName("speaker_metrics") val speakerMetrics: PipelineSpeakerMetrics? = null,
    val quality: PipelineQuality? = null,
    val artifacts: PipelineArtifacts? = null,
)

@Serializable
data class PipelineClassification(
    val type: String? = null,
    val sentiment: String? = null,
    @SerialName("key_topics") val keyTopics: List<String> = emptyList(),
    @SerialName("admin_name") val adminName: String? = null,
    @SerialName("clinic_address") val clinicAddress: String? = null,
)

@Serializable
data class PipelineAsrMetrics(
    @SerialName("elapsed_time")         val elapsedTime: Double? = null,
    @SerialName("audio_duration")       val audioDuration: Double? = null,
    val rtf: Double? = null,
    @SerialName("segment_count")        val segmentCount: Int? = null,
    val language: String? = null,
    @SerialName("language_probability") val languageProbability: Double? = null,
)

@Serializable
data class PipelineSpeakerTurn(
    val speaker: String,
    val text: String,
    val start: Double,
    val end: Double,
)

@Serializable
data class PipelineSpeakerMetrics(
    @SerialName("manager_talk_ratio")    val managerTalkRatio: Double? = null,
    @SerialName("client_talk_ratio")     val clientTalkRatio: Double? = null,
    @SerialName("silence_ratio")         val silenceRatio: Double? = null,
    @SerialName("interruptions_count")   val interruptionsCount: Int? = null,
    @SerialName("avg_pause_seconds")     val avgPauseSeconds: Double? = null,
    @SerialName("manager_wpm")           val managerWpm: Double? = null,
    @SerialName("client_wpm")            val clientWpm: Double? = null,
    @SerialName("longest_monologue_sec") val longestMonologueSec: Double? = null,
)

@Serializable
data class PipelineQuality(
    @SerialName("call_id")          val callId: String? = null,
    @SerialName("equipment_type")   val equipmentType: String? = null,
    @SerialName("processed_at")     val processedAt: String? = null,
    @SerialName("admin_name")       val adminName: String? = null,
    @SerialName("clinic_address")   val clinicAddress: String? = null,
    @SerialName("equipment_detected") val equipmentDetected: String? = null,
    @SerialName("criteria_evaluations") val criteriaEvaluations: List<PipelineCriterionEval> = emptyList(),
    @SerialName("overall_score")    val overallScore: Double? = null,
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val reasoning: String? = null,
    @SerialName("tokens_used")      val tokensUsed: PipelineTokensUsed? = null,
    @SerialName("cost_usd")         val costUsd: Double? = null,
)

@Serializable
data class PipelineCriterionEval(
    val id: Int,
    val name: String,
    val score: Double? = null,
    val comment: String? = null,
    val relevant: Boolean = true,
)

@Serializable
data class PipelineTokensUsed(
    val prompt: Int = 0,
    val completion: Int = 0,
    val total: Int = 0,
)

@Serializable
data class PipelineArtifacts(
    @SerialName("transcript_path") val transcriptPath: String? = null,
    @SerialName("metadata_path")   val metadataPath: String? = null,
    @SerialName("quality_path")    val qualityPath: String? = null,
)

// ── GET /healthz response ───────────────────────────────────────────

@Serializable
data class PipelineHealthResponse(
    val status: String,
    val service: String? = null,
    val device: String? = null,
    val model: String? = null,
    @SerialName("vllm_enabled")     val vllmEnabled: Boolean? = null,
    @SerialName("vllm_available")   val vllmAvailable: Boolean? = null,
    @SerialName("api_key_required") val apiKeyRequired: Boolean? = null,
)

// ── GET /analyses response ──────────────────────────────────────────

@Serializable
data class PipelineAnalysesListResponse(
    val items: List<PipelineAnalysisSummary> = emptyList(),
    val count: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
    val offset: Int = 0,
    val limit: Int = 20,
    @SerialName("next_offset") val nextOffset: Int? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class PipelineAnalysisSummary(
    @SerialName("result_id") val resultId: String,
    val filename: String? = null,
    @SerialName("processed_at") val processedAt: String? = null,
    val classification: PipelineClassification? = null,
    @SerialName("quality_summary") val qualitySummary: PipelineQualitySummary? = null,
    @SerialName("transcript_preview") val transcriptPreview: String? = null,
    val artifacts: PipelineArtifactFlags? = null,
)

@Serializable
data class PipelineQualitySummary(
    @SerialName("overall_score") val overallScore: Double? = null,
    @SerialName("strengths_count") val strengthsCount: Int = 0,
    @SerialName("weaknesses_count") val weaknessesCount: Int = 0,
)

@Serializable
data class PipelineArtifactFlags(
    @SerialName("has_transcript") val hasTranscript: Boolean = false,
    @SerialName("has_metadata") val hasMetadata: Boolean = false,
    @SerialName("has_quality") val hasQuality: Boolean = false,
)

// ── GET /analyses/{id} response ────────────────────────────────────

@Serializable
data class PipelineAnalysisDetailResponse(
    @SerialName("result_id") val resultId: String,
    val filename: String? = null,
    @SerialName("processed_at") val processedAt: String? = null,
    val summary: PipelineAnalysisDetailSummary? = null,
    @SerialName("cleaned_text") val cleanedText: String? = null,
    val classification: PipelineClassification? = null,
    @SerialName("asr_metrics") val asrMetrics: PipelineAsrMetrics? = null,
    val quality: PipelineQuality? = null,
    val artifacts: PipelineArtifacts? = null,
)

@Serializable
data class PipelineAnalysisDetailSummary(
    @SerialName("classification_type") val classificationType: String? = null,
    @SerialName("overall_score") val overallScore: Double? = null,
    @SerialName("strengths_count") val strengthsCount: Int = 0,
    @SerialName("weaknesses_count") val weaknessesCount: Int = 0,
    val artifacts: PipelineArtifactFlags? = null,
)

// ── Error response ──────────────────────────────────────────────────

@Serializable
data class PipelineErrorResponse(
    val detail: String,
)
