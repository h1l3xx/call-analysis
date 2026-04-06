<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { callsApi } from '@/api'
import type { CallResponse } from '@/types'
import StatsCards from '@/components/dashboard/StatsCards.vue'
import RecentCallsList from '@/components/dashboard/RecentCallsList.vue'

const loading = ref(true)
const recentCalls = ref<CallResponse[]>([])
const stats = ref({ total: 0, processing: 0, done: 0, failed: 0, noSpeech: 0 })

onMounted(async () => {
  try {
    const [callsRes, statsRes] = await Promise.all([
      callsApi.list({ page: 1, pageSize: 10 }),
      callsApi.stats(),
    ])
    recentCalls.value = callsRes.data.items
    stats.value = statsRes.data
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
