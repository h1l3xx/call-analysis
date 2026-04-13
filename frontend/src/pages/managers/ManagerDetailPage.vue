<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import {
  ArrowLeft, CalendarDays, Phone, Building, Star,
  CheckCircle2, AlertTriangle, VolumeX, Clock, ChevronRight,
} from 'lucide-vue-next'
import { managersApi, callsApi } from '@/api'
import type { ManagerResponse, CallResponse } from '@/types'
// CallResponse.source used as display label; overallScore available only in CallDetailResponse
import { useFormatters } from '@/composables/useFormatters'
import CallStatusBadge from '@/components/calls/CallStatusBadge.vue'

const route = useRoute()
const router = useRouter()
const { formatDate, formatDuration } = useFormatters()

const managerId = computed(() => route.params.id as string)

// ── Manager info ──────────────────────────────────────────────────────────────
const manager = ref<ManagerResponse | null>(null)
const managerLoading = ref(true)

async function fetchManager() {
  managerLoading.value = true
  try {
    const { data } = await managersApi.get(managerId.value)
    manager.value = data
  } finally {
    managerLoading.value = false
  }
}

// ── Period picker ─────────────────────────────────────────────────────────────
type Preset = 'today' | '7d' | '30d' | 'all'
const preset = ref<Preset>('30d')
const customFrom = ref('')
const customTo = ref('')
const showCustom = ref(false)

const presets: { key: Preset; label: string }[] = [
  { key: 'today', label: 'Сегодня' },
  { key: '7d',   label: '7 дней' },
  { key: '30d',  label: '30 дней' },
  { key: 'all',  label: 'Всё время' },
]

