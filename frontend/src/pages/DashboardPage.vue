<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { callsApi } from '@/api'
import type { CallResponse } from '@/types'
import StatsCards from '@/components/dashboard/StatsCards.vue'
import RecentCallsList from '@/components/dashboard/RecentCallsList.vue'

const loading = ref(true)
const recentCalls = ref<CallResponse[]>([])
const stats = ref({ total: 0, processing: 0, done: 0, failed: 0 })

onMounted(async () => {
  try {
    const { data } = await callsApi.list({ page: 1, pageSize: 10 })
    recentCalls.value = data.items
    stats.value.total = data.total

    const counts = data.items.reduce(
      (acc, c) => {
        if (['queued', 'processing', 'analyzing'].includes(c.status)) acc.processing++
        else if (['done', 'transcribed_only'].includes(c.status)) acc.done++
        else if (c.status === 'failed') acc.failed++
        return acc
      },
      { processing: 0, done: 0, failed: 0 },
    )
    stats.value = { ...stats.value, ...counts }
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="space-y-6">
    <h1 class="text-2xl font-bold text-gray-900">Дашборд</h1>
    <StatsCards v-bind="stats" />
    <RecentCallsList :calls="recentCalls" :loading="loading" />
  </div>
</template>
