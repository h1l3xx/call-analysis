import { useAuthStore } from '@/stores/auth'
import type { Role } from '@/types'

export function useAuth() {
  const store = useAuthStore()

  function hasRole(...roles: Role[]): boolean {
    return !!store.role && roles.includes(store.role)
  }

  function canAccess(requiredRoles: Role[]): boolean {
    if (!requiredRoles.length) return true
    return hasRole(...requiredRoles)
  }

  return {
    ...store,
    hasRole,
    canAccess,
  }
}
