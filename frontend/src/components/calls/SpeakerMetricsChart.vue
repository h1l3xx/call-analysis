<script setup lang="ts">
import { computed } from 'vue'
import type { SpeakerMetricsResponse } from '@/types'
import { useFormatters } from '@/composables/useFormatters'
import { Mic, MicOff, Timer, Zap } from 'lucide-vue-next'

const props = defineProps<{
  data: SpeakerMetricsResponse
  callType?: string | null
}>()
const { formatPercent } = useFormatters()

const isInternal = computed(() => props.callType === 'internal')

const speaker1Label = computed(() => isInternal.value ? 'Сотрудник 1' : 'Менеджер')
const speaker2Label = computed(() => isInternal.value ? 'Сотрудник 2' : 'Клиент')

const speaker1Color = computed(() => isInternal.value ? 'bg-sky-500' : 'bg-primary-500')
const speaker2Color = computed(() => isInternal.value ? 'bg-amber-500' : 'bg-emerald-500')
const speaker1Icon = computed(() => isInternal.value ? 'text-sky-500' : 'text-primary-500')
const speaker2Icon = computed(() => isInternal.value ? 'text-amber-500' : 'text-emerald-500')

const managerPct = computed(() => (props.data.managerTalkRatio ?? 0) * 100)
const clientPct = computed(() => (props.data.clientTalkRatio ?? 0) * 100)
const silencePct = computed(() => (props.data.silenceRatio ?? 0) * 100)
</script>

<template>
  <div class="space-y-6">
    <!-- Talk ratio bar -->
    <div>
      <h4 class="text-sm font-medium text-gray-700 mb-3">Распределение речи</h4>
      <div class="h-6 rounded-full overflow-hidden flex bg-gray-100">
        <div :class="speaker1Color" class="transition-all" :style="{ width: `${managerPct}%` }" />
        <div :class="speaker2Color" class="transition-all" :style="{ width: `${clientPct}%` }" />
        <div class="bg-gray-300 transition-all" :style="{ width: `${silencePct}%` }" />
      </div>
      <div class="flex justify-between mt-2 text-xs text-gray-500">
        <span class="flex items-center gap-1"><span class="w-2.5 h-2.5 rounded-full" :class="speaker1Color" /> {{ speaker1Label }} {{ formatPercent(data.managerTalkRatio) }}</span>
        <span class="flex items-center gap-1"><span class="w-2.5 h-2.5 rounded-full" :class="speaker2Color" /> {{ speaker2Label }} {{ formatPercent(data.clientTalkRatio) }}</span>
        <span class="flex items-center gap-1"><span class="w-2.5 h-2.5 rounded-full bg-gray-300" /> Тишина {{ formatPercent(data.silenceRatio) }}</span>
      </div>
    </div>

    <!-- Metrics grid -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-4">
      <div class="bg-gray-50 rounded-xl p-4">
        <div class="flex items-center gap-2 mb-1">
          <Mic class="w-4 h-4" :class="speaker1Icon" />
          <span class="text-xs text-gray-500">Скорость ({{ speaker1Label.toLowerCase() }})</span>
        </div>
        <p class="text-lg font-semibold text-gray-900">{{ data.managerWpm?.toFixed(0) ?? '—' }} <span class="text-xs text-gray-400">слов/мин</span></p>
      </div>
      <div class="bg-gray-50 rounded-xl p-4">
        <div class="flex items-center gap-2 mb-1">
          <MicOff class="w-4 h-4" :class="speaker2Icon" />
          <span class="text-xs text-gray-500">Скорость ({{ speaker2Label.toLowerCase() }})</span>
        </div>
        <p class="text-lg font-semibold text-gray-900">{{ data.clientWpm?.toFixed(0) ?? '—' }} <span class="text-xs text-gray-400">слов/мин</span></p>
      </div>
      <div class="bg-gray-50 rounded-xl p-4">
        <div class="flex items-center gap-2 mb-1">
          <Zap class="w-4 h-4 text-warning-500" />
          <span class="text-xs text-gray-500">Перебивания</span>
        </div>
        <p class="text-lg font-semibold text-gray-900">{{ data.interruptionsCount ?? '—' }}</p>
      </div>
      <div class="bg-gray-50 rounded-xl p-4">
        <div class="flex items-center gap-2 mb-1">
          <Timer class="w-4 h-4 text-gray-500" />
          <span class="text-xs text-gray-500">Ср. пауза</span>
        </div>
        <p class="text-lg font-semibold text-gray-900">{{ data.avgPauseSeconds?.toFixed(1) ?? '—' }} <span class="text-xs text-gray-400">сек</span></p>
      </div>
    </div>
  </div>
</template>
