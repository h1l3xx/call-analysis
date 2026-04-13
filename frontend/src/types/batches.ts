export type BatchStatus = 'uploading' | 'transcribing' | 'evaluating' | 'summarizing' | 'done' | 'failed'

export interface CallTypeStats {
  internal: number
  externalIncoming: number
  externalOutgoing: number
  unknown: number
}

export interface BatchResponse {
  id: string
  status: BatchStatus
  totalCalls: number
  processedCalls: number
  callTypeStats: CallTypeStats | null
  noSpeechCount: number
  failedCount: number
  transcribedOnlyCount: number
  createdAt: number
  finishedAt: number | null
}

export interface BatchSummaryResponse {
  id: string
  batchId: string
  scope: string
  periodType: string
  content: string | null
  createdAt: number
}

export interface BatchDetailResponse {
  batch: BatchResponse
  summaries: BatchSummaryResponse[]
}
