<script setup lang="ts">
import { ref } from 'vue'
import { X, Building2 } from 'lucide-vue-next'
import { adminApi } from '@/api'

const emit = defineEmits<{ close: []; created: [] }>()

const form = ref({
  slug: '',
  name: '',
  planId: '',
  adminEmail: '',
  adminPassword: '',
  adminFullName: '',
})
const loading = ref(false)
const error = ref('')

async function handleCreate() {
  error.value = ''
  loading.value = true
  try {
    await adminApi.createTenant(form.value)
    emit('created')
    emit('close')
  } catch (e: any) {
    error.value = e.response?.data?.error || 'Ошибка создания тенанта'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center">
    <div class="absolute inset-0 bg-black/50" @click="emit('close')" />
    <div class="relative bg-white rounded-2xl shadow-xl w-full max-w-lg mx-4 p-6">
      <div class="flex items-center justify-between mb-5">
        <div class="flex items-center gap-2">
          <Building2 class="w-5 h-5 text-primary-600" />
          <h2 class="text-lg font-semibold text-gray-900">Новый тенант</h2>
        </div>
        <button class="text-gray-400 hover:text-gray-600" @click="emit('close')">
          <X class="w-5 h-5" />
        </button>
      </div>

      <div v-if="error" class="mb-4 bg-red-50 text-red-700 text-sm rounded-lg px-4 py-3">{{ error }}</div>

      <form @submit.prevent="handleCreate" class="space-y-4">
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Slug</label>
            <input v-model="form.slug" required placeholder="my-clinic" class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 outline-none" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Название</label>
            <input v-model="form.name" required placeholder="Моя Клиника" class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 outline-none" />
          </div>
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Plan ID</label>
          <input v-model="form.planId" required placeholder="UUID плана" class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 outline-none" />
        </div>

        <hr class="border-gray-200" />
        <p class="text-sm text-gray-500">Администратор тенанта</p>

        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">ФИО</label>
          <input v-model="form.adminFullName" required class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 outline-none" />
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Email</label>
            <input v-model="form.adminEmail" type="email" required class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 outline-none" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Пароль</label>
            <input v-model="form.adminPassword" type="password" required class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 outline-none" />
          </div>
        </div>

        <button
          type="submit"
          :disabled="loading"
          class="w-full px-4 py-2.5 bg-primary-600 text-white font-medium rounded-lg text-sm hover:bg-primary-700 disabled:opacity-50 transition-colors"
        >
          {{ loading ? 'Создание...' : 'Создать тенант' }}
        </button>
      </form>
    </div>
  </div>
</template>
