<script setup lang="ts">
import { Phone, Clock, CheckCircle2, AlertTriangle } from 'lucide-vue-next'

defineProps<{
  total: number
  processing: number
  done: number
  failed: number
}>()
</script>

<template>
  <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
    <div class="bg-white rounded-xl border border-gray-200 p-5">
      <div class="flex items-center gap-3">
        <div class="p-2.5 rounded-lg bg-primary-50">
          <Phone class="w-5 h-5 text-primary-600" />
        </div>
        <div>
          <p class="text-2xl font-bold text-gray-900">{{ total }}</p>
          <p class="text-sm text-gray-500">Всего звонков</p>
        </div>
      </div>
    </div>
    <div class="bg-white rounded-xl border border-gray-200 p-5">
      <div class="flex items-center gap-3">
        <div class="p-2.5 rounded-lg bg-yellow-50">
          <Clock class="w-5 h-5 text-warning-500" />
        </div>
        <div>
          <p class="text-2xl font-bold text-gray-900">{{ processing }}</p>
          <p class="text-sm text-gray-500">В обработке</p>
        </div>
      </div>
    </div>
    <div class="bg-white rounded-xl border border-gray-200 p-5">
      <div class="flex items-center gap-3">
        <div class="p-2.5 rounded-lg bg-green-50">
          <CheckCircle2 class="w-5 h-5 text-success-500" />
        </div>
        <div>
          <p class="text-2xl font-bold text-gray-900">{{ done }}</p>
          <p class="text-sm text-gray-500">Завершено</p>
        </div>
      </div>
    </div>
    <div class="bg-white rounded-xl border border-gray-200 p-5">
      <div class="flex items-center gap-3">
        <div class="p-2.5 rounded-lg bg-red-50">
          <AlertTriangle class="w-5 h-5 text-danger-500" />
        </div>
        <div>
          <p class="text-2xl font-bold text-gray-900">{{ failed }}</p>
          <p class="text-sm text-gray-500">Ошибки</p>
        </div>
      </div>
    </div>
  </div>
</template>
