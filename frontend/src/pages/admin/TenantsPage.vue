<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Plus, ChevronLeft, ChevronRight, Eye, X, Users } from 'lucide-vue-next'
import { adminApi } from '@/api'
import type { TenantResponse, TenantUsageResponse, TenantUserResponse } from '@/types'
import { useFormatters } from '@/composables/useFormatters'
import CreateTenantModal from '@/components/admin/CreateTenantModal.vue'
import UsageCard from '@/components/admin/UsageCard.vue'

const { formatDate } = useFormatters()

const tenants = ref<TenantResponse[]>([])
const loading = ref(true)
const page = ref(1)
const totalPages = ref(1)
const showCreate = ref(false)

// ── Usage ──────────────────────────────────────────────────────────────────
const selectedUsage = ref<TenantUsageResponse | null>(null)
const selectedTenantId = ref<string | null>(null)
const usageLoading = ref(false)
const usageError = ref('')

async function viewUsage(tenantId: string) {
  if (selectedTenantId.value === tenantId && !usageLoading.value) {
    selectedUsage.value = null
    selectedTenantId.value = null
    usageError.value = ''
    return
  }
  usageLoading.value = true
  usageError.value = ''
  selectedTenantId.value = tenantId
  selectedUsage.value = null
  try {
    const { data } = await adminApi.getTenantUsage(tenantId)
    selectedUsage.value = data
  } catch (e: any) {
    console.error('getTenantUsage failed:', e)
    usageError.value = e.response?.data?.error || 'Не удалось загрузить данные'
  } finally {
    usageLoading.value = false
  }
}

// ── Users ──────────────────────────────────────────────────────────────────
const usersMap = ref<Record<string, TenantUserResponse[]>>({})
const usersLoadingId = ref<string | null>(null)
const expandedUsersId = ref<string | null>(null)

async function toggleUsers(tenantId: string) {
  if (expandedUsersId.value === tenantId) {
    expandedUsersId.value = null
    return
  }
  expandedUsersId.value = tenantId
  if (usersMap.value[tenantId]) return
  usersLoadingId.value = tenantId
  try {
    const { data } = await adminApi.getTenantUsers(tenantId)
    usersMap.value[tenantId] = data
  } finally {
    usersLoadingId.value = null
  }
}

function roleLabel(role: string): string {
  const map: Record<string, string> = {
    CLIENT_ADMIN: 'Администратор',
    TEAM_LEAD:    'Тим-лид',
    MANAGER:      'Менеджер',
    SUPERADMIN:   'Суперадмин',
  }
  return map[role] ?? role
}

function lastActiveLabel(ts: number | null): string {
  if (!ts) return '—'
  const diff = Date.now() - ts
  const m = Math.floor(diff / 60_000)
  if (m < 1)  return 'только что'
  if (m < 60) return `${m} мин. назад`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} ч. назад`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d} дн. назад`
  return formatDate(ts)
}

// ── Tenants list ───────────────────────────────────────────────────────────
async function fetchTenants() {
  loading.value = true
  try {
    const { data } = await adminApi.listTenants({ page: page.value, pageSize: 20 })
    tenants.value = data.items
    totalPages.value = data.totalPages
  } finally {
    loading.value = false
  }
}

onMounted(fetchTenants)
</script>

