<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { Plus, ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { scriptsApi } from '@/api'
import type { ScriptResponse } from '@/types'
import { useAuthStore } from '@/stores/auth'
import { useFormatters } from '@/composables/useFormatters'

const auth = useAuthStore()
const router = useRouter()
const { formatDate } = useFormatters()

const scripts = ref<ScriptResponse[]>([])
const loading = ref(true)
const page = ref(1)
const totalPages = ref(1)
const activeFilter = ref<'' | 'true' | 'false'>('')

async function fetchScripts() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: page.value, pageSize: 20 }
    if (activeFilter.value) params.isActive = activeFilter.value === 'true'
    const { data } = await scriptsApi.list(params)
    scripts.value = data.items
    totalPages.value = data.totalPages
  } finally {
    loading.value = false
  }
}

onMounted(fetchScripts)
watch([page, activeFilter], fetchScripts)

async function handleCreate() {
  try {
    const { data } = await scriptsApi.create({
      name: 'Новый скрипт',
      callType: 'default',
      criteria: [],
    })
    router.push(`/scripts/${data.id}`)
  } catch {
    // handled by interceptor
  }
}
</script>

<template>
  <div class="space-y-5">
    <div class="flex items-center justify-between flex-wrap gap-3">
      <h1 class="text-2xl font-bold text-gray-900">Скрипты оценки</h1>
      <button
        v-if="auth.isClientAdmin"
        class="flex items-center gap-2 px-4 py-2 bg-primary-600 text-white text-sm font-medium rounded-lg hover:bg-primary-700 transition-colors"
        @click="handleCreate"
      >
        <Plus class="w-4 h-4" />
        Создать
      </button>
    </div>

    <div class="flex gap-3">
      <select
        v-model="activeFilter"
        class="bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 outline-none"
      >
        <option value="">Все</option>
        <option value="true">Активные</option>
        <option value="false">Неактивные</option>
      </select>
    </div>

    <div class="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <div v-if="loading" class="p-12 text-center text-gray-400">Загрузка...</div>
      <table v-else class="w-full text-sm">
        <thead class="bg-gray-50 border-b border-gray-200">
          <tr>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Название</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Тип</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Критериев</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Статус</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Обновлён</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-if="!scripts.length">
            <td colspan="5" class="px-5 py-12 text-center text-gray-400">Нет скриптов</td>
          </tr>
          <RouterLink
            v-for="s in scripts"
            :key="s.id"
            :to="`/scripts/${s.id}`"
            custom
            v-slot="{ navigate }"
          >
            <tr class="hover:bg-gray-50 cursor-pointer transition-colors" @click="navigate">
              <td class="px-5 py-3 font-medium text-gray-900">{{ s.name }}</td>
              <td class="px-5 py-3 text-gray-600">{{ s.callType }}</td>
              <td class="px-5 py-3 text-gray-600">{{ s.criteriaCount }}</td>
              <td class="px-5 py-3">
                <span
                  :class="[
                    'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium',
                    s.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600',
                  ]"
                >
                  {{ s.isActive ? 'Активен' : 'Неактивен' }}
                </span>
              </td>
              <td class="px-5 py-3 text-gray-500">{{ formatDate(s.updatedAt) }}</td>
            </tr>
          </RouterLink>
        </tbody>
      </table>
    </div>

    <div v-if="totalPages > 1" class="flex items-center justify-center gap-2">
      <button
        :disabled="page <= 1"
        class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
        @click="page--"
      >
        <ChevronLeft class="w-4 h-4" />
      </button>
      <span class="text-sm text-gray-600">{{ page }} / {{ totalPages }}</span>
      <button
        :disabled="page >= totalPages"
        class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
        @click="page++"
      >
        <ChevronRight class="w-4 h-4" />
      </button>
    </div>
  </div>
</template>
