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
  getCalls(id: string, params: { page?: number; pageSize?: number; callType?: string; ids?: string } = {}) {
    return client.get<PaginatedResponse<CallResponse>>(`/api/v1/batches/${id}/calls`, { params })
  },
  getSummaries(id: string) {
    return client.get<BatchSummaryResponse[]>(`/api/v1/batches/${id}/summary`)
  },
  regenerateSummary(id: string) {
    return client.post(`/api/v1/batches/${id}/summarize`)
  },
  async exportCsv(id: string, departmentId?: string) {
    const params: Record<string, string> = {}
    if (departmentId) params.departmentId = departmentId
    const response = await client.get(`/api/v1/batches/${id}/export`, {
      params,
      responseType: 'blob',
    })
    const url = window.URL.createObjectURL(new Blob([response.data]))
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', `batch-${id}.csv`)
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
  },
  generatePeriodSummary(sinceMs: number, untilMs: number, departmentId?: string) {
    return client.post<{ summaryId: string }>('/api/v1/summaries/generate', {
      sinceMs, untilMs, departmentId,
    })
  },
}
