<script setup lang="ts">
import { computed } from 'vue'
import type { QualityScoreResponse, ErrorEventResponse } from '@/types'
import { useFormatters } from '@/composables/useFormatters'
import { Award, ThumbsUp, ThumbsDown, Lightbulb } from 'lucide-vue-next'

const props = defineProps<{
  quality: QualityScoreResponse | null
  errors: ErrorEventResponse[]
}>()

const { formatScore } = useFormatters()

const scoreColor = computed(() => {
  const s = props.quality?.overallScore
  if (s == null) return 'text-gray-400'
  if (s >= 80) return 'text-green-600'
  if (s >= 60) return 'text-yellow-600'
  return 'text-red-600'
})

function tryParseJson(val: string | null): string[] {
  if (!val) return []
  try {
    const parsed = JSON.parse(val)
    return Array.isArray(parsed) ? parsed : [String(parsed)]
  } catch {
    return val.split('\n').filter(Boolean)
  }
}
</script>

<template>
  <div v-if="!quality" class="text-center py-8 text-gray-400">
    <Award class="w-8 h-8 mx-auto mb-2" />
    <p>Оценка качества не выполнена</p>
  </div>

  <div v-else class="space-y-6">
    <!-- Score header -->
    <div class="flex items-center gap-6 flex-wrap">
      <div class="text-center">
        <p :class="['text-4xl font-bold', scoreColor]">{{ formatScore(quality.overallScore) }}</p>
        <p class="text-xs text-gray-500 mt-1">Общий балл</p>
      </div>
      <div class="text-center">
        <p class="text-xl font-semibold text-gray-900">{{ formatScore(quality.requiredScore) }}</p>
        <p class="text-xs text-gray-500">Обязательные</p>
      </div>
      <div class="text-center">
        <p class="text-xl font-semibold text-gray-900">{{ formatScore(quality.optionalScore) }}</p>
        <p class="text-xs text-gray-500">Опциональные</p>
      </div>
    </div>

    <!-- Strengths / Weaknesses / Recommendations -->
    <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
      <div class="bg-green-50 rounded-xl p-4">
        <div class="flex items-center gap-2 mb-2">
          <ThumbsUp class="w-4 h-4 text-green-600" />
          <h4 class="text-sm font-medium text-green-800">Сильные стороны</h4>
        </div>
        <ul class="space-y-1 text-sm text-green-700">
          <li v-for="(s, i) in tryParseJson(quality.strengths)" :key="i">• {{ s }}</li>
          <li v-if="!tryParseJson(quality.strengths).length" class="text-green-400">Нет данных</li>
        </ul>
      </div>

      <div class="bg-red-50 rounded-xl p-4">
        <div class="flex items-center gap-2 mb-2">
          <ThumbsDown class="w-4 h-4 text-red-600" />
          <h4 class="text-sm font-medium text-red-800">Слабые стороны</h4>
        </div>
        <ul class="space-y-1 text-sm text-red-700">
          <li v-for="(w, i) in tryParseJson(quality.weaknesses)" :key="i">• {{ w }}</li>
          <li v-if="!tryParseJson(quality.weaknesses).length" class="text-red-400">Нет данных</li>
        </ul>
      </div>

      <div class="bg-blue-50 rounded-xl p-4">
        <div class="flex items-center gap-2 mb-2">
          <Lightbulb class="w-4 h-4 text-blue-600" />
          <h4 class="text-sm font-medium text-blue-800">Рекомендации</h4>
        </div>
        <ul class="space-y-1 text-sm text-blue-700">
          <li v-for="(r, i) in tryParseJson(quality.recommendations)" :key="i">• {{ r }}</li>
          <li v-if="!tryParseJson(quality.recommendations).length" class="text-blue-400">Нет данных</li>
        </ul>
      </div>
    </div>

    <!-- Error events -->
    <div v-if="errors.length">
      <h4 class="text-sm font-medium text-gray-700 mb-3">Ошибки ({{ errors.length }})</h4>
      <div class="space-y-2">
        <div
          v-for="err in errors"
          :key="err.id"
          class="bg-white border border-gray-200 rounded-lg p-3 flex items-start gap-3"
        >
          <span
            :class="[
              'mt-0.5 w-2 h-2 rounded-full shrink-0',
              err.severity === 'high' ? 'bg-red-500' : err.severity === 'medium' ? 'bg-yellow-500' : 'bg-gray-400',
            ]"
          />
          <div class="min-w-0 flex-1">
            <p class="text-sm font-medium text-gray-900">{{ err.criterionName || 'Без критерия' }}</p>
            <p v-if="err.comment" class="text-sm text-gray-600 mt-0.5">{{ err.comment }}</p>
            <p v-if="err.quote" class="text-xs text-gray-400 mt-1 italic">"{{ err.quote }}"</p>
          </div>
          <span v-if="err.score != null" class="text-sm font-medium text-gray-500 shrink-0">{{ err.score }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
