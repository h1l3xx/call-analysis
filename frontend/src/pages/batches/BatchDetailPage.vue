<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { ArrowLeft, RefreshCw, Phone, Building, Download, ChevronLeft, ChevronRight, ChevronDown, Trash2 } from 'lucide-vue-next'
import { batchesApi, callsApi, managersApi } from '@/api'
import type { BatchResponse, BatchSummaryResponse, CallResponse, ManagerResponse } from '@/types'
import { useFormatters } from '@/composables/useFormatters'
import CallStatusBadge from '@/components/calls/CallStatusBadge.vue'
import BatchCallsModal from '@/components/batches/BatchCallsModal.vue'
import MultiSelect from '@/components/ui/MultiSelect.vue'
import type { SelectOption } from '@/components/ui/MultiSelect.vue'

const route = useRoute()
const router = useRouter()
const { formatDate, formatDuration, participantLabel } = useFormatters()

const batch = ref<BatchResponse | null>(null)
const summaries = ref<BatchSummaryResponse[]>([])
const calls = ref<CallResponse[]>([])
const loading = ref(true)
const callsLoading = ref(false)
const summarizing = ref(false)
const exporting = ref(false)
const deleting = ref(false)
const exportModalOpen = ref(false)
const departments = ref<{ id: string; name: string }[]>([])
const allManagers = ref<ManagerResponse[]>([])
const exportDepartment = ref('')
const exportManagers = ref<string[]>([])
const tab = ref<'all' | 'internal' | 'external'>(
  (route.query.tab as 'all' | 'internal' | 'external') || 'all'
)
const callsPage = ref(Number(route.query.page) || 1)
const callsTotalPages = ref(1)
const pageInput = ref(String(callsPage.value))

function syncQuery() {
  router.replace({
    query: {
      ...route.query,
      page: callsPage.value > 1 ? String(callsPage.value) : undefined,
      tab: tab.value !== 'all' ? tab.value : undefined,
    },
  })
}

function goToPage(n: number) {
  const clamped = Math.max(1, Math.min(n, callsTotalPages.value))
  callsPage.value = clamped
  pageInput.value = String(clamped)
  syncQuery()
  fetchCalls()
}

function onPageInputBlur() {
  const n = parseInt(pageInput.value)
  if (!isNaN(n)) goToPage(n)
  else pageInput.value = String(callsPage.value)
}

function onPageInputKey(e: KeyboardEvent) {
  if (e.key === 'Enter') (e.target as HTMLElement).blur()
}

watch(callsTotalPages, () => {
  pageInput.value = String(callsPage.value)
})
let pollInterval: ReturnType<typeof setInterval> | null = null

const managerOptions = computed<SelectOption[]>(() =>
  allManagers.value.map(m => ({
    id: m.id,
    label: m.fullName,
    sublabel: m.departmentName || undefined,
  }))
)

const batchId = computed(() => route.params.id as string)

async function fetchBatch() {
  try {
    const { data } = await batchesApi.get(batchId.value)
    batch.value = data.batch
    summaries.value = data.summaries
  } finally {
    loading.value = false
  }
}

async function fetchCalls(silent = false) {
  if (!silent) callsLoading.value = true
  try {
    const callType = tab.value === 'all' ? undefined : tab.value
    const { data } = await batchesApi.getCalls(batchId.value, {
      page: callsPage.value, pageSize: 20, callType,
    })
    calls.value = data.items
    callsTotalPages.value = data.totalPages
  } finally {
    if (!silent) callsLoading.value = false
  }
}

async function regenerateSummary() {
  if (summarizing.value) return
  summarizing.value = true
  try {
    await batchesApi.regenerateSummary(batchId.value)
    await fetchBatch()
  } finally {
    summarizing.value = false
  }
}

function openExportModal() {
  exportDepartment.value = ''
  exportManagers.value = []
  exportModalOpen.value = true
}

async function doExport() {
  exporting.value = true
  exportModalOpen.value = false
  try {
    const depId = exportDepartment.value || undefined
    const mgrIds = exportManagers.value.length ? exportManagers.value : undefined
    await batchesApi.exportCsv(batchId.value, depId, mgrIds)
  } finally {
    exporting.value = false
  }
}

async function exportAll() {
  exporting.value = true
  try {
    await batchesApi.exportCsv(batchId.value)
  } finally {
    exporting.value = false
  }
}

