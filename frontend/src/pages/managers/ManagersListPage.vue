<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { managersApi } from '@/api'
import type { ManagerResponse } from '@/types'
import { useAuthStore } from '@/stores/auth'
import { useFormatters } from '@/composables/useFormatters'

const auth = useAuthStore()
const { formatDate } = useFormatters()

const managers = ref<ManagerResponse[]>([])
const loading = ref(true)
const page = ref(1)
const totalPages = ref(1)

async function fetchManagers() {
  loading.value = true
  try {
    const { data } = await managersApi.list({ page: page.value, pageSize: 20 })
    if (Array.isArray(data)) {
      managers.value = data
      totalPages.value = 1
    } else {
      managers.value = data.items
      totalPages.value = data.totalPages
    }
  } finally {
    loading.value = false
  }
}

onMounted(fetchManagers)
</script>

<template>
  <div class="space-y-5">
    <h1 class="text-2xl font-bold text-gray-900">
      {{ auth.isManager ? 'Мой профиль' : 'Менеджеры' }}
    </h1>

    <div class="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <div v-if="loading" class="p-12 text-center text-gray-400">Загрузка...</div>
      <table v-else class="w-full text-sm">
        <thead class="bg-gray-50 border-b border-gray-200">
          <tr>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Имя</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Email</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Отдел</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Телефон</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Доб.</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Статус</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Дата создания</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-if="!managers.length">
            <td colspan="7" class="px-5 py-12 text-center text-gray-400">Нет менеджеров</td>
          </tr>
          <tr v-for="m in managers" :key="m.id" class="hover:bg-gray-50 transition-colors">
            <td class="px-5 py-3 font-medium text-gray-900">{{ m.fullName }}</td>
            <td class="px-5 py-3 text-gray-600">{{ m.email }}</td>
            <td class="px-5 py-3 text-gray-600">{{ m.departmentName || '—' }}</td>
            <td class="px-5 py-3 text-gray-600 font-mono text-xs">{{ m.phoneNumber || '—' }}</td>
            <td class="px-5 py-3 text-gray-600">{{ m.extension || '—' }}</td>
            <td class="px-5 py-3">
              <span
                :class="[
                  'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium',
                  m.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600',
                ]"
              >
                {{ m.isActive ? 'Активен' : 'Неактивен' }}
              </span>
            </td>
            <td class="px-5 py-3 text-gray-500">{{ formatDate(m.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="totalPages > 1" class="flex items-center justify-center gap-2">
      <button
        :disabled="page <= 1"
        class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
        @click="page--; fetchManagers()"
      >
        <ChevronLeft class="w-4 h-4" />
      </button>
      <span class="text-sm text-gray-600">{{ page }} / {{ totalPages }}</span>
      <button
        :disabled="page >= totalPages"
        class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
        @click="page++; fetchManagers()"
      >
        <ChevronRight class="w-4 h-4" />
      </button>
    </div>
  </div>
</template>
