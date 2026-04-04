<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { Plus, FolderUp, ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { callsApi } from '@/api'
import type { CallResponse } from '@/types'
import { useAuthStore } from '@/stores/auth'
import { useFormatters } from '@/composables/useFormatters'
import CallStatusBadge from '@/components/calls/CallStatusBadge.vue'
import CallUploadModal from '@/components/calls/CallUploadModal.vue'
import BulkUploadModal from '@/components/calls/BulkUploadModal.vue'

const auth = useAuthStore()
const { formatDate, formatDuration } = useFormatters()

const calls = ref<CallResponse[]>([])
const loading = ref(true)
const page = ref(1)
const totalPages = ref(1)
const total = ref(0)
const statusFilter = ref('')
const showUpload = ref(false)
const showBulkUpload = ref(false)

const statusOptions = [
  { value: '', label: 'Все статусы' },
  { value: 'queued', label: 'В очереди' },
  { value: 'processing', label: 'Обработка' },
  { value: 'transcribed_only', label: 'Транскрибирован' },
  { value: 'analyzing', label: 'Анализ' },
  { value: 'done', label: 'Готово' },
  { value: 'failed', label: 'Ошибка' },
]

function participantLabel(name: string | null, names: string[] | null | undefined): string {
  if (names && names.length > 1) return names.join(' / ')
  return name || '—'
}

function callTypeLabel(ct: string | null): string {
  if (ct === 'internal') return 'Вн'
  if (ct === 'external') return 'Вш'
  return ''
}

function callTypeBadgeClass(ct: string | null): string {
  if (ct === 'internal') return 'bg-blue-100 text-blue-700'
  if (ct === 'external') return 'bg-purple-100 text-purple-700'
  return 'bg-gray-100 text-gray-600'
}

async function fetchCalls() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: page.value, pageSize: 20 }
    if (statusFilter.value) params.status = statusFilter.value
    const { data } = await callsApi.list(params)
    calls.value = data.items
    totalPages.value = data.totalPages
    total.value = data.total
  } finally {
    loading.value = false
  }
}

onMounted(fetchCalls)
watch([page, statusFilter], () => {
  if (statusFilter.value) page.value = 1
  fetchCalls()
})

function handleUploaded() {
  page.value = 1
  fetchCalls()
}
</script>

<template>
  <div class="space-y-5">
    <div class="flex items-center justify-between flex-wrap gap-3">
      <h1 class="text-2xl font-bold text-gray-900">Звонки</h1>
      <div v-if="auth.canManage && !auth.isManager" class="flex items-center gap-2">
        <button
          v-if="auth.isClientAdmin"
          class="flex items-center gap-2 px-4 py-2 bg-amber-600 text-white text-sm font-medium rounded-lg hover:bg-amber-700 transition-colors"
          @click="showBulkUpload = true"
        >
          <FolderUp class="w-4 h-4" />
          Массовая загрузка
        </button>
        <button
          class="flex items-center gap-2 px-4 py-2 bg-primary-600 text-white text-sm font-medium rounded-lg hover:bg-primary-700 transition-colors"
          @click="showUpload = true"
        >
          <Plus class="w-4 h-4" />
          Загрузить
        </button>
      </div>
    </div>

    <div class="flex gap-3">
      <select
        v-model="statusFilter"
        class="appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
      >
        <option v-for="opt in statusOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
      </select>
      <span class="flex items-center text-sm text-gray-500">Всего: {{ total }}</span>
    </div>

    <div class="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <div v-if="loading" class="p-12 text-center text-gray-400">Загрузка...</div>
      <table v-else class="w-full text-sm">
        <thead class="bg-gray-50 border-b border-gray-200">
          <tr>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Участники</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Тип</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Скрипт</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Статус</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Длительность</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Дата</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-if="!calls.length">
            <td colspan="6" class="px-5 py-12 text-center text-gray-400">Нет звонков</td>
          </tr>
          <RouterLink
            v-for="call in calls"
            :key="call.id"
            :to="`/calls/${call.id}`"
            custom
            v-slot="{ navigate }"
          >
            <tr class="hover:bg-gray-50 cursor-pointer transition-colors" @click="navigate">
              <td class="px-5 py-3 font-medium text-gray-900">
                <template v-if="call.callType === 'internal'">
                  {{ participantLabel(call.managerName, call.participantNames) }}
                  <template v-if="call.secondManagerId">
                    <span class="text-gray-400 mx-1">&harr;</span>
                    {{ participantLabel(call.secondManagerName, call.secondParticipantNames) }}
                  </template>
                </template>
                <template v-else>
                  {{ call.managerName || '—' }}
                </template>
              </td>
              <td class="px-5 py-3">
                <span
                  v-if="call.callType"
                  class="text-[10px] font-medium px-1.5 py-0.5 rounded"
                  :class="callTypeBadgeClass(call.callType)"
                >{{ callTypeLabel(call.callType) }}</span>
                <span v-else class="text-gray-400">—</span>
              </td>
              <td class="px-5 py-3 text-gray-600">{{ call.scriptName || '—' }}</td>
              <td class="px-5 py-3"><CallStatusBadge :status="call.status" /></td>
              <td class="px-5 py-3 text-gray-600">{{ formatDuration(call.durationSeconds) }}</td>
              <td class="px-5 py-3 text-gray-500">{{ formatDate(call.createdAt) }}</td>
            </tr>
          </RouterLink>
        </tbody>
      </table>
    </div>

    <div v-if="totalPages > 1" class="flex items-center justify-center gap-2">
      <button
        :disabled="page <= 1"
        class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
        @click="page--"
      >
        <ChevronLeft class="w-4 h-4" />
      </button>
      <span class="text-sm text-gray-600">{{ page }} / {{ totalPages }}</span>
      <button
        :disabled="page >= totalPages"
        class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
        @click="page++"
      >
        <ChevronRight class="w-4 h-4" />
      </button>
    </div>

    <CallUploadModal v-if="showUpload" @close="showUpload = false" @uploaded="handleUploaded" />
    <BulkUploadModal v-if="showBulkUpload" @close="showBulkUpload = false" @uploaded="handleUploaded" />
  </div>
</template>
