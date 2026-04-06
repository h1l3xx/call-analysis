export type CallStatus = 'queued' | 'processing' | 'transcribed_only' | 'pending_review' | 'analyzing' | 'done' | 'no_speech' | 'failed'

export interface CreateCallRequest {
  managerId: string
  scriptId: string
  source?: string
  audioS3Key?: string
  audioFilename?: string
}

export interface CallResponse {
  id: string
  managerId: string | null
  managerName: string | null
  secondManagerId: string | null
  secondManagerName: string | null
  participantNames: string[] | null
  secondParticipantNames: string[] | null
  scriptId: string | null
  scriptName: string | null
  status: CallStatus
  source: string
  callType: string | null
  batchId: string | null
  durationSeconds: number | null
  createdAt: number
  finishedAt: number | null
}

export interface CallDetailResponse {
  id: string
  managerId: string | null
  managerName: string | null
  secondManagerId: string | null
  secondManagerName: string | null
  participantNames: string[] | null
  secondParticipantNames: string[] | null
  scriptId: string | null
  scriptName: string | null
  status: CallStatus
  source: string
  callType: string | null
  batchId: string | null
  audioS3Key: string | null
  audioFilename: string | null
  durationSeconds: number | null
  failedStep: string | null
  errorMessage: string | null
  createdAt: number
  finishedAt: number | null
}

export interface SpeakerTurnDto {
  speaker: string
  text: string
  start: number
  end: number
}

export interface TranscriptionResponse {
  rawText: string | null
  cleanedText: string | null
  language: string | null
  languageProb: number | null
  classification: string | null
  speakerTurns: SpeakerTurnDto[] | null
}

export interface SpeakerMetricsResponse {
  managerTalkRatio: number | null
  clientTalkRatio: number | null
  silenceRatio: number | null
  interruptionsCount: number | null
  avgPauseSeconds: number | null
  managerWpm: number | null
  clientWpm: number | null
  longestMonologueSec: number | null
}

export interface QualityScoreResponse {
  overallScore: number | null
  requiredScore: number | null
  optionalScore: number | null
  criteria: string | null
  strengths: string | null
  weaknesses: string | null
  recommendations: string | null
  summary: string | null
}

export interface ErrorEventResponse {
  id: number
  criterionName: string | null
  severity: string
  status: string | null
  score: number | null
  comment: string | null
  quote: string | null
}

export interface CallResultResponse {
  callId: string
  status: string
  transcription: TranscriptionResponse | null
  speakerMetrics: SpeakerMetricsResponse | null
  qualityScore: QualityScoreResponse | null
  errors: ErrorEventResponse[]
}

export interface BulkUploadItemResult {
  filename: string
  status: string
  callId?: string
  managerId?: string
  managerName?: string
  phone?: string
  callType?: string
  error?: string
}

export interface BulkUploadResponse {
  batchId: string
  total: number
  queued: number
  failed: number
  items: BulkUploadItemResult[]
}
