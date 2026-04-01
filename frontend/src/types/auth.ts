export type Role = 'SUPERADMIN' | 'CLIENT_ADMIN' | 'TEAM_LEAD' | 'MANAGER'

export interface LoginRequest {
  email: string
  password: string
}

export interface RefreshRequest {
  refreshToken: string
}

export interface UserResponse {
  id: string
  email: string
  fullName: string
  role: Role
  tenantId: string | null
}

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  accessExpiresAt: number
  refreshExpiresAt: number
  user: UserResponse
}

export interface MeResponse {
  id: string
  role: Role
  tenantId: string | null
  schema: string | null
}
