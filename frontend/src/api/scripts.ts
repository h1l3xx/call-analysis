import client from './client'
import type {
  PaginatedResponse,
  ScriptResponse,
  ScriptDetailResponse,
  CreateScriptRequest,
  UpdateScriptRequest,
} from '@/types'

export const scriptsApi = {
  list(params: { page?: number; pageSize?: number; isActive?: boolean } = {}) {
    return client.get<PaginatedResponse<ScriptResponse>>('/api/v1/scripts', { params })
  },
  get(id: string) {
    return client.get<ScriptDetailResponse>(`/api/v1/scripts/${id}`)
  },
  create(data: CreateScriptRequest) {
    return client.post<ScriptDetailResponse>('/api/v1/scripts', data)
  },
  update(id: string, data: UpdateScriptRequest) {
    return client.put<ScriptDetailResponse>(`/api/v1/scripts/${id}`, data)
  },
  delete(id: string) {
    return client.delete(`/api/v1/scripts/${id}`)
  },
}
