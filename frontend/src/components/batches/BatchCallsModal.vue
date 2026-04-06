<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { X, ExternalLink, Loader2 } from 'lucide-vue-next'
import { batchesApi } from '@/api'
import type { CallResponse } from '@/types'
import { useFormatters } from '@/composables/useFormatters'
import CallStatusBadge from '@/components/calls/CallStatusBadge.vue'

const props = defineProps<{
  batchId: string
  title: string
  callIds: string[]
}>()

const emit = defineEmits<{ close: [] }>()

const { formatDate, formatDuration, participantLabel } = useFormatters()

const calls = ref<CallResponse[]>([])
const loading = ref(true)

onMounted(async () => {
  if (!props.callIds.length) {
    loading.value = false
    return
  }
  try {
    const { data } = await batchesApi.getCalls(props.batchId, {
      ids: props.callIds.join(','),
      pageSize: 50,
    })
    calls.value = data.items
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center">
    <div class="absolute inset-0 bg-black/50" @click="emit('close')" />
    <div class="relative bg-white rounded-2xl shadow-xl w-full max-w-2xl mx-4 max-h-[80vh] flex flex-col">
      <div class="flex items-center justify-between px-6 py-4 border-b border-gray-200">
        <h2 class="text-lg font-semibold text-gray-900 pr-4">{{ title }}</h2>
        <button class="text-gray-400 hover:text-gray-600 shrink-0" @click="emit('close')">
          <X class="w-5 h-5" />
        </button>
      </div>

      <div class="overflow-y-auto flex-1">
        <div v-if="loading" class="p-12 text-center text-gray-400">
          <Loader2 class="w-5 h-5 animate-spin inline-block mr-2" />Загрузка...
        </div>
        <div v-else-if="!calls.length" class="p-12 text-center text-gray-400">Звонки не найдены</div>
        <table v-else class="w-full text-sm">
          <thead class="bg-gray-50 border-b border-gray-200 sticky top-0">
            <tr>
              <th class="text-left px-5 py-3 font-medium text-gray-600">Участники</th>
              <th class="text-left px-5 py-3 font-medium text-gray-600">Статус</th>
              <th class="text-left px-5 py-3 font-medium text-gray-600">Длительность</th>
              <th class="text-left px-5 py-3 font-medium text-gray-600">Дата</th>
              <th class="w-10"></th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-100">
            <tr v-for="c in calls" :key="c.id" class="hover:bg-gray-50">
              <td class="px-5 py-3 font-medium text-gray-900">
                {{ participantLabel(c.managerName, c.participantNames) }}
              </td>
              <td class="px-5 py-3"><CallStatusBadge :status="c.status" /></td>
              <td class="px-5 py-3 text-gray-600">{{ formatDuration(c.durationSeconds) }}</td>
              <td class="px-5 py-3 text-gray-500">{{ formatDate(c.createdAt) }}</td>
              <td class="px-3 py-3">
                <a
                  :href="`/calls/${c.id}`"
                  target="_blank"
                  class="text-primary-600 hover:text-primary-700"
                  title="Открыть в новой вкладке"
                >
                  <ExternalLink class="w-4 h-4" />
                </a>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="px-6 py-3 border-t border-gray-200 text-sm text-gray-500">
        {{ calls.length }} {{ calls.length === 1 ? 'звонок' : calls.length < 5 ? 'звонка' : 'звонков' }}
      </div>
    </div>
  </div>
</template>
