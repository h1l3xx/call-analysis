import client from './client'
import type { AddPhoneRequest, ManagerEvaluationResponse, ManagerPhoneResponse, ManagerResponse, PaginatedResponse } from '@/types'

export const managersApi = {
  list(params: { page?: number; pageSize?: number; isActive?: boolean; search?: string; departmentId?: string } = {}) {
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

  // Phone management
  listPhones(managerId: string) {
    return client.get<ManagerPhoneResponse[]>(`/api/v1/managers/${managerId}/phones`)
  },
  addPhone(managerId: string, req: AddPhoneRequest) {
    return client.post<ManagerPhoneResponse>(`/api/v1/managers/${managerId}/phones`, req)
  },
  removePhone(managerId: string, phoneId: string) {
    return client.delete(`/api/v1/managers/${managerId}/phones/${phoneId}`)
  },

  // Period evaluations
  listEvaluations(managerId: string) {
    return client.get<ManagerEvaluationResponse[]>(`/api/v1/managers/${managerId}/evaluations`)
  },
  evaluate(managerId: string, params: { since?: number; until?: number; templateId?: string } = {}) {
    return client.post<ManagerEvaluationResponse>(`/api/v1/managers/${managerId}/evaluate`, null, { params })
  },
}