async function deleteBatch() {
  if (!confirm('Удалить батч и все его звонки? Это действие необратимо.')) return
  deleting.value = true
  try {
    await batchesApi.delete(batchId.value)
    router.push('/batches')
  } finally {
    deleting.value = false
  }
}

async function fetchDepartments() {
  try {
    const [depts, mgrs] = await Promise.all([
      callsApi.departments(),
      managersApi.allActive(),
    ])
    departments.value = depts.data
    allManagers.value = mgrs
  } catch { /* ignore */ }
}

function startPolling() {
  pollInterval = setInterval(async () => {
    if (batch.value && !['done', 'failed'].includes(batch.value.status)) {
      await fetchBatch()
      await fetchCalls(true)
    } else if (pollInterval) {
      clearInterval(pollInterval)
      pollInterval = null
    }
  }, 5000)
}

onMounted(async () => {
  await fetchBatch()
  await Promise.all([fetchCalls(), fetchDepartments()])
  startPolling()
})

onUnmounted(() => {
  if (pollInterval) clearInterval(pollInterval)
})

function statusLabel(s: string): string {
  const map: Record<string, string> = {
    uploading: 'Загрузка файлов',
    transcribing: 'Транскрипция звонков',
    evaluating: 'Оценка LLM',
    summarizing: 'Генерация отчёта',
    done: 'Обработка завершена',
    failed: 'Ошибка обработки',
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

function progressPercent(): number {
  if (!batch.value?.totalCalls) return 0
  return Math.round((batch.value.processedCalls / batch.value.totalCalls) * 100)
}

function callTypeLabel(ct: string | null): string {
  if (ct === 'internal') return 'Внутренний'
  if (ct === 'external') return 'Внешний'
  return 'Неизвестный'
}

function callTypeBadgeClass(ct: string | null): string {
  if (ct === 'internal') return 'bg-blue-100 text-blue-700'
  if (ct === 'external') return 'bg-purple-100 text-purple-700'
  return 'bg-gray-100 text-gray-600'
}

function parseSummaryContent(content: string | null): Record<string, any> | null {
  if (!content) return null
  try { return JSON.parse(content) } catch { return null }
}

// Числа из summary-секций (совпадают с тем, что видит пользователь в отчётах)
const summaryTypeCounts = computed(() => {
  const internal = summaries.value.find(s => s.scope === 'internal')
  const external = summaries.value.find(s => s.scope === 'external')
  const internalTotal = internal ? (parseSummaryContent(internal.content)?.total_calls ?? null) : null
  const externalTotal = external ? (parseSummaryContent(external.content)?.total_calls ?? null) : null
  return { internal: internalTotal, external: externalTotal }
})

const modalVisible = ref(false)
const modalTitle = ref('')
const modalCallIds = ref<string[]>([])

function openCallsModal(title: string, callIds: string[]) {
  if (!callIds?.length) return
  modalTitle.value = title
  modalCallIds.value = callIds
  modalVisible.value = true
}
</script>

<template>
  <div class="space-y-5">
    <div class="flex items-center gap-3">
      <RouterLink to="/batches" class="p-2 rounded-lg hover:bg-gray-100 text-gray-500">
        <ArrowLeft class="w-5 h-5" />
      </RouterLink>
      <h1 class="text-2xl font-bold text-gray-900">Батч загрузки</h1>
    </div>

    <div v-if="loading" class="p-12 text-center text-gray-400">Загрузка...</div>

    <template v-else-if="batch">
      <!-- Status card -->
      <div class="bg-white rounded-xl border border-gray-200 p-6 space-y-4">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="text-sm font-medium px-3 py-1 rounded" :class="statusClass(batch.status)">
              {{ statusLabel(batch.status) }}
            </span>
            <span class="text-sm text-gray-500">{{ formatDate(batch.createdAt) }}</span>
          </div>
          <div class="flex items-center gap-3 text-sm text-gray-600">
            <div v-if="batch.status === 'done'" class="flex gap-1">
              <button
                :disabled="exporting"
                class="flex items-center gap-1.5 px-3 py-1.5 font-medium text-primary-600 hover:text-primary-700 border border-primary-200 rounded-l-lg hover:bg-primary-50 disabled:opacity-50"
                @click="exportAll"
              >
                <Download class="w-3.5 h-3.5" :class="{ 'animate-bounce': exporting }" />
                CSV
              </button>
              <button
                :disabled="exporting"
                class="flex items-center px-1.5 py-1.5 font-medium text-primary-600 hover:text-primary-700 border border-l-0 border-primary-200 rounded-r-lg hover:bg-primary-50 disabled:opacity-50"
                title="Выгрузка с фильтрами"
                @click="openExportModal"
              >
                <ChevronDown class="w-3.5 h-3.5" />
              </button>
            </div>
            <span>{{ batch.processedCalls }}/{{ batch.totalCalls }} звонков</span>
            <button
              :disabled="deleting"
              class="flex items-center gap-1.5 px-3 py-1.5 text-red-500 hover:text-red-700 border border-red-200 rounded-lg hover:bg-red-50 disabled:opacity-50 transition-colors"
              title="Удалить батч"
              @click="deleteBatch"
            >
              <Trash2 class="w-3.5 h-3.5" />
              Удалить
            </button>
          </div>
        </div>

        <div v-if="batch.status !== 'done' && batch.status !== 'failed'" class="space-y-1">
          <div class="w-full bg-gray-200 rounded-full h-2">
            <div
              class="h-2 rounded-full transition-all bg-primary-500"
              :style="{ width: `${progressPercent()}%` }"
            />
          </div>
          <p class="text-xs text-gray-500 text-center">{{ progressPercent() }}%</p>
        </div>

        <div class="flex flex-wrap gap-4 text-sm">
          <div v-if="summaryTypeCounts.internal ?? batch.callTypeStats?.internal" class="flex items-center gap-1.5">
            <Building class="w-4 h-4 text-blue-500" />
            <span class="text-gray-700">Внутренние: {{ summaryTypeCounts.internal ?? batch.callTypeStats?.internal }}</span>
          </div>
          <div v-if="summaryTypeCounts.external ?? (batch.callTypeStats && batch.callTypeStats.externalIncoming + batch.callTypeStats.externalOutgoing)" class="flex items-center gap-1.5">
            <Phone class="w-4 h-4 text-purple-500" />
            <span class="text-gray-700">Внешние: {{ summaryTypeCounts.external ?? (batch.callTypeStats!.externalIncoming + batch.callTypeStats!.externalOutgoing) }}</span>
          </div>
          <div v-if="batch.noSpeechCount" class="flex items-center gap-1.5">
            <span class="text-gray-400 text-xs">Без речи: {{ batch.noSpeechCount }}</span>
          </div>
          <div v-if="batch.transcribedOnlyCount" class="flex items-center gap-1.5">
            <span class="text-yellow-500 text-xs">Без оценки: {{ batch.transcribedOnlyCount }}</span>
          </div>
          <div v-if="batch.failedCount" class="flex items-center gap-1.5">
            <span class="text-red-400 text-xs">Ошибки: {{ batch.failedCount }}</span>
          </div>
        </div>
      </div>

      <!-- Summaries -->
      <div v-if="summaries.length || batch.status === 'failed'" class="space-y-3">
        <div class="flex items-center justify-between">
          <h2 class="text-lg font-semibold text-gray-900">Отчёты</h2>
          <button
            v-if="batch.status === 'done' || batch.status === 'failed'"
            :disabled="summarizing"
            class="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-primary-600 hover:text-primary-700 border border-primary-200 rounded-lg hover:bg-primary-50 disabled:opacity-50"
            @click="regenerateSummary"
          >
            <RefreshCw class="w-3.5 h-3.5" :class="{ 'animate-spin': summarizing }" />
            {{ batch.status === 'failed' ? 'Сгенерировать отчёт' : 'Пересоздать' }}
          </button>
        </div>

        <div v-for="s in summaries" :key="s.id" class="bg-white rounded-xl border border-gray-200 p-5">
          <div class="flex items-center gap-2 mb-3">
            <span
              class="text-xs font-medium px-2 py-0.5 rounded"
              :class="s.scope === 'internal' ? 'bg-blue-100 text-blue-700' : s.scope === 'external' ? 'bg-purple-100 text-purple-700' : 'bg-gray-100 text-gray-700'"
            >
              {{ s.scope === 'internal' ? 'Внутренние' : s.scope === 'external' ? 'Внешние' : 'Все' }}
            </span>
            <span class="text-xs text-gray-400">{{ formatDate(s.createdAt) }}</span>
          </div>

          <template v-if="parseSummaryContent(s.content)">
            <div class="space-y-4 text-sm">
              <!-- Summary text -->
              <p v-if="parseSummaryContent(s.content)!.summary_text" class="text-gray-700 leading-relaxed">
                {{ parseSummaryContent(s.content)!.summary_text }}
              </p>

              <!-- Stats row -->
              <div class="flex gap-4 flex-wrap">
                <div v-if="parseSummaryContent(s.content)!.total_calls" class="bg-gray-50 rounded-lg px-3 py-2">
                  <p class="text-xs text-gray-500">Звонков</p>
                  <p class="text-lg font-semibold text-gray-900">{{ parseSummaryContent(s.content)!.total_calls }}</p>
                </div>
                <div v-if="parseSummaryContent(s.content)!.avg_score != null" class="bg-gray-50 rounded-lg px-3 py-2">
                  <p class="text-xs text-gray-500">Средний балл</p>
                  <p class="text-lg font-semibold" :class="parseSummaryContent(s.content)!.avg_score >= 70 ? 'text-green-600' : parseSummaryContent(s.content)!.avg_score >= 50 ? 'text-yellow-600' : 'text-red-600'">
                    {{ Math.round(parseSummaryContent(s.content)!.avg_score) }}
                  </p>
                </div>
              </div>

              <!-- Business process issues (internal) -->
              <div v-if="parseSummaryContent(s.content)!.business_process_issues?.length">
                <p class="font-medium text-gray-800 mb-2">Проблемы бизнес-процессов</p>
                <div class="space-y-1.5">
                  <div v-for="(issue, i) in parseSummaryContent(s.content)!.business_process_issues" :key="i"
                    class="flex items-start gap-2"
                    :class="issue.call_ids?.length ? 'cursor-pointer hover:bg-gray-50 rounded-lg px-2 py-1 -mx-2 transition-colors' : ''"
                    @click="issue.call_ids?.length && openCallsModal(issue.issue, issue.call_ids)">
                    <span class="mt-1 w-2 h-2 rounded-full shrink-0"
                      :class="issue.severity === 'high' ? 'bg-red-500' : issue.severity === 'medium' ? 'bg-yellow-500' : 'bg-gray-400'" />
                    <span class="text-gray-700">{{ issue.issue }}<span v-if="issue.call_ids?.length" class="ml-1 text-primary-500 underline decoration-dotted">({{ issue.call_ids.length }} зв.)</span></span>
                  </div>
                </div>
              </div>

              <!-- Communication issues (internal) -->
              <div v-if="parseSummaryContent(s.content)!.communication_issues?.length">
                <p class="font-medium text-gray-800 mb-2">Проблемы коммуникации</p>
                <div class="space-y-1.5">
                  <div v-for="(issue, i) in parseSummaryContent(s.content)!.communication_issues" :key="i"
                    class="flex items-start gap-2"
                    :class="issue.call_ids?.length ? 'cursor-pointer hover:bg-gray-50 rounded-lg px-2 py-1 -mx-2 transition-colors' : ''"
                    @click="issue.call_ids?.length && openCallsModal(issue.issue, issue.call_ids)">
                    <span class="mt-1 w-2 h-2 rounded-full shrink-0"
                      :class="issue.severity === 'high' ? 'bg-red-500' : issue.severity === 'medium' ? 'bg-yellow-500' : 'bg-gray-400'" />
                    <span class="text-gray-700">{{ issue.issue }}<span v-if="issue.call_ids?.length" class="ml-1 text-primary-500 underline decoration-dotted">({{ issue.call_ids.length }} зв.)</span></span>
                  </div>
                </div>
              </div>

              <!-- Manager performance (external) -->
              <div v-if="parseSummaryContent(s.content)!.manager_performance?.length">
                <p class="font-medium text-gray-800 mb-2">Производительность менеджеров</p>
                <div class="space-y-2">
                  <div v-for="(mgr, i) in parseSummaryContent(s.content)!.manager_performance" :key="i"
                    class="bg-gray-50 rounded-lg p-3 transition-colors"
                    :class="mgr.call_ids?.length ? 'cursor-pointer hover:bg-gray-100' : ''"
                    @click="mgr.call_ids?.length && openCallsModal(mgr.manager, mgr.call_ids)">
                    <div class="flex items-center justify-between mb-1">
                      <span class="font-medium text-gray-900">{{ mgr.manager }}</span>
                      <span class="text-xs" :class="mgr.call_ids?.length ? 'text-primary-500 underline decoration-dotted' : 'text-gray-500'">{{ mgr.calls_count }} зв. · ср. балл {{ Math.round(mgr.avg_score) }}</span>
                    </div>
                    <ul v-if="mgr.key_issues?.length" class="space-y-0.5">
                      <li v-for="(iss, j) in mgr.key_issues" :key="j" class="text-gray-600 text-xs">• {{ iss }}</li>
                    </ul>
                  </div>
                </div>
              </div>

              <!-- Script adherence (external) -->
              <div v-if="parseSummaryContent(s.content)!.script_adherence">
                <p class="font-medium text-gray-800 mb-2">Следование скрипту</p>
                <div class="bg-gray-50 rounded-lg p-3">
                  <p v-if="parseSummaryContent(s.content)!.script_adherence.avg_adherence_percent != null" class="text-gray-700">
                    Среднее следование: <span class="font-semibold">{{ parseSummaryContent(s.content)!.script_adherence.avg_adherence_percent }}%</span>
                  </p>
                  <div v-if="parseSummaryContent(s.content)!.script_adherence.most_skipped_criteria?.length" class="mt-1">
                    <p class="text-xs text-gray-500">Чаще всего пропускают:</p>
                    <ul class="mt-0.5 space-y-0.5">
                      <li v-for="(cr, i) in parseSummaryContent(s.content)!.script_adherence.most_skipped_criteria" :key="i" class="text-gray-600 text-xs">• {{ cr }}</li>
                    </ul>
                  </div>
                </div>
              </div>

              <!-- Recurring patterns (internal) -->
              <div v-if="parseSummaryContent(s.content)!.recurring_patterns?.length">
                <p class="font-medium text-gray-800 mb-1">Повторяющиеся паттерны</p>
                <ul class="list-disc list-inside space-y-0.5 text-gray-600">
                  <li v-for="(p, i) in parseSummaryContent(s.content)!.recurring_patterns" :key="i">{{ p }}</li>
                </ul>
              </div>

              <!-- Common client complaints (external) -->
              <div v-if="parseSummaryContent(s.content)!.common_client_complaints?.length">
                <p class="font-medium text-gray-800 mb-1">Частые жалобы клиентов</p>
                <ul class="space-y-0.5 text-gray-600">
                  <template v-for="(c, i) in parseSummaryContent(s.content)!.common_client_complaints" :key="i">
                    <li v-if="typeof c === 'string'" class="list-disc list-inside">{{ c }}</li>
                    <li v-else
                      class="list-disc list-inside transition-colors"
                      :class="c.call_ids?.length ? 'cursor-pointer hover:bg-gray-50 rounded px-2 py-0.5 -mx-2 text-primary-700' : ''"
                      @click="c.call_ids?.length && openCallsModal(c.complaint, c.call_ids)">
                      {{ c.complaint }}
                      <span v-if="c.call_ids?.length" class="text-primary-500 text-xs underline decoration-dotted ml-1">({{ c.call_ids.length }} зв.)</span>
                    </li>
                  </template>
                </ul>
              </div>

              <!-- Recommendations -->
              <div v-if="parseSummaryContent(s.content)!.top_recommendations?.length">
                <p class="font-medium text-gray-800 mb-1">Рекомендации</p>
                <ul class="list-disc list-inside space-y-0.5 text-gray-600">
                  <li v-for="(rec, i) in parseSummaryContent(s.content)!.top_recommendations" :key="i">{{ rec }}</li>
                </ul>
              </div>
              <div v-else-if="parseSummaryContent(s.content)!.recommendations?.length">
                <p class="font-medium text-gray-800 mb-1">Рекомендации</p>
                <ul class="list-disc list-inside space-y-0.5 text-gray-600">
                  <li v-for="(rec, i) in parseSummaryContent(s.content)!.recommendations" :key="i">{{ rec }}</li>
                </ul>
              </div>
            </div>
          </template>
          <pre v-else class="text-xs text-gray-500 whitespace-pre-wrap">{{ s.content }}</pre>
        </div>
      </div>

      <!-- Calls tabs -->
      <div class="space-y-3">
        <div class="flex items-center gap-2">
          <button
            v-for="t in (['all', 'internal', 'external'] as const)"
            :key="t"
            class="px-3 py-1.5 text-sm font-medium rounded-lg transition-colors"
            :class="tab === t ? 'bg-primary-100 text-primary-700' : 'text-gray-600 hover:bg-gray-100'"
            @click="tab = t; callsPage = 1; pageInput = '1'; syncQuery(); fetchCalls()"
          >
            {{ t === 'all' ? 'Все звонки' : t === 'internal' ? 'Внутренние' : 'Внешние' }}
          </button>
        </div>

        <div class="bg-white rounded-xl border border-gray-200 overflow-hidden">
          <div v-if="callsLoading" class="p-8 text-center text-gray-400">Загрузка...</div>
          <table v-else class="w-full text-sm">
            <thead class="bg-gray-50 border-b border-gray-200">
              <tr>
                <th class="text-left px-5 py-3 font-medium text-gray-600">Участники</th>
                <th class="text-left px-5 py-3 font-medium text-gray-600">Тип</th>
                <th class="text-left px-5 py-3 font-medium text-gray-600">Статус</th>
                <th class="text-left px-5 py-3 font-medium text-gray-600">Длительность</th>
                <th class="text-left px-5 py-3 font-medium text-gray-600">Дата</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-gray-100">
              <tr v-if="!calls.length">
                <td colspan="5" class="px-5 py-8 text-center text-gray-400">Нет звонков</td>
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
                    {{ participantLabel(call.managerName, call.participantNames) }}
                    <template v-if="call.secondManagerId || call.secondManagerName">
                      <span class="text-gray-400 mx-1">&harr;</span>
                      {{ participantLabel(call.secondManagerName, call.secondParticipantNames) }}
                    </template>
                  </td>
                  <td class="px-5 py-3">
                    <span
                      class="text-[10px] font-medium px-1.5 py-0.5 rounded"
                      :class="callTypeBadgeClass(call.callType)"
                    >{{ callTypeLabel(call.callType) }}</span>
                  </td>
                  <td class="px-5 py-3"><CallStatusBadge :status="call.status" /></td>
                  <td class="px-5 py-3 text-gray-600">{{ formatDuration(call.durationSeconds) }}</td>
                  <td class="px-5 py-3 text-gray-500">{{ formatDate(call.createdAt) }}</td>
                </tr>
              </RouterLink>
            </tbody>
          </table>
        </div>

        <div v-if="callsTotalPages > 1" class="flex items-center justify-center gap-2 mt-3">
          <button
            :disabled="callsPage <= 1"
            class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
            @click="goToPage(callsPage - 1)"
          >
            <ChevronLeft class="w-4 h-4" />
          </button>
          <div class="flex items-center gap-1.5 text-sm text-gray-600">
            <input
              v-model="pageInput"
              type="text"
              inputmode="numeric"
              class="w-12 text-center border border-gray-300 rounded-lg px-2 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-primary-300"
              @blur="onPageInputBlur"
              @keydown="onPageInputKey"
            />
            <span class="text-gray-400">/ {{ callsTotalPages }}</span>
          </div>
          <button
            :disabled="callsPage >= callsTotalPages"
            class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
            @click="goToPage(callsPage + 1)"
          >
            <ChevronRight class="w-4 h-4" />
          </button>
        </div>
      </div>
    </template>

    <BatchCallsModal
      v-if="modalVisible"
      :batch-id="batchId"
      :title="modalTitle"
      :call-ids="modalCallIds"
      @close="modalVisible = false"
    />

    <!-- Export modal -->
    <teleport to="body">
      <div v-if="exportModalOpen" class="fixed inset-0 z-50 flex items-center justify-center">
        <div class="fixed inset-0 bg-black/40" @click="exportModalOpen = false" />
        <div class="relative bg-white rounded-xl shadow-xl w-full max-w-md mx-4 p-6 space-y-4">
          <h3 class="text-lg font-semibold text-gray-900">Выгрузка CSV с фильтрами</h3>

          <div v-if="managerOptions.length">
            <label class="block text-sm font-medium text-gray-700 mb-1">Сотрудники</label>
            <MultiSelect
              v-model="exportManagers"
              :options="managerOptions"
              placeholder="Все сотрудники..."
            />
          </div>

          <div v-if="departments.length">
            <label class="block text-sm font-medium text-gray-700 mb-1">Отдел</label>
            <select
              v-model="exportDepartment"
              class="w-full appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
            >
              <option value="">Все отделы</option>
              <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select>
          </div>

          <div class="flex justify-end gap-2 pt-2">
            <button
              class="px-4 py-2 text-sm text-gray-600 hover:text-gray-800 rounded-lg hover:bg-gray-100"
              @click="exportModalOpen = false"
            >
              Отмена
            </button>
            <button
              class="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700"
              @click="doExport"
            >
              <Download class="w-4 h-4" />
              Скачать
            </button>
          </div>
        </div>
      </div>
    </teleport>
  </div>
</template>
