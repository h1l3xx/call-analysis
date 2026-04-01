export interface CreateTenantRequest {
  slug: string
  name: string
  planId: string
  adminEmail: string
  adminPassword: string
  adminFullName: string
}

export interface TenantResponse {
  id: string
  slug: string
  name: string
  dbSchema: string
  isActive: boolean
  createdAt: number
}

export interface TenantUsageResponse {
  tenantId: string
  tenantName: string
  planName: string
  minutesUsed: number
  minutesLimit: number
  periodStart: string
  periodEnd: string
}