<template>
  <div class="space-y-5">
    <div class="flex items-center justify-between flex-wrap gap-3">
      <h1 class="text-2xl font-bold text-gray-900">Тенанты</h1>
      <button
        class="flex items-center gap-2 px-4 py-2 bg-primary-600 text-white text-sm font-medium rounded-lg hover:bg-primary-700 transition-colors"
        @click="showCreate = true"
      >
        <Plus class="w-4 h-4" />
        Создать тенант
      </button>
    </div>

    <div class="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <div v-if="loading" class="p-12 text-center text-gray-400">Загрузка...</div>
      <table v-else class="w-full text-sm">
        <thead class="bg-gray-50 border-b border-gray-200">
          <tr>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Название</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Slug</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Схема</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Статус</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Создан</th>
            <th class="px-5 py-3"></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-if="!tenants.length">
            <td colspan="6" class="px-5 py-12 text-center text-gray-400">Нет тенантов</td>
          </tr>
          <template v-for="t in tenants" :key="t.id">
            <tr
              :class="[
                'transition-colors hover:bg-gray-50',
                selectedTenantId === t.id ? 'bg-primary-50' : '',
              ]"
            >
              <td class="px-5 py-3 font-medium text-gray-900">{{ t.name }}</td>
              <td class="px-5 py-3 text-gray-600">{{ t.slug }}</td>
              <td class="px-5 py-3 font-mono text-xs text-gray-500">{{ t.dbSchema }}</td>
              <td class="px-5 py-3">
                <span
                  :class="[
                    'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium',
                    t.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600',
                  ]"
                >
                  {{ t.isActive ? 'Активен' : 'Неактивен' }}
                </span>
              </td>
              <td class="px-5 py-3 text-gray-500">{{ formatDate(t.createdAt) }}</td>
              <td class="px-3 py-3">
                <div class="flex items-center gap-1">
                  <!-- Пользователи -->
                  <button
                    :class="[
                      'p-1.5 rounded transition-colors',
                      expandedUsersId === t.id
                        ? 'text-indigo-700 bg-indigo-100'
                        : 'text-gray-400 hover:text-indigo-600 hover:bg-gray-100',
                    ]"
                    title="Пользователи"
                    @click="toggleUsers(t.id)"
                  >
                    <Users class="w-4 h-4" />
                  </button>
                  <!-- Использование -->
                  <button
                    :class="[
                      'p-1.5 rounded transition-colors',
                      selectedTenantId === t.id
                        ? 'text-primary-700 bg-primary-100'
                        : 'text-primary-600 hover:text-primary-700 hover:bg-gray-100',
                    ]"
                    title="Использование"
                    @click="viewUsage(t.id)"
                  >
                    <Eye v-if="selectedTenantId !== t.id" class="w-4 h-4" />
                    <X v-else class="w-4 h-4" />
                  </button>
                </div>
              </td>
            </tr>

            <!-- Строка с пользователями -->
            <tr v-if="expandedUsersId === t.id">
              <td colspan="6" class="p-0">
                <div class="px-5 py-4 bg-indigo-50/40 border-t border-indigo-100 animate-slideDown">
                  <div v-if="usersLoadingId === t.id" class="py-2 text-center text-gray-400 text-sm">Загрузка...</div>
                  <template v-else-if="usersMap[t.id]">
                    <p v-if="!usersMap[t.id].length" class="text-sm text-gray-400 py-1">Нет пользователей</p>
                    <table v-else class="w-full text-xs">
                      <thead>
                        <tr class="text-gray-500 border-b border-indigo-100">
                          <th class="text-left pb-2 pr-4 font-medium">Имя</th>
                          <th class="text-left pb-2 pr-4 font-medium">Email</th>
                          <th class="text-left pb-2 pr-4 font-medium">Роль</th>
                          <th class="text-left pb-2 pr-4 font-medium">Статус</th>
                          <th class="text-left pb-2 font-medium">Последняя активность</th>
                        </tr>
                      </thead>
                      <tbody class="divide-y divide-indigo-100/50">
                        <tr v-for="u in usersMap[t.id]" :key="u.id" class="hover:bg-indigo-50/50">
                          <td class="py-1.5 pr-4 font-medium text-gray-800">{{ u.fullName }}</td>
                          <td class="py-1.5 pr-4 text-gray-500">{{ u.email }}</td>
                          <td class="py-1.5 pr-4">
                            <span class="px-1.5 py-0.5 rounded bg-indigo-100 text-indigo-700">
                              {{ roleLabel(u.role) }}
                            </span>
                          </td>
                          <td class="py-1.5 pr-4">
                            <span :class="u.isActive ? 'text-green-600' : 'text-gray-400'">
                              {{ u.isActive ? 'Активен' : 'Неактивен' }}
                            </span>
                          </td>
                          <td class="py-1.5">
                            <span
                              :class="[
                                u.lastActiveAt ? 'text-gray-700' : 'text-gray-400',
                              ]"
                              :title="u.lastActiveAt ? formatDate(u.lastActiveAt) : undefined"
                            >
                              {{ lastActiveLabel(u.lastActiveAt) }}
                            </span>
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </template>
                </div>
              </td>
            </tr>

            <!-- Строка с использованием -->
            <tr v-if="selectedTenantId === t.id">
              <td colspan="6" class="p-0">
                <div class="px-5 py-4 bg-gray-50 border-t border-gray-100 animate-slideDown">
                  <div v-if="usageLoading" class="py-3 text-center text-gray-400 text-sm">Загрузка...</div>
                  <div v-else-if="usageError" class="py-3 text-center text-red-500 text-sm">{{ usageError }}</div>
                  <UsageCard v-else-if="selectedUsage" :usage="selectedUsage" />
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <div v-if="totalPages > 1" class="flex items-center justify-center gap-2">
      <button
        :disabled="page <= 1"
        class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
        @click="page--; fetchTenants()"
      >
        <ChevronLeft class="w-4 h-4" />
      </button>
      <span class="text-sm text-gray-600">{{ page }} / {{ totalPages }}</span>
      <button
        :disabled="page >= totalPages"
        class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
        @click="page++; fetchTenants()"
      >
        <ChevronRight class="w-4 h-4" />
      </button>
    </div>

    <CreateTenantModal
      v-if="showCreate"
      @close="showCreate = false"
      @created="fetchTenants"
    />
  </div>
</template>

<style scoped>
.animate-slideDown {
  animation: slideDown 0.2s ease-out;
}
@keyframes slideDown {
  from { opacity: 0; max-height: 0; }
  to   { opacity: 1; max-height: 400px; }
}
</style>
