<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { CalendarDays, ChevronRight } from 'lucide-vue-next'
import { callsApi, batchesApi } from '@/api'
import type { BatchResponse } from '@/types'
import StatsCards from '@/components/dashboard/StatsCards.vue'
import { useFormatters } from '@/composables/useFormatters'

const { formatDate } = useFormatters()

// ── Period picker ────────────────────────────────────────────────────────────

type Preset = 'today' | '7d' | '30d' | 'all'
const preset = ref<Preset>('7d')
const customFrom = ref('')
const customTo = ref('')
const showCustom = ref(false)

const presets: { key: Preset; label: string }[] = [
  { key: 'today', label: 'Сегодня' },
  { key: '7d',   label: '7 дней' },
  { key: '30d',  label: '30 дней' },
  { key: 'all',  label: 'Всё время' },
]

function startOfDay(date: Date): number {
  const d = new Date(date)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

const periodMs = computed<{ since?: number; until?: number }>(() => {
  if (showCustom.value) {
    return {
      since: customFrom.value ? new Date(customFrom.value).getTime() : undefined,
      until: customTo.value   ? new Date(customTo.value + 'T23:59:59').getTime() : undefined,
    }
  }
  const now = Date.now()
  switch (preset.value) {
    case 'today': return { since: startOfDay(new Date()) }
    case '7d':    return { since: now - 7  * 86400_000 }
    case '30d':   return { since: now - 30 * 86400_000 }
    default:      return {}
  }
})

// ── Data ─────────────────────────────────────────────────────────────────────

const loading = ref(true)
const batchesLoading = ref(true)
const stats = ref({ total: 0, processing: 0, done: 0, failed: 0, noSpeech: 0, avgScore: 0 })
const recentBatches = ref<BatchResponse[]>([])

async function fetchStats() {
  loading.value = true
  try {
    const { data } = await callsApi.stats(periodMs.value)
    stats.value = data
  } finally {
    loading.value = false
  }
}

async function fetchBatches() {
  batchesLoading.value = true
  try {
    const { data } = await batchesApi.list({ page: 1, pageSize: 5 })
    recentBatches.value = data.items
  } finally {
    batchesLoading.value = false
  }
}

onMounted(() => { fetchStats(); fetchBatches() })
watch(periodMs, fetchStats)

function selectPreset(p: Preset) {
  preset.value = p
  showCustom.value = false
}

function batchProgress(b: BatchResponse) {
  if (!b.totalCalls) return 0
  return Math.round((b.processedCalls / b.totalCalls) * 100)
}

function batchStatusLabel(status: string) {
  const map: Record<string, string> = {
    uploading: 'Загрузка', transcribing: 'Транскрибация',
    evaluating: 'Оценка', summarizing: 'Сводка',
    done: 'Готово', failed: 'Ошибка',
  }
  return map[status] ?? status
}

function batchStatusClass(status: string) {
  if (status === 'done')   return 'bg-green-100 text-green-700'
  if (status === 'failed') return 'bg-red-100 text-red-700'
  return 'bg-yellow-100 text-yellow-700'
}
</script>

<template>
  <div class="space-y-6">
    <!-- Header + period selector -->
    <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
      <h1 class="text-2xl font-bold text-gray-900">Дашборд</h1>

      <div class="flex items-center gap-2 flex-wrap">
        <!-- Presets -->
        <div class="flex bg-gray-100 rounded-lg p-0.5 gap-0.5">
          <button
            v-for="p in presets"
            :key="p.key"
            @click="selectPreset(p.key)"
            :class="[
              'px-3 py-1.5 text-sm rounded-md transition-colors',
              preset === p.key && !showCustom
                ? 'bg-white shadow-sm text-gray-900 font-medium'
                : 'text-gray-500 hover:text-gray-700',
            ]"
          >
            {{ p.label }}
          </button>
        </div>

        <!-- Custom range toggle -->
        <button
          @click="showCustom = !showCustom; if (showCustom) preset = 'all'"
          :class="[
            'flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg border transition-colors',
            showCustom
              ? 'border-primary-400 bg-primary-50 text-primary-700'
              : 'border-gray-200 bg-white text-gray-500 hover:text-gray-700',
          ]"
        >
          <CalendarDays class="w-4 h-4" />
          Период
        </button>
      </div>
    </div>

    <!-- Custom date pickers -->
    <div v-if="showCustom" class="flex items-center gap-3 flex-wrap bg-white border border-gray-200 rounded-xl px-4 py-3">
      <span class="text-sm text-gray-500">С</span>
      <input type="date" v-model="customFrom"
        class="border border-gray-200 rounded-lg px-3 py-1.5 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-primary-300" />
      <span class="text-sm text-gray-500">по</span>
      <input type="date" v-model="customTo"
        class="border border-gray-200 rounded-lg px-3 py-1.5 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-primary-300" />
    </div>

    <!-- Stats cards -->
    <div v-if="loading" class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4">
      <div v-for="i in 5" :key="i" class="bg-white rounded-xl border border-gray-200 p-5 animate-pulse">
        <div class="h-8 bg-gray-100 rounded w-16 mb-2" />
        <div class="h-4 bg-gray-100 rounded w-24" />
      </div>
    </div>
    <StatsCards v-else v-bind="stats" />

    <!-- Recent batches -->
    <div class="bg-white rounded-xl border border-gray-200">
      <div class="px-5 py-4 border-b border-gray-200 flex items-center justify-between">
        <h3 class="text-base font-semibold text-gray-900">Последние батчи</h3>
        <RouterLink to="/batches" class="text-sm text-primary-600 hover:text-primary-700 flex items-center gap-1">
          Все батчи <ChevronRight class="w-4 h-4" />
        </RouterLink>
      </div>

      <div v-if="batchesLoading" class="p-8 text-center text-gray-400">Загрузка...</div>
      <div v-else-if="!recentBatches.length" class="p-8 text-center text-gray-400">Нет батчей</div>
      <div v-else class="divide-y divide-gray-100">
        <RouterLink
          v-for="b in recentBatches"
          :key="b.id"
          :to="`/batches/${b.id}`"
          class="flex items-center gap-4 px-5 py-3.5 hover:bg-gray-50 transition-colors"
        >
          <!-- Progress bar -->
          <div class="flex-1 min-w-0">
            <div class="flex items-center justify-between mb-1">
              <span class="text-sm font-medium text-gray-900">
                {{ formatDate(b.createdAt) }}
              </span>
              <span :class="['text-xs px-2 py-0.5 rounded-full font-medium', batchStatusClass(b.status)]">
                {{ batchStatusLabel(b.status) }}
              </span>
            </div>
            <div class="flex items-center gap-2">
              <div class="flex-1 bg-gray-100 rounded-full h-1.5">
                <div
                  class="h-1.5 rounded-full transition-all"
                  :class="b.status === 'failed' ? 'bg-red-400' : b.status === 'done' ? 'bg-green-500' : 'bg-primary-500'"
                  :style="{ width: batchProgress(b) + '%' }"
                />
              </div>
              <span class="text-xs text-gray-500 shrink-0">
                {{ b.processedCalls }}/{{ b.totalCalls }}
              </span>
            </div>
            <div v-if="b.callTypeStats" class="flex gap-3 mt-1 text-xs text-gray-400">
              <span v-if="b.callTypeStats.internal">Внутренние: {{ b.callTypeStats.internal }}</span>
              <span v-if="b.callTypeStats.externalIncoming + b.callTypeStats.externalOutgoing">
                Внешние: {{ b.callTypeStats.externalIncoming + b.callTypeStats.externalOutgoing }}
              </span>
            </div>
          </div>
          <ChevronRight class="w-4 h-4 text-gray-400 shrink-0" />
        </RouterLink>
      </div>
    </div>
  </div>
</template>
