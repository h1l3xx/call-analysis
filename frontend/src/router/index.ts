import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import type { Role } from '@/types'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    roles?: Role[]
    layout?: 'auth' | 'default'
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/pages/LoginPage.vue'),
      meta: { layout: 'auth' },
    },
    {
      path: '/',
      name: 'home',
      redirect: () => {
        const auth = useAuthStore()
        return auth.isSuperAdmin ? '/admin/tenants' : '/dashboard'
      },
    },
    {
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('@/pages/DashboardPage.vue'),
      meta: { requiresAuth: true, roles: ['CLIENT_ADMIN', 'TEAM_LEAD', 'MANAGER'] },
    },
    {
      path: '/calls',
      name: 'calls',
      component: () => import('@/pages/calls/CallsListPage.vue'),
      meta: { requiresAuth: true, roles: ['CLIENT_ADMIN', 'TEAM_LEAD', 'MANAGER'] },
    },
    {
      path: '/calls/:id',
      name: 'call-detail',
      component: () => import('@/pages/calls/CallDetailPage.vue'),
      meta: { requiresAuth: true, roles: ['CLIENT_ADMIN', 'TEAM_LEAD', 'MANAGER'] },
    },
    {
      path: '/scripts',
      name: 'scripts',
      component: () => import('@/pages/scripts/ScriptsListPage.vue'),
      meta: { requiresAuth: true, roles: ['CLIENT_ADMIN', 'TEAM_LEAD', 'MANAGER'] },
    },
    {
      path: '/scripts/:id',
      name: 'script-detail',
      component: () => import('@/pages/scripts/ScriptDetailPage.vue'),
      meta: { requiresAuth: true, roles: ['CLIENT_ADMIN', 'TEAM_LEAD', 'MANAGER'] },
    },
    {
      path: '/managers',
      name: 'managers',
      component: () => import('@/pages/managers/ManagersListPage.vue'),
      meta: { requiresAuth: true, roles: ['CLIENT_ADMIN', 'TEAM_LEAD', 'MANAGER'] },
    },
    {
      path: '/batches',
      name: 'batches',
      component: () => import('@/pages/batches/BatchesListPage.vue'),
      meta: { requiresAuth: true, roles: ['CLIENT_ADMIN', 'TEAM_LEAD'] },
    },
    {
      path: '/batches/:id',
      name: 'batch-detail',
      component: () => import('@/pages/batches/BatchDetailPage.vue'),
      meta: { requiresAuth: true, roles: ['CLIENT_ADMIN', 'TEAM_LEAD'] },
    },
    {
      path: '/export',
      name: 'export',
      component: () => import('@/pages/ExportPage.vue'),
      meta: { requiresAuth: true, roles: ['CLIENT_ADMIN', 'TEAM_LEAD'] },
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/pages/SettingsPage.vue'),
      meta: { requiresAuth: true, roles: ['CLIENT_ADMIN', 'TEAM_LEAD', 'MANAGER'] },
    },
    {
      path: '/settings/prompts',
      name: 'prompt-templates',
      component: () => import('@/pages/PromptTemplatesPage.vue'),
      meta: { requiresAuth: true, roles: ['CLIENT_ADMIN', 'TEAM_LEAD'] },
    },
    {
      path: '/settings/policies',
      name: 'department-call-policies',
      component: () => import('@/pages/settings/DepartmentCallPoliciesPage.vue'),
      meta: { requiresAuth: true, roles: ['CLIENT_ADMIN', 'TEAM_LEAD'] },
    },
    {
      path: '/admin/tenants',
      name: 'admin-tenants',
      component: () => import('@/pages/admin/TenantsPage.vue'),
      meta: { requiresAuth: true, roles: ['SUPERADMIN'] },
    },
    {
      path: '/forbidden',
      name: 'forbidden',
      component: () => import('@/pages/ForbiddenPage.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/pages/NotFoundPage.vue'),
    },
  ],
})

router.beforeEach((to: RouteLocationNormalized) => {
  const auth = useAuthStore()
  auth.restoreSession()

  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.name === 'login' && auth.isAuthenticated) {
    return auth.isSuperAdmin ? { name: 'admin-tenants' } : { name: 'dashboard' }
  }

  if (to.meta.roles?.length && auth.role) {
    if (!to.meta.roles.includes(auth.role)) {
      return { name: 'forbidden' }
    }
  }
})

export default router
