<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { CallResponse } from '@/types'
import { useFormatters } from '@/composables/useFormatters'
import CallStatusBadge from '@/components/calls/CallStatusBadge.vue'

defineProps<{
  calls: CallResponse[]
  loading: boolean
}>()

const { formatDate, formatDuration } = useFormatters()
</script>

<template>
  <div class="bg-white rounded-xl border border-gray-200">
    <div class="px-5 py-4 border-b border-gray-200">
      <h3 class="text-base font-semibold text-gray-900">Последние звонки</h3>
    </div>

    <div v-if="loading" class="p-8 text-center text-gray-400">Загрузка...</div>

    <div v-else-if="!calls.length" class="p-8 text-center text-gray-400">Нет звонков</div>

    <div v-else class="divide-y divide-gray-100">
      <RouterLink
        v-for="call in calls"
        :key="call.id"
        :to="`/calls/${call.id}`"
        class="flex items-center justify-between px-5 py-3.5 hover:bg-gray-50 transition-colors"
      >
        <div class="min-w-0 flex-1">
          <p class="text-sm font-medium text-gray-900 truncate">{{ call.managerName || 'Без менеджера' }}</p>
          <p class="text-xs text-gray-500">{{ call.scriptName }} · {{ formatDate(call.createdAt) }}</p>
        </div>
        <div class="flex items-center gap-3 ml-4 shrink-0">
          <span class="text-xs text-gray-500">{{ formatDuration(call.durationSeconds) }}</span>
          <CallStatusBadge :status="call.status" />
        </div>
      </RouterLink>
    </div>
  </div>
</template>
