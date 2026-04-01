export interface PipelineHealthResponse {
  status: string
  service: string | null
  device: string | null
  model: string | null
  vllm_enabled: boolean | null
  vllm_available: boolean | null
  api_key_required: boolean | null
}

export interface PipelineArtifactFlags {
  has_transcript: boolean
  has_metadata: boolean
  has_quality: boolean
}

export interface PipelineQualitySummary {
  overall_score: number | null
  strengths_count: number
  weaknesses_count: number
}

export interface PipelineClassification {
  type: string | null
  sentiment: string | null
  key_topics: string[]
  admin_name: string | null
  clinic_address: string | null
}

export interface PipelineAnalysisSummary {
  result_id: string
  filename: string | null
  processed_at: string | null
  classification: PipelineClassification | null
  quality_summary: PipelineQualitySummary | null
  transcript_preview: string | null
  artifacts: PipelineArtifactFlags | null
}

export interface PipelineAnalysesListResponse {
  items: PipelineAnalysisSummary[]
  count: number
  total_count: number
  offset: number
  limit: number
  next_offset: number | null
  has_more: boolean
}
