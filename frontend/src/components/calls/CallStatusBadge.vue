<script setup lang="ts">
import { computed } from 'vue'
import type { CallStatus } from '@/types'

const props = defineProps<{ status: CallStatus }>()

const config = computed(() => {
  const map: Record<string, { label: string; classes: string }> = {
    queued: { label: 'В очереди', classes: 'bg-gray-100 text-gray-700' },
    processing: { label: 'Обработка', classes: 'bg-blue-100 text-blue-700' },
    transcribed_only: { label: 'Транскрибирован', classes: 'bg-indigo-100 text-indigo-700' },
    pending_review: { label: 'На проверке', classes: 'bg-yellow-100 text-yellow-700' },
    analyzing: { label: 'Анализ', classes: 'bg-purple-100 text-purple-700' },
    done: { label: 'Готово', classes: 'bg-green-100 text-green-700' },
    failed: { label: 'Ошибка', classes: 'bg-red-100 text-red-700' },
  }
  return map[props.status] || { label: props.status, classes: 'bg-gray-100 text-gray-700' }
})
</script>

<template>
  <span :class="['inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium whitespace-nowrap', config.classes]">
    {{ config.label }}
  </span>
</template>
