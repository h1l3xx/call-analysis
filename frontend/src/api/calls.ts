import client from './client'
import type {
  PaginatedResponse,
  CallResponse,
  CallDetailResponse,
  CallResultResponse,
  BulkUploadResponse,
} from '@/types'

export const callsApi = {
  list(params: { page?: number; pageSize?: number; status?: string; managerId?: string } = {}) {
    return client.get<PaginatedResponse<CallResponse>>('/api/v1/calls', { params })
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

  bulkUpload(files: File[], onProgress?: (pct: number) => void) {
    const form = new FormData()
    for (const file of files) {
      form.append('files', file)
    }
    return client.post<BulkUploadResponse>('/api/v1/calls/bulk-upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 0,
      onUploadProgress: (e) => {
        if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
      },
    })
  },
}
