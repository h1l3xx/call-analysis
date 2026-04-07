import client from './client'
import type { ManagerResponse, PaginatedResponse } from '@/types'

export const managersApi = {
  list(params: { page?: number; pageSize?: number; isActive?: boolean } = {}) {
    return client.get<PaginatedResponse<ManagerResponse>>('/api/v1/managers', { params })
  },
  get(id: string) {
    return client.get<ManagerResponse>(`/api/v1/managers/${id}`)
  },
  async allActive(): Promise<ManagerResponse[]> {
    const { data } = await client.get<PaginatedResponse<ManagerResponse>>('/api/v1/managers', {
      params: { isActive: true, pageSize: 500 },
    })
    return data.items
  },
}
