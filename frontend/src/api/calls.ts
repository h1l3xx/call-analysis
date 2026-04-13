import client from './client'
import type {
  PaginatedResponse,
  CallResponse,
  CallDetailResponse,
  CallResultResponse,
  BulkUploadResponse,
} from '@/types'

export const callsApi = {
  list(params: { page?: number; pageSize?: number; status?: string; managerId?: string; managerIds?: string; departmentId?: string; search?: string } = {}) {
    return client.get<PaginatedResponse<CallResponse>>('/api/v1/calls', { params })
  },
  departments() {
    return client.get<{ id: string; name: string }[]>('/api/v1/calls/departments')
  },
  stats(params: { since?: number; until?: number; managerId?: string } = {}) {
    return client.get<{ total: number; processing: number; done: number; failed: number; noSpeech: number; avgScore: number }>('/api/v1/calls/stats', { params })
  },
  get(id: string) {
    return client.get<CallDetailResponse>(`/api/v1/calls/${id}`)
  },
  getResult(id: string) {
    return client.get<CallResultResponse>(`/api/v1/calls/${id}/result`)
  },
  upload(managerId: string, scriptId: string, file: File, onProgress?: (pct: number) => void) {
    const form = new FormData()
    form.append('managerId', managerId)
    form.append('scriptId', scriptId)
    form.append('file', file)
    return client.post<CallResponse>('/api/v1/calls/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300_000,
      onUploadProgress: (e) => {
        if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
      },
    })
  },

  getAudioUrl(id: string) {
    return `/api/v1/calls/${id}/audio`
  },

  async exportCsv(params: {
    departmentId?: string
    managerIds?: string
    status?: string
    callType?: string
    sinceMs?: number
    untilMs?: number
    search?: string
  } = {}) {
    const response = await client.get('/api/v1/calls/export', {
      params,
      responseType: 'blob',
    })
    const url = window.URL.createObjectURL(new Blob([response.data]))
    const link = document.createElement('a')
    link.href = url
    const today = new Date().toISOString().slice(0, 10)
    link.setAttribute('download', `calls-export-${today}.csv`)
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
  },

  bulkUpload(
    files: File[],
    options?: {
      batchId?: string
      final?: boolean
      onProgress?: (pct: number) => void
    },
  ) {
    const form = new FormData()
    for (const file of files) {
      form.append('files', file)
    }
    const params: Record<string, string> = {}
    if (options?.batchId) params.batchId = options.batchId
    if (options?.final === false) params.final = 'false'
    return client.post<BulkUploadResponse>('/api/v1/calls/bulk-upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      params,
      timeout: 0,
      onUploadProgress: (e) => {
        if (options?.onProgress && e.total)
          options.onProgress(Math.round((e.loaded / e.total) * 100))
      },
    })
  },

  delete(id: string) {
    return client.delete(`/api/v1/calls/${id}`)
  },
}
