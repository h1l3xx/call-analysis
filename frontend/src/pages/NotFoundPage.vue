<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { FileQuestion } from 'lucide-vue-next'
</script>

<template>
  <div class="flex flex-col items-center justify-center min-h-[60vh] text-center">
    <FileQuestion class="w-16 h-16 text-gray-400 mb-4" />
    <h1 class="text-2xl font-bold text-gray-900 mb-2">Страница не найдена</h1>
    <p class="text-gray-500 mb-6">Запрашиваемая страница не существует</p>
    <RouterLink to="/" class="px-4 py-2 bg-primary-600 text-white rounded-lg text-sm hover:bg-primary-700">
      На главную
    </RouterLink>
  </div>
</template>
