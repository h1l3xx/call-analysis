import client from './client'
import type { LoginRequest, TokenResponse, MeResponse } from '@/types'

export const authApi = {
  login(data: LoginRequest) {
    return client.post<TokenResponse>('/api/v1/auth/login', data)
  },
  refresh(refreshToken: string) {
    return client.post<TokenResponse>('/api/v1/auth/refresh', { refreshToken })
  },
  logout(refreshToken?: string) {
    return client.post('/api/v1/auth/logout', refreshToken ? { refreshToken } : {})
  },
  logoutAll() {
    return client.post('/api/v1/auth/logout-all')
  },
  me() {
    return client.get<MeResponse>('/api/v1/auth/me')
  },
}
