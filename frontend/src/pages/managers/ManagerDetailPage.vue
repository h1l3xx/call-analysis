<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import {
  ArrowLeft, CalendarDays, Phone, Building, Star,
  CheckCircle2, AlertTriangle, VolumeX, Clock, ChevronRight,
  Download, Sparkles, Loader2, TrendingUp, TrendingDown, Minus,
} from 'lucide-vue-next'
import { managersApi, callsApi, promptTemplatesApi } from '@/api'
import type { ManagerResponse, CallResponse, PromptTemplateResponse } from '@/types'
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

const periodLabel = computed(() => {
  if (showCustom.value) {
    const from = customFrom.value ? new Date(customFrom.value).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' }) : '?'
    const to   = customTo.value   ? new Date(customTo.value).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' }) : '?'
    return `${from} — ${to}`
  }
  switch (preset.value) {
    case 'today': return 'сегодня'
    case '7d':    return 'последние 7 дней'
    case '30d':   return 'последние 30 дней'
    default:      return 'всё время'
  }
})

// True if latestEvaluation covers roughly the same window as the current period
const evaluationMatchesPeriod = computed(() => {
  if (!latestEvaluation.value) return false
  const ev = latestEvaluation.value
  const TOLERANCE = 2 * 86400_000 // 2 days
  const pSince = periodMs.value.since
  const pUntil = periodMs.value.until
  const evFrom = ev.periodFrom
  const evTo   = ev.periodTo

  const fromOk = pSince == null && evFrom == null
    || (pSince != null && evFrom != null && Math.abs(pSince - evFrom) < TOLERANCE)
  const toOk   = pUntil == null && evTo == null
    || (pUntil != null && evTo != null && Math.abs(pUntil - evTo) < TOLERANCE)
  return fromOk && toOk
})

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

// ── Export ────────────────────────────────────────────────────────────────────
const exporting = ref(false)

async function exportCalls() {
  exporting.value = true
  try {
    await callsApi.exportCsv({
      managerIds: managerId.value,
      sinceMs: periodMs.value.since,
      untilMs: periodMs.value.until,
    })
  } finally {
    exporting.value = false
  }
}

// ── Period evaluation (manager assessment) ────────────────────────────────────
import type { ManagerEvaluationResponse } from '@/types'

const evaluating = ref(false)
const latestEvaluation = ref<ManagerEvaluationResponse | null>(null)
const evaluationsLoading = ref(false)

// Evaluation templates
const evalTemplates = ref<PromptTemplateResponse[]>([])
const selectedTemplateId = ref<string>('')

async function fetchEvalTemplates() {
  try {
    const { data } = await promptTemplatesApi.list('manager_evaluation')
    evalTemplates.value = data
    if (data.length > 0 && !selectedTemplateId.value)
      selectedTemplateId.value = data[0].id
  } catch { /* ignore */ }
}

interface Assessment {
  summary_text?: string
  strengths?: string[]
  weaknesses?: string[]
  top_recommendations?: string[]
  performance_level?: 'high' | 'medium' | 'low'
}

function parseAssessment(raw: string | null): Assessment | null {
  if (!raw) return null
  try { return JSON.parse(raw) } catch { return null }
}

function performanceLabel(level?: string) {
  if (level === 'high') return { text: 'Высокий', cls: 'bg-green-100 text-green-700' }
  if (level === 'low')  return { text: 'Низкий',  cls: 'bg-red-100 text-red-700' }
  return { text: 'Средний', cls: 'bg-yellow-100 text-yellow-700' }
}

async function fetchLatestEvaluation() {
  evaluationsLoading.value = true
  try {
    const { data } = await managersApi.listEvaluations(managerId.value)
    latestEvaluation.value = data[0] ?? null
  } catch { /* ignore */ } finally {
    evaluationsLoading.value = false
  }
}

