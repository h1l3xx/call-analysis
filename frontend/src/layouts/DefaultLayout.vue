<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import {
  LayoutDashboard,
  Phone,
  FileText,
  Users,
  Building2,
  LogOut,
  Menu,
  X,
  Sun,
  Moon,
  Settings,
  Package,
  Sparkles,
  FileDown,
  SlidersHorizontal,
} from 'lucide-vue-next'
import { useDarkMode } from '@/composables/useDarkMode'

const auth = useAuthStore()
const router = useRouter()
const sidebarOpen = ref(false)
const { isDark, toggle: toggleDark } = useDarkMode()

const navItems = computed(() => {
  const items: { to: string; label: string; icon: any; roles: string[] }[] = []

  if (!auth.isSuperAdmin) {
    items.push(
      { to: '/dashboard', label: 'Дашборд', icon: LayoutDashboard, roles: ['CLIENT_ADMIN', 'TEAM_LEAD', 'MANAGER'] },
      { to: '/calls', label: 'Звонки', icon: Phone, roles: ['CLIENT_ADMIN', 'TEAM_LEAD', 'MANAGER'] },
      { to: '/batches', label: 'Батчи', icon: Package, roles: ['CLIENT_ADMIN', 'TEAM_LEAD'] },
      { to: '/scripts', label: 'Скрипты', icon: FileText, roles: ['CLIENT_ADMIN', 'TEAM_LEAD', 'MANAGER'] },
      { to: '/managers', label: 'Менеджеры', icon: Users, roles: ['CLIENT_ADMIN', 'TEAM_LEAD', 'MANAGER'] },
      { to: '/export', label: 'Выгрузка', icon: FileDown, roles: ['CLIENT_ADMIN', 'TEAM_LEAD'] },
      { to: '/settings/prompts', label: 'Оценка', icon: Sparkles, roles: ['CLIENT_ADMIN', 'TEAM_LEAD'] },
      { to: '/settings/policies', label: 'Политики', icon: SlidersHorizontal, roles: ['CLIENT_ADMIN', 'TEAM_LEAD'] },
      { to: '/settings', label: 'Настройки', icon: Settings, roles: ['CLIENT_ADMIN', 'TEAM_LEAD', 'MANAGER'] },
    )
  } else {
    items.push(
      { to: '/admin/tenants', label: 'Тенанты', icon: Building2, roles: ['SUPERADMIN'] },
    )
  }

  return items.filter((item) => !auth.role || item.roles.includes(auth.role))
})

import { computed } from 'vue'

async function handleLogout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="min-h-screen flex bg-gray-50">
    <!-- Mobile overlay -->
    <div
      v-if="sidebarOpen"
      class="fixed inset-0 z-30 bg-black/50 lg:hidden"
      @click="sidebarOpen = false"
    />

    <!-- Sidebar -->
    <aside
      :class="[
        'fixed inset-y-0 left-0 z-40 w-64 bg-white border-r border-gray-200 transform transition-transform duration-200 lg:translate-x-0 lg:static lg:z-auto',
        sidebarOpen ? 'translate-x-0' : '-translate-x-full',
      ]"
    >
      <div class="flex items-center justify-between h-16 px-6 border-b border-gray-200">
        <span class="text-xl font-bold text-primary-600">Malikov</span>
        <button class="lg:hidden text-gray-500" @click="sidebarOpen = false">
          <X class="w-5 h-5" />
        </button>
      </div>

      <nav class="mt-6 px-3 space-y-1">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-gray-700 hover:bg-primary-50 hover:text-primary-700 transition-colors"
          active-class="!bg-primary-50 !text-primary-700"
          @click="sidebarOpen = false"
        >
          <component :is="item.icon" class="w-5 h-5" />
          {{ item.label }}
        </RouterLink>
      </nav>
    </aside>

    <!-- Main content -->
    <div class="flex-1 flex flex-col min-w-0">
      <!-- Top bar -->
      <header class="h-16 bg-white border-b border-gray-200 flex items-center justify-between px-4 lg:px-6 shrink-0">
        <button class="lg:hidden text-gray-500" @click="sidebarOpen = true">
          <Menu class="w-6 h-6" />
        </button>

        <div class="ml-auto flex items-center gap-3">
          <button
            class="p-2 text-gray-400 hover:text-gray-600 rounded-lg hover:bg-gray-100 transition-colors"
            title="Переключить тему"
            @click="toggleDark"
          >
            <Moon v-if="!isDark" class="w-5 h-5" />
            <Sun v-else class="w-5 h-5" />
          </button>
          <div class="text-sm text-right">
            <div class="font-medium text-gray-900">{{ auth.user?.fullName }}</div>
            <div class="text-gray-500 text-xs">{{ auth.user?.role }}</div>
          </div>
          <button
            class="p-2 text-gray-400 hover:text-danger-500 rounded-lg hover:bg-gray-100 transition-colors"
            title="Выйти"
            @click="handleLogout"
          >
            <LogOut class="w-5 h-5" />
          </button>
        </div>
      </header>

      <!-- Page content -->
      <main class="flex-1 p-4 lg:p-6 overflow-auto">
        <slot />
      </main>
    </div>
  </div>
</template>