function startOfDay(date: Date) {
  const d = new Date(date); d.setHours(0, 0, 0, 0); return d.getTime()
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

function selectPreset(p: Preset) { preset.value = p; showCustom.value = false }

// ── Stats ─────────────────────────────────────────────────────────────────────
const stats = ref({ total: 0, processing: 0, done: 0, failed: 0, noSpeech: 0, avgScore: 0 })
const statsLoading = ref(true)

async function fetchStats() {
  statsLoading.value = true
  try {
    const { data } = await callsApi.stats({ ...periodMs.value, managerId: managerId.value })
    stats.value = data
  } finally {
    statsLoading.value = false
  }
}

// ── Calls list ────────────────────────────────────────────────────────────────
const calls = ref<CallResponse[]>([])
const callsLoading = ref(true)
const page = ref(1)
const totalPages = ref(1)

async function fetchCalls() {
  callsLoading.value = true
  try {
    const { data } = await callsApi.list({
      managerId: managerId.value,
      page: page.value,
      pageSize: 15,
    })
    calls.value = data.items
    totalPages.value = data.totalPages
  } finally {
    callsLoading.value = false
  }
}

onMounted(() => { fetchManager(); fetchStats(); fetchCalls() })
watch(periodMs, fetchStats)

function callLabel(c: CallResponse): string {
  const parts: string[] = []
  if (c.managerName) parts.push(c.managerName)
  if (c.secondManagerName) parts.push(c.secondManagerName)
  else if (c.participantNames?.length) parts.push(...c.participantNames.slice(0, 2))
  if (parts.length >= 2) return parts.join(' → ')
  if (parts.length === 1) {
    const dir = c.callDirection
    if (dir === 'internal_incoming' || dir === 'external_incoming') return `← ${parts[0]}`
    if (dir === 'internal_outgoing' || dir === 'external_outgoing') return `→ ${parts[0]}`
    return parts[0]
  }
  return c.id.slice(0, 8)
}

function formatPhone(p: string) {
  if (p.length === 11 && p.startsWith('7'))
    return `+7 (${p.slice(1,4)}) ${p.slice(4,7)}-${p.slice(7,9)}-${p.slice(9)}`
  return p
}
</script>

<template>
  <div class="space-y-6">
    <!-- Back + header -->
    <div class="flex items-start gap-4">
      <button @click="router.back()"
        class="mt-1 p-2 rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 transition-colors shrink-0">
        <ArrowLeft class="w-4 h-4" />
      </button>
      <div v-if="managerLoading" class="animate-pulse">
        <div class="h-7 bg-gray-200 rounded w-48 mb-2" />
        <div class="h-4 bg-gray-100 rounded w-32" />
      </div>
      <div v-else-if="manager">
        <h1 class="text-2xl font-bold text-gray-900">{{ manager.fullName }}</h1>
        <div class="flex flex-wrap items-center gap-3 mt-1 text-sm text-gray-500">
          <span v-if="manager.departmentName" class="flex items-center gap-1">
            <Building class="w-3.5 h-3.5" /> {{ manager.departmentName }}
          </span>
          <span v-if="manager.extension" class="flex items-center gap-1">
            <Phone class="w-3.5 h-3.5" /> доб. {{ manager.extension }}
          </span>
          <span
            :class="['px-2 py-0.5 rounded-full text-xs font-medium', manager.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500']">
            {{ manager.isActive ? 'Активен' : 'Неактивен' }}
          </span>
        </div>
        <div v-if="manager.phoneNumbers?.length" class="flex flex-wrap gap-1.5 mt-2">
          <span v-for="p in manager.phoneNumbers" :key="p.id"
            class="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full"
            :class="p.isPrimary ? 'bg-blue-50 text-blue-700' : 'bg-gray-100 text-gray-600'">
            <Star v-if="p.isPrimary" class="w-2.5 h-2.5" />
            {{ formatPhone(p.phoneNumber) }}
            <span v-if="p.label" class="text-gray-400">· {{ p.label }}</span>
          </span>
        </div>
      </div>
    </div>

    <!-- Period selector -->
    <div class="flex flex-wrap items-center gap-2">
      <div class="flex bg-gray-100 rounded-lg p-0.5 gap-0.5">
        <button v-for="p in presets" :key="p.key" @click="selectPreset(p.key)"
          :class="['px-3 py-1.5 text-sm rounded-md transition-colors',
            preset === p.key && !showCustom
              ? 'bg-white shadow-sm text-gray-900 font-medium'
              : 'text-gray-500 hover:text-gray-700']">
          {{ p.label }}
        </button>
      </div>
      <button @click="showCustom = !showCustom; if (showCustom) preset = 'all'"
        :class="['flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg border transition-colors',
          showCustom ? 'border-primary-400 bg-primary-50 text-primary-700' : 'border-gray-200 bg-white text-gray-500 hover:text-gray-700']">
        <CalendarDays class="w-4 h-4" /> Период
      </button>
    </div>

    <!-- Custom dates -->
    <div v-if="showCustom" class="flex items-center gap-3 flex-wrap bg-white border border-gray-200 rounded-xl px-4 py-3">
      <span class="text-sm text-gray-500">С</span>
      <input type="date" v-model="customFrom"
        class="border border-gray-200 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-300" />
      <span class="text-sm text-gray-500">по</span>
      <input type="date" v-model="customTo"
        class="border border-gray-200 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-300" />
    </div>

    <!-- Stats cards -->
    <div v-if="statsLoading" class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4">
      <div v-for="i in 5" :key="i" class="bg-white rounded-xl border border-gray-200 p-5 animate-pulse">
        <div class="h-8 bg-gray-100 rounded w-16 mb-2" /><div class="h-4 bg-gray-100 rounded w-24" />
      </div>
    </div>
    <div v-else class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4">
      <div class="bg-white rounded-xl border border-gray-200 p-5">
        <div class="flex items-center gap-3">
          <div class="p-2.5 rounded-lg bg-primary-50"><Phone class="w-5 h-5 text-primary-600" /></div>
          <div><p class="text-2xl font-bold text-gray-900">{{ stats.total }}</p><p class="text-sm text-gray-500">Всего звонков</p></div>
        </div>
      </div>
      <div class="bg-white rounded-xl border border-gray-200 p-5">
        <div class="flex items-center gap-3">
          <div class="p-2.5 rounded-lg" :class="stats.avgScore >= 70 ? 'bg-green-50' : stats.avgScore >= 50 ? 'bg-yellow-50' : 'bg-gray-50'">
            <Star class="w-5 h-5" :class="stats.avgScore >= 70 ? 'text-green-600' : stats.avgScore >= 50 ? 'text-yellow-600' : 'text-gray-400'" />
          </div>
          <div>
            <p class="text-2xl font-bold" :class="stats.avgScore >= 70 ? 'text-green-600' : stats.avgScore >= 50 ? 'text-yellow-600' : 'text-gray-900'">
              {{ stats.avgScore ? Math.round(stats.avgScore) : '—' }}
            </p>
            <p class="text-sm text-gray-500">Средний балл</p>
          </div>
        </div>
      </div>
      <div class="bg-white rounded-xl border border-gray-200 p-5">
        <div class="flex items-center gap-3">
          <div class="p-2.5 rounded-lg bg-green-50"><CheckCircle2 class="w-5 h-5 text-success-500" /></div>
          <div><p class="text-2xl font-bold text-gray-900">{{ stats.done }}</p><p class="text-sm text-gray-500">Завершено</p></div>
        </div>
      </div>
      <div class="bg-white rounded-xl border border-gray-200 p-5">
        <div class="flex items-center gap-3">
          <div class="p-2.5 rounded-lg bg-gray-100"><VolumeX class="w-5 h-5 text-gray-400" /></div>
          <div><p class="text-2xl font-bold text-gray-900">{{ stats.noSpeech }}</p><p class="text-sm text-gray-500">Без речи</p></div>
        </div>
      </div>
      <div class="bg-white rounded-xl border border-gray-200 p-5">
        <div class="flex items-center gap-3">
          <div class="p-2.5 rounded-lg" :class="stats.failed ? 'bg-red-50' : 'bg-gray-50'">
            <AlertTriangle class="w-5 h-5" :class="stats.failed ? 'text-danger-500' : 'text-gray-400'" />
          </div>
          <div><p class="text-2xl font-bold text-gray-900">{{ stats.failed }}</p><p class="text-sm text-gray-500">Ошибки</p></div>
        </div>
      </div>
    </div>

    <!-- Calls list -->
    <div class="bg-white rounded-xl border border-gray-200">
      <div class="px-5 py-4 border-b border-gray-200 flex items-center justify-between">
        <h3 class="text-base font-semibold text-gray-900">Звонки</h3>
        <RouterLink :to="`/calls?managerId=${managerId}`"
          class="text-sm text-primary-600 hover:text-primary-700 flex items-center gap-1">
          Все <ChevronRight class="w-4 h-4" />
        </RouterLink>
      </div>

      <div v-if="callsLoading" class="p-8 text-center text-gray-400">Загрузка...</div>
      <div v-else-if="!calls.length" class="p-8 text-center text-gray-400">Нет звонков</div>
      <div v-else>
        <div class="divide-y divide-gray-100">
          <RouterLink v-for="c in calls" :key="c.id" :to="`/calls/${c.id}`"
            class="flex items-center gap-4 px-5 py-3 hover:bg-gray-50 transition-colors">
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-0.5">
                <span class="text-xs text-gray-400">{{ formatDate(c.createdAt) }}</span>
                <span v-if="c.callType" class="text-[10px] px-1.5 py-0.5 rounded font-medium"
                  :class="c.callType === 'internal' ? 'bg-blue-50 text-blue-600' : 'bg-purple-50 text-purple-600'">
                  {{ c.callType === 'internal' ? 'Внутренний' : 'Внешний' }}
                </span>
              </div>
              <p class="text-sm text-gray-700 truncate">
                {{ callLabel(c) }}
              </p>
            </div>
            <div class="flex items-center gap-3 shrink-0">
              <span v-if="c.durationSeconds" class="text-xs text-gray-400 flex items-center gap-1">
                <Clock class="w-3 h-3" /> {{ formatDuration(c.durationSeconds) }}
              </span>
              <CallStatusBadge :status="c.status" />
            </div>
          </RouterLink>
        </div>

        <!-- Pagination -->
        <div v-if="totalPages > 1" class="flex items-center justify-center gap-2 px-5 py-3 border-t border-gray-100">
          <button :disabled="page <= 1" @click="page--; fetchCalls()"
            class="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50 disabled:opacity-40">‹</button>
          <span class="text-sm text-gray-500">{{ page }} / {{ totalPages }}</span>
          <button :disabled="page >= totalPages" @click="page++; fetchCalls()"
            class="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50 disabled:opacity-40">›</button>
        </div>
      </div>
    </div>
  </div>
</template>
