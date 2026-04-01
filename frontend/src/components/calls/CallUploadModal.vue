<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { X, Upload, FileAudio } from 'lucide-vue-next'
import { callsApi, managersApi, scriptsApi } from '@/api'
import type { ManagerResponse, ScriptResponse } from '@/types'

const emit = defineEmits<{ close: []; uploaded: [] }>()

const managers = ref<ManagerResponse[]>([])
const scripts = ref<ScriptResponse[]>([])
const selectedManager = ref('')
const selectedScript = ref('')
const file = ref<File | null>(null)
const progress = ref(0)
const loading = ref(false)
const error = ref('')
const dragOver = ref(false)

onMounted(async () => {
  const [m, s] = await Promise.all([
    managersApi.list({ pageSize: 100 }),
    scriptsApi.list({ pageSize: 100, isActive: true }),
  ])
  managers.value = m.data.items
  scripts.value = s.data.items
  if (managers.value.length) selectedManager.value = managers.value[0].id
  if (scripts.value.length) selectedScript.value = scripts.value[0].id
})

function handleDrop(e: DragEvent) {
  dragOver.value = false
  const f = e.dataTransfer?.files[0]
  if (f) file.value = f
}

function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files?.[0]) file.value = input.files[0]
}

async function handleUpload() {
  if (!file.value || !selectedManager.value || !selectedScript.value) return
  error.value = ''
  loading.value = true
  progress.value = 0
  try {
    await callsApi.upload(selectedManager.value, selectedScript.value, file.value, (pct) => {
      progress.value = pct
    })
    emit('uploaded')
    emit('close')
  } catch (e: any) {
    error.value = e.response?.data?.error || 'Ошибка загрузки'
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
        <h2 class="text-lg font-semibold text-gray-900">Загрузить звонок</h2>
        <button class="text-gray-400 hover:text-gray-600" @click="emit('close')">
          <X class="w-5 h-5" />
        </button>
      </div>

      <div v-if="error" class="mb-4 bg-red-50 text-red-700 text-sm rounded-lg px-4 py-3">{{ error }}</div>

      <div class="space-y-4">
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1.5">Менеджер</label>
          <select
            v-model="selectedManager"
            class="appearance-auto bg-white w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          >
            <option v-for="m in managers" :key="m.id" :value="m.id">{{ m.fullName }}</option>
          </select>
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1.5">Скрипт оценки</label>
          <select
            v-model="selectedScript"
            class="appearance-auto bg-white w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          >
            <option v-for="s in scripts" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
        </div>

        <div
          :class="[
            'border-2 border-dashed rounded-xl p-8 text-center transition-colors cursor-pointer',
            dragOver ? 'border-primary-500 bg-primary-50' : 'border-gray-300 hover:border-gray-400',
          ]"
          @dragover.prevent="dragOver = true"
          @dragleave="dragOver = false"
          @drop.prevent="handleDrop"
          @click="($refs.fileInput as HTMLInputElement).click()"
        >
          <input ref="fileInput" type="file" accept="audio/*" class="hidden" @change="handleFileSelect" />
          <div v-if="file" class="flex items-center justify-center gap-2">
            <FileAudio class="w-5 h-5 text-primary-600" />
            <span class="text-sm font-medium text-gray-900">{{ file.name }}</span>
            <span class="text-xs text-gray-500">({{ (file.size / 1024 / 1024).toFixed(1) }} MB)</span>
          </div>
          <div v-else>
            <Upload class="w-8 h-8 text-gray-400 mx-auto mb-2" />
            <p class="text-sm text-gray-600">Перетащите аудиофайл сюда или нажмите для выбора</p>
            <p class="text-xs text-gray-400 mt-1">WAV, MP3, OGG, FLAC — до 200 MB</p>
          </div>
        </div>

        <div v-if="loading" class="w-full bg-gray-200 rounded-full h-2">
          <div class="bg-primary-600 h-2 rounded-full transition-all" :style="{ width: `${progress}%` }" />
        </div>

        <button
          :disabled="!file || !selectedManager || !selectedScript || loading"
          class="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-primary-600 text-white font-medium rounded-lg text-sm hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          @click="handleUpload"
        >
          <Upload class="w-4 h-4" />
          {{ loading ? `Загрузка ${progress}%...` : 'Отправить на анализ' }}
        </button>
      </div>
    </div>
  </div>
</template>
