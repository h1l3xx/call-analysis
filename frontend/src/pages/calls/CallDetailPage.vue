<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, RefreshCw } from 'lucide-vue-next'
import { callsApi } from '@/api'
import type { CallDetailResponse, CallResultResponse } from '@/types'
import { useFormatters } from '@/composables/useFormatters'
import CallStatusBadge from '@/components/calls/CallStatusBadge.vue'
import AudioPlayer from '@/components/calls/AudioPlayer.vue'
import TranscriptionView from '@/components/calls/TranscriptionView.vue'
import SpeakerMetricsChart from '@/components/calls/SpeakerMetricsChart.vue'
import QualityScoreCard from '@/components/calls/QualityScoreCard.vue'

const route = useRoute()
const router = useRouter()
const { formatDate, formatDuration, participantLabel } = useFormatters()

const call = ref<CallDetailResponse | null>(null)
const result = ref<CallResultResponse | null>(null)
const loading = ref(true)
const activeTab = ref<'transcription' | 'metrics' | 'quality'>('transcription')
let pollTimer: ReturnType<typeof setInterval> | null = null

const isProcessing = computed(() =>
  call.value && ['queued', 'processing', 'analyzing'].includes(call.value.status),
)

const effectiveCallType = computed(() => {
  if (call.value?.callType) return call.value.callType
  const turns = result.value?.transcription?.speakerTurns
  if (turns?.some(t => t.speaker === 'speaker_1' || t.speaker === 'speaker_2')) return 'internal'
  if (turns?.some(t => t.speaker === 'manager' || t.speaker === 'client')) return 'external'
  return null
})

async function fetchData() {
  const id = route.params.id as string
  try {
    const [callRes, resultRes] = await Promise.all([
      callsApi.get(id),
      callsApi.getResult(id).catch(() => null),
    ])
    call.value = callRes.data
    if (resultRes) result.value = resultRes.data
  } finally {
    loading.value = false
  }
}

function startPolling() {
  pollTimer = setInterval(async () => {
    if (!isProcessing.value) {
      if (pollTimer) clearInterval(pollTimer)
      return
    }
    await fetchData()
  }, 5000)
}

onMounted(async () => {
  await fetchData()
  if (isProcessing.value) startPolling()
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

const tabs = [
  { key: 'transcription', label: 'Транскрипция' },
  { key: 'metrics', label: 'Метрики спикеров' },
  { key: 'quality', label: 'Оценка качества' },
] as const
</script>

<template>
  <div class="space-y-5">
    <div class="flex items-center gap-3">
      <button @click="router.back()" class="p-2 rounded-lg hover:bg-gray-100 text-gray-500 transition-colors">
        <ArrowLeft class="w-5 h-5" />
      </button>
      <h1 class="text-2xl font-bold text-gray-900">Детали звонка</h1>
    </div>

    <div v-if="loading" class="text-center py-12 text-gray-400">Загрузка...</div>

    <template v-else-if="call">
      <!-- Call info card -->
      <div class="bg-white rounded-xl border border-gray-200 p-5">
        <div class="flex flex-wrap gap-x-8 gap-y-3">
          <div>
            <p class="text-xs text-gray-500">
              {{ (call.secondManagerId || call.secondManagerName || (call.participantNames && call.participantNames.length > 1)) ? 'Участники' : 'Сотрудник' }}
            </p>
            <p class="text-sm font-medium text-gray-900">
              {{ participantLabel(call.managerName, call.participantNames) }}
              <template v-if="call.secondManagerId || call.secondManagerName">
                <span class="text-gray-400 mx-1">&harr;</span>
                {{ participantLabel(call.secondManagerName, call.secondParticipantNames) }}
              </template>
            </p>
          </div>
          <div>
            <p class="text-xs text-gray-500">Скрипт</p>
            <p class="text-sm font-medium text-gray-900">{{ call.scriptName || '—' }}</p>
          </div>
          <div>
            <p class="text-xs text-gray-500">Статус</p>
            <CallStatusBadge :status="call.status" />
          </div>
          <div>
            <p class="text-xs text-gray-500">Длительность</p>
            <p class="text-sm font-medium text-gray-900">{{ formatDuration(call.durationSeconds) }}</p>
          </div>
          <div>
            <p class="text-xs text-gray-500">Файл</p>
            <p class="text-sm text-gray-900">{{ call.audioFilename || '—' }}</p>
          </div>
          <div>
            <p class="text-xs text-gray-500">Создан</p>
            <p class="text-sm text-gray-900">{{ formatDate(call.createdAt) }}</p>
          </div>
        </div>

        <div v-if="result?.qualityScore?.summary" class="mt-4 bg-gray-50 rounded-lg px-4 py-3">
          <p class="text-xs text-gray-500 mb-1">Описание</p>
          <p class="text-sm text-gray-700">{{ result.qualityScore.summary }}</p>
        </div>

        <div v-if="call.status !== 'queued'" class="mt-4">
          <AudioPlayer :call-id="(route.params.id as string)" />
        </div>

        <div v-if="isProcessing" class="mt-4 flex items-center gap-2 text-sm text-primary-600">
          <RefreshCw class="w-4 h-4 animate-spin" />
          Обработка... Автообновление каждые 5 сек
        </div>

        <div v-if="call.status === 'no_speech'" class="mt-4 bg-gray-50 text-gray-600 text-sm rounded-lg px-4 py-3">
          В записи не обнаружена речь — звонок пропущен.
        </div>
        <div v-else-if="call.errorMessage" class="mt-4 bg-red-50 text-red-700 text-sm rounded-lg px-4 py-3">
          <strong>Ошибка ({{ call.failedStep ? ({ transcription: 'транскрибация', evaluation: 'оценка', pipeline_analyze: 'обработка' } as Record<string, string>)[call.failedStep] || call.failedStep : 'обработка' }}):</strong> {{ call.errorMessage }}
        </div>
      </div>

      <!-- Tabs -->
      <div class="bg-white rounded-xl border border-gray-200">
        <div class="border-b border-gray-200 px-5">
          <nav class="flex gap-6 -mb-px">
            <button
              v-for="tab in tabs"
              :key="tab.key"
              :class="[
                'py-3 text-sm font-medium border-b-2 transition-colors',
                activeTab === tab.key
                  ? 'border-primary-600 text-primary-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700',
              ]"
              @click="activeTab = tab.key"
            >
              {{ tab.label }}
            </button>
          </nav>
        </div>

        <div class="p-5">
          <TranscriptionView
            v-if="activeTab === 'transcription' && result?.transcription"
            :data="result.transcription"
            :call-type="effectiveCallType"
          />
          <div v-else-if="activeTab === 'transcription'" class="text-center py-8 text-gray-400">
            Транскрипция отсутствует
          </div>

          <SpeakerMetricsChart
            v-if="activeTab === 'metrics' && result?.speakerMetrics"
            :data="result.speakerMetrics"
            :call-type="effectiveCallType"
          />
          <div v-else-if="activeTab === 'metrics'" class="text-center py-8 text-gray-400">
            Метрики спикеров отсутствуют
          </div>

          <QualityScoreCard
            v-if="activeTab === 'quality'"
            :quality="result?.qualityScore ?? null"
            :errors="result?.errors ?? []"
          />
        </div>
      </div>
    </template>
  </div>
</template>
