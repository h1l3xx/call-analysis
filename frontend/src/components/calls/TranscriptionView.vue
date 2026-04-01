<script setup lang="ts">
import { ref, computed } from 'vue'
import type { TranscriptionResponse } from '@/types'
import { FileText, MessageSquare } from 'lucide-vue-next'

const props = defineProps<{
  data: TranscriptionResponse
  callType?: string | null
}>()
const showRaw = ref(false)
const viewMode = ref<'dialogue' | 'text'>(props.data.speakerTurns?.length ? 'dialogue' : 'text')

const isInternal = computed(() => props.callType === 'internal')

const hasTurns = computed(() => !!props.data.speakerTurns?.length)

function formatTime(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}

function speakerLabel(speaker: string): string {
  if (isInternal.value) {
    const internal: Record<string, string> = {
      manager: 'Сотрудник 1', speaker_1: 'Сотрудник 1',
      client: 'Сотрудник 2', speaker_2: 'Сотрудник 2',
    }
    return internal[speaker] || speaker
  }
  const labels: Record<string, string> = {
    manager: 'Менеджер', client: 'Клиент',
    speaker_1: 'Сотрудник 1', speaker_2: 'Сотрудник 2',
    unknown: 'Неизвестно',
  }
  return labels[speaker] || speaker
}

function isSpeaker1(speaker: string): boolean {
  return speaker === 'manager' || speaker === 'speaker_1'
}

function speakerColor(speaker: string): string {
  if (isInternal.value) {
    return isSpeaker1(speaker)
      ? 'bg-sky-100 border-sky-300 text-sky-900'
      : 'bg-amber-100 border-amber-300 text-amber-900'
  }
  if (speaker === 'manager') return 'bg-primary-100 border-primary-300 text-primary-900'
  if (speaker === 'client') return 'bg-emerald-100 border-emerald-300 text-emerald-900'
  if (speaker === 'speaker_1') return 'bg-sky-100 border-sky-300 text-sky-900'
  if (speaker === 'speaker_2') return 'bg-amber-100 border-amber-300 text-amber-900'
  return 'bg-gray-100 border-gray-300 text-gray-900'
}

function badgeColor(speaker: string): string {
  if (isInternal.value) {
    return isSpeaker1(speaker) ? 'bg-sky-600 text-white' : 'bg-amber-600 text-white'
  }
  if (speaker === 'manager') return 'bg-primary-600 text-white'
  if (speaker === 'client') return 'bg-emerald-600 text-white'
  if (speaker === 'speaker_1') return 'bg-sky-600 text-white'
  if (speaker === 'speaker_2') return 'bg-amber-600 text-white'
  return 'bg-gray-600 text-white'
}

function isRight(speaker: string): boolean {
  return speaker === 'client' || speaker === 'speaker_2'
}
</script>

<template>
  <div class="space-y-4">
    <div class="flex items-center gap-3 text-sm flex-wrap">
      <span v-if="data.language" class="px-2.5 py-1 bg-gray-100 rounded-md text-gray-700">
        Язык: {{ data.language }}
        <span v-if="data.languageProb" class="text-gray-500">({{ (data.languageProb * 100).toFixed(0) }}%)</span>
      </span>
      <span v-if="data.classification" class="px-2.5 py-1 bg-indigo-50 text-indigo-700 rounded-md">
        {{ data.classification }}
      </span>

      <div class="ml-auto flex gap-2">
        <button
          v-if="hasTurns"
          class="flex items-center gap-1.5 px-3 py-1 rounded-md text-sm transition-colors"
          :class="viewMode === 'dialogue' ? 'bg-primary-100 text-primary-700' : 'hover:bg-gray-100 text-gray-600'"
          @click="viewMode = 'dialogue'"
        >
          <MessageSquare class="w-3.5 h-3.5" />
          Диалог
        </button>
        <button
          class="flex items-center gap-1.5 px-3 py-1 rounded-md text-sm transition-colors"
          :class="viewMode === 'text' ? 'bg-primary-100 text-primary-700' : 'hover:bg-gray-100 text-gray-600'"
          @click="viewMode = 'text'"
        >
          <FileText class="w-3.5 h-3.5" />
          Текст
        </button>
        <button
          v-if="viewMode === 'text' && data.rawText && data.cleanedText"
          class="text-primary-600 hover:text-primary-700 text-sm"
          @click="showRaw = !showRaw"
        >
          {{ showRaw ? 'Очищенный' : 'Исходный' }}
        </button>
      </div>
    </div>

    <!-- Dialogue view -->
    <div
      v-if="viewMode === 'dialogue' && hasTurns"
      class="space-y-3 max-h-[32rem] overflow-y-auto pr-1"
    >
      <div
        v-for="(turn, idx) in data.speakerTurns"
        :key="idx"
        class="flex"
        :class="isRight(turn.speaker) ? 'justify-end' : 'justify-start'"
      >
        <div class="max-w-[75%] space-y-1">
          <div
            class="flex items-center gap-2 text-xs"
            :class="isRight(turn.speaker) ? 'justify-end' : 'justify-start'"
          >
            <span
              class="px-1.5 py-0.5 rounded-full text-[10px] font-medium"
              :class="badgeColor(turn.speaker)"
            >
              {{ speakerLabel(turn.speaker) }}
            </span>
            <span class="text-gray-400">{{ formatTime(turn.start) }}</span>
          </div>
          <div
            class="px-3.5 py-2.5 rounded-2xl border text-sm leading-relaxed"
            :class="speakerColor(turn.speaker)"
          >
            {{ turn.text }}
          </div>
        </div>
      </div>
    </div>

    <!-- Text view -->
    <div
      v-else-if="viewMode === 'text' && (data.cleanedText || data.rawText)"
      class="bg-gray-50 rounded-xl p-5 text-sm text-gray-800 leading-relaxed whitespace-pre-wrap max-h-96 overflow-y-auto"
    >
      {{ showRaw ? data.rawText : (data.cleanedText || data.rawText) }}
    </div>

    <!-- Empty state -->
    <div v-else class="text-center py-8 text-gray-400">
      <FileText class="w-8 h-8 mx-auto mb-2" />
      <p>Транскрипция отсутствует</p>
    </div>
  </div>
</template>
