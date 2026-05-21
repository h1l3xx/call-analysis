import client from './client'

export interface UserSearchResponse {
  id: string
  fullName: string
  email: string
  role: string
}

export interface LinkCodeResponse {
  code: string
  ttlMinutes: number
}

export interface TelegramStatusResponse {
  linked: boolean
  pendingCode: string | null
}

export interface DepartmentLeadResponse {
  userId: string
  fullName: string
  email: string
  departmentId: string
  departmentName: string
}

export const telegramApi = {
  generateLinkCode() {
    return client.post<LinkCodeResponse>('/api/v1/telegram/link-code')
  },
  getStatus() {
    return client.get<TelegramStatusResponse>('/api/v1/telegram/status')
  },
  unlink() {
    return client.delete('/api/v1/telegram/unlink')
  },
}

export const usersApi = {
  search(q: string, role?: string) {
    return client.get<UserSearchResponse[]>('/api/v1/users/search', { params: { q, role } })
  },
}

export const departmentLeadsApi = {
  listAll() {
    return client.get<DepartmentLeadResponse[]>('/api/v1/departments/leads')
  },
  list(departmentId: string) {
    return client.get<DepartmentLeadResponse[]>(`/api/v1/departments/${departmentId}/leads`)
  },
  assign(departmentId: string, userId: string) {
    return client.post(`/api/v1/departments/${departmentId}/leads`, { userId })
  },
  remove(departmentId: string, userId: string) {
    return client.delete(`/api/v1/departments/${departmentId}/leads/${userId}`)
  },
}
