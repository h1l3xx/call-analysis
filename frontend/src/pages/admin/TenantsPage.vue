<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Plus, ChevronLeft, ChevronRight, Eye } from 'lucide-vue-next'
import { adminApi } from '@/api'
import type { TenantResponse, TenantUsageResponse } from '@/types'
import { useFormatters } from '@/composables/useFormatters'
import CreateTenantModal from '@/components/admin/CreateTenantModal.vue'
import UsageCard from '@/components/admin/UsageCard.vue'

const { formatDate } = useFormatters()

const tenants = ref<TenantResponse[]>([])
const loading = ref(true)
const page = ref(1)
const totalPages = ref(1)
const showCreate = ref(false)

const selectedUsage = ref<TenantUsageResponse | null>(null)
const usageLoading = ref(false)

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

async function viewUsage(tenantId: string) {
  usageLoading.value = true
  selectedUsage.value = null
  try {
    const { data } = await adminApi.getTenantUsage(tenantId)
    selectedUsage.value = data
  } catch {
    // handled by interceptor
  } finally {
    usageLoading.value = false
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

    <UsageCard v-if="selectedUsage" :usage="selectedUsage" />

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
            <th class="text-left px-5 py-3 font-medium text-gray-600"></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-if="!tenants.length">
            <td colspan="6" class="px-5 py-12 text-center text-gray-400">Нет тенантов</td>
          </tr>
          <tr v-for="t in tenants" :key="t.id" class="hover:bg-gray-50 transition-colors">
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
            <td class="px-5 py-3">
              <button
                class="text-primary-600 hover:text-primary-700 text-xs font-medium"
                @click="viewUsage(t.id)"
              >
                <Eye class="w-4 h-4" />
              </button>
            </td>
          </tr>
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
