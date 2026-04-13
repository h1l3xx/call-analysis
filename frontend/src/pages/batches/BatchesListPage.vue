<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { Package, ChevronLeft, ChevronRight, Trash2 } from 'lucide-vue-next'
import { batchesApi } from '@/api'
import type { BatchResponse } from '@/types'
import { useFormatters } from '@/composables/useFormatters'

const { formatDate } = useFormatters()

const batches = ref<BatchResponse[]>([])
const loading = ref(true)
const page = ref(1)
const totalPages = ref(1)
const total = ref(0)
const deletingId = ref<string | null>(null)

async function fetchBatches() {
  loading.value = true
  try {
    const { data } = await batchesApi.list({ page: page.value, pageSize: 20 })
    batches.value = data.items
    totalPages.value = data.totalPages
    total.value = data.total
  } finally {
    loading.value = false
  }
}

async function deleteBatch(id: string, e: Event) {
  e.stopPropagation()
  if (!confirm('Удалить батч и все его звонки? Это действие необратимо.')) return
  deletingId.value = id
  try {
    await batchesApi.delete(id)
    await fetchBatches()
  } finally {
    deletingId.value = null
  }
}

onMounted(fetchBatches)

function statusLabel(s: string): string {
  const map: Record<string, string> = {
    uploading: 'Загрузка',
    transcribing: 'Транскрипция',
    evaluating: 'Оценка',
    summarizing: 'Суммаризация',
    done: 'Готово',
    failed: 'Ошибка',
  }
  return map[s] || s
}

function statusClass(s: string): string {
  const map: Record<string, string> = {
    uploading: 'bg-blue-100 text-blue-700',
    transcribing: 'bg-cyan-100 text-cyan-700',
    evaluating: 'bg-amber-100 text-amber-700',
    summarizing: 'bg-purple-100 text-purple-700',
    done: 'bg-green-100 text-green-700',
    failed: 'bg-red-100 text-red-700',
  }
  return map[s] || 'bg-gray-100 text-gray-700'
}

function progressPercent(b: BatchResponse): number {
  if (!b.totalCalls) return 0
  return Math.round((b.processedCalls / b.totalCalls) * 100)
}
</script>

<template>
  <div class="space-y-5">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold text-gray-900">Батчи загрузок</h1>
      <span class="text-sm text-gray-500">Всего: {{ total }}</span>
    </div>

    <div class="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <div v-if="loading" class="p-12 text-center text-gray-400">Загрузка...</div>
      <table v-else class="w-full text-sm">
        <thead class="bg-gray-50 border-b border-gray-200">
          <tr>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Статус</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Прогресс</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Звонки</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Типы</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Дата</th>
            <th class="px-5 py-3"></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-if="!batches.length">
            <td colspan="6" class="px-5 py-12 text-center text-gray-400">Нет батчей</td>
          </tr>
          <RouterLink
            v-for="batch in batches"
            :key="batch.id"
            :to="`/batches/${batch.id}`"
            custom
            v-slot="{ navigate }"
          >
            <tr class="hover:bg-gray-50 cursor-pointer transition-colors" @click="navigate">
              <td class="px-5 py-3">
                <span class="text-xs font-medium px-2 py-1 rounded" :class="statusClass(batch.status)">
                  {{ statusLabel(batch.status) }}
                </span>
              </td>
              <td class="px-5 py-3">
                <div class="flex items-center gap-2">
                  <div class="w-24 bg-gray-200 rounded-full h-1.5">
                    <div
                      class="h-1.5 rounded-full transition-all"
                      :class="batch.status === 'done' ? 'bg-green-500' : 'bg-primary-500'"
                      :style="{ width: `${progressPercent(batch)}%` }"
                    />
                  </div>
                  <span class="text-xs text-gray-500">{{ batch.processedCalls }}/{{ batch.totalCalls }}</span>
                </div>
              </td>
              <td class="px-5 py-3 text-gray-600">{{ batch.totalCalls }}</td>
              <td class="px-5 py-3">
                <div v-if="batch.callTypeStats" class="flex gap-1">
                  <span v-if="batch.callTypeStats.internal" class="text-[10px] px-1.5 py-0.5 rounded bg-blue-100 text-blue-700">
                    Вн: {{ batch.callTypeStats.internal }}
                  </span>
                  <span v-if="batch.callTypeStats.externalIncoming + batch.callTypeStats.externalOutgoing" class="text-[10px] px-1.5 py-0.5 rounded bg-purple-100 text-purple-700">
                    Вш: {{ batch.callTypeStats.externalIncoming + batch.callTypeStats.externalOutgoing }}
                  </span>
                </div>
              </td>
              <td class="px-5 py-3 text-gray-500">{{ formatDate(batch.createdAt) }}</td>
              <td class="px-3 py-3 text-right">
                <button
                  :disabled="deletingId === batch.id"
                  class="p-1.5 text-gray-300 hover:text-red-500 hover:bg-red-50 rounded transition-colors disabled:opacity-50"
                  title="Удалить батч"
                  @click="deleteBatch(batch.id, $event)"
                >
                  <Trash2 class="w-4 h-4" />
                </button>
              </td>
            </tr>
          </RouterLink>
        </tbody>
      </table>
    </div>

    <div v-if="totalPages > 1" class="flex items-center justify-center gap-2">
      <button
        :disabled="page <= 1"
        class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
        @click="page--; fetchBatches()"
      >
        <ChevronLeft class="w-4 h-4" />
      </button>
      <span class="text-sm text-gray-600">{{ page }} / {{ totalPages }}</span>
      <button
        :disabled="page >= totalPages"
        class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
        @click="page++; fetchBatches()"
      >
        <ChevronRight class="w-4 h-4" />
      </button>
    </div>
  </div>
</template>
