import client from './client'
import type { PaginatedResponse, TenantResponse, TenantUsageResponse, CreateTenantRequest } from '@/types'

export const adminApi = {
  listTenants(params: { page?: number; pageSize?: number } = {}) {
    return client.get<PaginatedResponse<TenantResponse>>('/api/v1/admin/tenants', { params })
  },
  createTenant(data: CreateTenantRequest) {
    return client.post<TenantResponse>('/api/v1/admin/tenants', data)
  },
  getTenantUsage(tenantId: string) {
    return client.get<TenantUsageResponse>(`/api/v1/admin/tenants/${tenantId}/usage`)
  },
}
