import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api'
import type { UserResponse, Role } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserResponse | null>(null)
  const accessToken = ref<string | null>(localStorage.getItem('access_token'))
  const refreshToken = ref<string | null>(localStorage.getItem('refresh_token'))

  const isAuthenticated = computed(() => !!accessToken.value)
  const role = computed<Role | null>(() => user.value?.role ?? null)
  const isSuperAdmin = computed(() => role.value === 'SUPERADMIN')
  const isClientAdmin = computed(() => role.value === 'CLIENT_ADMIN')
  const isTeamLead = computed(() => role.value === 'TEAM_LEAD')
  const isManager = computed(() => role.value === 'MANAGER')

  const canManage = computed(() => isSuperAdmin.value || isClientAdmin.value || isTeamLead.value)

  function setTokens(access: string, refresh: string) {
    accessToken.value = access
    refreshToken.value = refresh
    localStorage.setItem('access_token', access)
    localStorage.setItem('refresh_token', refresh)
  }

  function clearAuth() {
    user.value = null
    accessToken.value = null
    refreshToken.value = null
    localStorage.removeItem('access_token')
    localStorage.removeItem('refresh_token')
    localStorage.removeItem('user')
  }

  async function login(email: string, password: string) {
    const { data } = await authApi.login({ email, password })
    setTokens(data.accessToken, data.refreshToken)
    user.value = data.user
    localStorage.setItem('user', JSON.stringify(data.user))
  }

  async function logout() {
    try {
      await authApi.logout(refreshToken.value ?? undefined)
    } finally {
      clearAuth()
    }
  }

  function restoreSession() {
    const stored = localStorage.getItem('user')
    if (stored && accessToken.value) {
      try {
        user.value = JSON.parse(stored)
      } catch {
        clearAuth()
      }
    }
  }

  return {
    user,
    accessToken,
    refreshToken,
    isAuthenticated,
    role,
    isSuperAdmin,
    isClientAdmin,
    isTeamLead,
    isManager,
    canManage,
    login,
    logout,
    restoreSession,
    clearAuth,
  }
})
