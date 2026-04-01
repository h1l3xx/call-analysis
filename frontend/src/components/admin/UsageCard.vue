<script setup lang="ts">
import { computed } from 'vue'
import type { TenantUsageResponse } from '@/types'
import { BarChart3 } from 'lucide-vue-next'

const props = defineProps<{ usage: TenantUsageResponse }>()

const usagePct = computed(() => {
  if (!props.usage.minutesLimit) return 0
  return Math.min(100, Math.round((props.usage.minutesUsed / props.usage.minutesLimit) * 100))
})

const barColor = computed(() => {
  if (usagePct.value >= 90) return 'bg-red-500'
  if (usagePct.value >= 70) return 'bg-yellow-500'
  return 'bg-primary-500'
})
</script>

<template>
  <div class="bg-white rounded-xl border border-gray-200 p-5 space-y-3">
    <div class="flex items-center gap-2">
      <BarChart3 class="w-5 h-5 text-primary-600" />
      <h3 class="text-base font-semibold text-gray-900">{{ usage.tenantName }}</h3>
    </div>

    <div class="flex items-center justify-between text-sm">
      <span class="text-gray-600">План: {{ usage.planName }}</span>
      <span class="text-gray-500">{{ usage.periodStart }} — {{ usage.periodEnd }}</span>
    </div>

    <div>
      <div class="flex justify-between text-sm mb-1">
        <span class="text-gray-700">{{ usage.minutesUsed }} / {{ usage.minutesLimit }} мин</span>
        <span class="text-gray-500">{{ usagePct }}%</span>
      </div>
      <div class="w-full bg-gray-200 rounded-full h-2.5">
        <div :class="['h-2.5 rounded-full transition-all', barColor]" :style="{ width: `${usagePct}%` }" />
      </div>
    </div>
  </div>
</template>
