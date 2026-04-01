import client from './client'
import type {
  PaginatedResponse,
  BatchResponse,
  BatchDetailResponse,
  BatchSummaryResponse,
  CallResponse,
} from '@/types'

export const batchesApi = {
  list(params: { page?: number; pageSize?: number } = {}) {
    return client.get<PaginatedResponse<BatchResponse>>('/api/v1/batches', { params })
  },
  get(id: string) {
    return client.get<BatchDetailResponse>(`/api/v1/batches/${id}`)
  },
  getCalls(id: string, params: { page?: number; pageSize?: number; callType?: string } = {}) {
    return client.get<PaginatedResponse<CallResponse>>(`/api/v1/batches/${id}/calls`, { params })
  },
  getSummaries(id: string) {
    return client.get<BatchSummaryResponse[]>(`/api/v1/batches/${id}/summary`)
  },
  regenerateSummary(id: string) {
    return client.post(`/api/v1/batches/${id}/summarize`)
  },
  generatePeriodSummary(sinceMs: number, untilMs: number, departmentId?: string) {
    return client.post<{ summaryId: string }>('/api/v1/summaries/generate', {
      sinceMs, untilMs, departmentId,
    })
  },
}