async function generateEvaluation() {
  evaluating.value = true
  try {
    const { data } = await managersApi.evaluate(managerId.value, {
      ...periodMs.value,
      templateId: selectedTemplateId.value || undefined,
    })
    latestEvaluation.value = data
  } catch (e: any) {
    alert(e?.response?.data?.error ?? 'Ошибка при формировании отчёта')
  } finally {
    evaluating.value = false
  }
}

onMounted(() => { fetchManager(); fetchStats(); fetchCalls(); fetchLatestEvaluation(); fetchEvalTemplates() })
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

    <!-- Period selector + actions -->
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

      <div class="flex items-center gap-2 ml-auto">
        <button
          :disabled="exporting"
          class="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg border border-gray-200 bg-white text-gray-600 hover:bg-gray-50 disabled:opacity-50 transition-colors"
          @click="exportCalls"
          :title="`Выгрузить CSV за: ${periodLabel}`"
        >
          <Loader2 v-if="exporting" class="w-4 h-4 animate-spin" />
          <Download v-else class="w-4 h-4" />
          CSV
        </button>
        <div class="flex items-center gap-2">
          <select
            v-if="evalTemplates.length > 1"
            v-model="selectedTemplateId"
            class="text-sm border border-gray-200 rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-violet-300 bg-white text-gray-700"
            title="Шаблон оценки"
          >
            <option v-for="tpl in evalTemplates" :key="tpl.id" :value="tpl.id">{{ tpl.name }}</option>
          </select>
          <button
            :disabled="evaluating"
            class="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg border transition-colors disabled:opacity-50"
            :class="evaluationMatchesPeriod
              ? 'border-gray-200 bg-white text-gray-600 hover:bg-gray-50'
              : 'border-primary-200 bg-primary-50 text-primary-700 hover:bg-primary-100'"
            @click="generateEvaluation"
            :title="`Сформировать LLM-отчёт за: ${periodLabel}`"
          >
            <Loader2 v-if="evaluating" class="w-4 h-4 animate-spin" />
            <Sparkles v-else class="w-4 h-4" />
            {{ evaluationMatchesPeriod ? 'Обновить отчёт' : 'Сформировать отчёт' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Custom dates — right below the period row -->
    <div v-if="showCustom" class="flex items-center gap-3 flex-wrap bg-white border border-gray-200 rounded-xl px-4 py-3">
      <span class="text-sm text-gray-500">С</span>
      <input type="date" v-model="customFrom"
        class="border border-gray-200 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-300" />
      <span class="text-sm text-gray-500">по</span>
      <input type="date" v-model="customTo"
        class="border border-gray-200 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-300" />
    </div>

    <!-- Period evaluation report -->
    <div v-if="evaluating" class="bg-white rounded-xl border border-primary-200 p-5 flex items-center gap-3">
      <Loader2 class="w-5 h-5 animate-spin text-primary-500 shrink-0" />
      <div>
        <p class="text-sm font-medium text-gray-800">Формируется отчёт...</p>
        <p class="text-xs text-gray-400 mt-0.5">LLM анализирует {{ periodLabel }}</p>
      </div>
    </div>

    <template v-else-if="latestEvaluation">
      <!-- Stale warning -->
      <div v-if="!evaluationMatchesPeriod"
        class="flex items-center gap-2.5 px-4 py-2.5 bg-amber-50 border border-amber-200 rounded-xl text-sm text-amber-700">
        <Sparkles class="w-4 h-4 shrink-0" />
        Отчёт сформирован за другой период.
        Нажмите <strong>"Сформировать отчёт"</strong> чтобы получить данные за <em>{{ periodLabel }}</em>.
      </div>

      <div class="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <div class="px-5 py-3.5 border-b border-gray-100 flex items-center gap-3 flex-wrap">
          <Sparkles class="w-4 h-4 text-primary-500 shrink-0" />
          <h3 class="text-base font-semibold text-gray-900">Итоговый отчёт</h3>

          <!-- Period badge -->
          <span class="px-2 py-0.5 bg-gray-100 text-gray-600 rounded-full text-xs">
            {{ latestEvaluation.periodFrom
                ? new Date(latestEvaluation.periodFrom).toLocaleDateString('ru-RU', {day:'numeric',month:'short'})
                : '∞' }}
            —
            {{ latestEvaluation.periodTo
                ? new Date(latestEvaluation.periodTo).toLocaleDateString('ru-RU', {day:'numeric',month:'short'})
                : 'сейчас' }}
          </span>

          <span class="text-xs text-gray-400 ml-auto">{{ formatDate(latestEvaluation.createdAt) }} · {{ latestEvaluation.callCount }} зв.</span>

          <span v-if="latestEvaluation.avgScore != null"
            class="text-sm font-semibold"
            :class="(latestEvaluation.avgScore ?? 0) >= 70 ? 'text-green-600' : (latestEvaluation.avgScore ?? 0) >= 50 ? 'text-yellow-600' : 'text-red-500'">
            ср. {{ Math.round(latestEvaluation.avgScore) }}
          </span>
          <span v-if="parseAssessment(latestEvaluation.assessment)?.performance_level"
            class="px-2 py-0.5 rounded-full text-xs font-medium"
            :class="performanceLabel(parseAssessment(latestEvaluation.assessment)?.performance_level).cls">
            {{ performanceLabel(parseAssessment(latestEvaluation.assessment)?.performance_level).text }}
          </span>
        </div>

        <div class="p-5 space-y-4">
          <p v-if="parseAssessment(latestEvaluation.assessment)?.summary_text"
            class="text-sm text-gray-700 leading-relaxed">
            {{ parseAssessment(latestEvaluation.assessment)?.summary_text }}
          </p>

          <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div v-if="parseAssessment(latestEvaluation.assessment)?.strengths?.length">
              <div class="flex items-center gap-1.5 mb-2 text-green-700 text-xs font-semibold uppercase tracking-wide">
                <TrendingUp class="w-3.5 h-3.5" /> Сильные стороны
              </div>
              <ul class="space-y-1">
                <li v-for="s in parseAssessment(latestEvaluation.assessment)?.strengths" :key="s"
                  class="text-sm text-gray-700 flex gap-2">
                  <span class="text-green-500 shrink-0">✓</span>{{ s }}
                </li>
              </ul>
            </div>

            <div v-if="parseAssessment(latestEvaluation.assessment)?.weaknesses?.length">
              <div class="flex items-center gap-1.5 mb-2 text-red-600 text-xs font-semibold uppercase tracking-wide">
                <TrendingDown class="w-3.5 h-3.5" /> Зоны роста
              </div>
              <ul class="space-y-1">
                <li v-for="w in parseAssessment(latestEvaluation.assessment)?.weaknesses" :key="w"
                  class="text-sm text-gray-700 flex gap-2">
                  <span class="text-red-400 shrink-0">✗</span>{{ w }}
                </li>
              </ul>
            </div>

            <div v-if="parseAssessment(latestEvaluation.assessment)?.top_recommendations?.length">
              <div class="flex items-center gap-1.5 mb-2 text-primary-700 text-xs font-semibold uppercase tracking-wide">
                <Minus class="w-3.5 h-3.5" /> Рекомендации
              </div>
              <ul class="space-y-1">
                <li v-for="r in parseAssessment(latestEvaluation.assessment)?.top_recommendations" :key="r"
                  class="text-sm text-gray-700 flex gap-2">
                  <span class="text-primary-400 shrink-0">→</span>{{ r }}
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- No evaluation yet -->
    <div v-else-if="!evaluationsLoading"
      class="flex items-center gap-3 px-5 py-4 bg-gray-50 border border-dashed border-gray-200 rounded-xl text-sm text-gray-400">
      <Sparkles class="w-4 h-4 shrink-0" />
      Отчёт ещё не формировался. Нажмите <strong class="text-primary-600 ml-1">"Сформировать отчёт"</strong> чтобы получить итоговую оценку за {{ periodLabel }}.
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
