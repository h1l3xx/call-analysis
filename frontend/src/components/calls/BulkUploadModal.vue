<script setup lang="ts">
import { ref, computed } from 'vue'
import { X, Upload, FileAudio, CheckCircle, AlertCircle, Trash2, FolderUp } from 'lucide-vue-next'
import { callsApi } from '@/api'
import type { BulkUploadItemResult } from '@/types'
import { useRouter } from 'vue-router'

const emit = defineEmits<{ close: []; uploaded: [batchId: string] }>()
const router = useRouter()

const files = ref<File[]>([])
const progress = ref(0)
const loading = ref(false)
const error = ref('')
const dragOver = ref(false)
const results = ref<BulkUploadItemResult[] | null>(null)
const resultSummary = ref<{ batchId: string; total: number; queued: number; failed: number } | null>(null)

const AUDIO_EXTENSIONS = ['wav', 'mp3', 'ogg', 'flac', 'm4a', 'webm', 'opus']

const totalSize = computed(() => {
  const bytes = files.value.reduce((sum, f) => sum + f.size, 0)
  if (bytes >= 1024 * 1024 * 1024) return (bytes / 1024 / 1024 / 1024).toFixed(1) + ' GB'
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return (bytes / 1024).toFixed(0) + ' KB'
})

function isAudioFile(f: File): boolean {
  const ext = f.name.split('.').pop()?.toLowerCase() ?? ''
  return AUDIO_EXTENSIONS.includes(ext)
}

function addFiles(newFiles: FileList | File[]) {
  const audioFiles = Array.from(newFiles).filter(isAudioFile)
  const existing = new Set(files.value.map((f) => f.name + f.size))
  for (const f of audioFiles) {
    if (!existing.has(f.name + f.size)) {
      files.value.push(f)
    }
  }
}

function handleDrop(e: DragEvent) {
  dragOver.value = false
  if (e.dataTransfer?.files) addFiles(e.dataTransfer.files)
}

function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files) addFiles(input.files)
  input.value = ''
}

function removeFile(index: number) {
  files.value.splice(index, 1)
}

function clearFiles() {
  files.value = []
}

function callTypeLabel(ct?: string): string {
  if (!ct) return ''
  if (ct === 'internal') return 'Внутренний'
  if (ct === 'external') return 'Внешний'
  return 'Неизвестный'
}

function callTypeBadgeClass(ct?: string): string {
  if (ct === 'internal') return 'bg-blue-100 text-blue-700'
  if (ct === 'external') return 'bg-purple-100 text-purple-700'
  return 'bg-gray-100 text-gray-600'
}

async function handleUpload() {
  if (!files.value.length) return
  error.value = ''
  loading.value = true
  progress.value = 0
  results.value = null
  resultSummary.value = null

  try {
    const { data } = await callsApi.bulkUpload(files.value, (pct) => {
      progress.value = pct
    })
    results.value = data.items
    resultSummary.value = { batchId: data.batchId, total: data.total, queued: data.queued, failed: data.failed }
    emit('uploaded', data.batchId)
  } catch (e: any) {
    error.value = e.response?.data?.error || 'Ошибка загрузки'
  } finally {
    loading.value = false
  }
}

function goToBatch() {
  if (resultSummary.value?.batchId) {
    router.push(`/batches/${resultSummary.value.batchId}`)
    emit('close')
  }
}
</script>

<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center">
    <div class="absolute inset-0 bg-black/50" @click="emit('close')" />
    <div class="relative bg-white rounded-2xl shadow-xl w-full max-w-2xl mx-4 max-h-[90vh] flex flex-col">
      <div class="flex items-center justify-between p-6 pb-4 border-b border-gray-100">
        <div>
          <h2 class="text-lg font-semibold text-gray-900">Массовая загрузка звонков</h2>
          <p class="text-sm text-gray-500 mt-0.5">Тип звонка и менеджер определяются автоматически по имени файла</p>
        </div>
        <button class="text-gray-400 hover:text-gray-600" @click="emit('close')">
          <X class="w-5 h-5" />
        </button>
      </div>

      <div class="flex-1 overflow-y-auto p-6 space-y-4">
        <div v-if="error" class="bg-red-50 text-red-700 text-sm rounded-lg px-4 py-3">{{ error }}</div>

        <template v-if="results">
          <div
            class="rounded-lg px-4 py-3 text-sm font-medium"
            :class="resultSummary!.failed === 0 ? 'bg-green-50 text-green-800' : 'bg-amber-50 text-amber-800'"
          >
            Загружено: {{ resultSummary!.queued }} из {{ resultSummary!.total }}
            <span v-if="resultSummary!.failed"> · Пропущено: {{ resultSummary!.failed }}</span>
          </div>

          <div class="space-y-1.5 max-h-72 overflow-y-auto">
            <div
              v-for="(item, i) in results"
              :key="i"
              class="flex items-start gap-2.5 px-3 py-2 rounded-lg text-sm"
              :class="{
                'bg-green-50': item.status === 'queued',
                'bg-red-50': item.status === 'error',
                'bg-amber-50': item.status === 'skipped',
              }"
            >
              <CheckCircle v-if="item.status === 'queued'" class="w-4 h-4 text-green-500 mt-0.5 shrink-0" />
              <AlertCircle v-else class="w-4 h-4 mt-0.5 shrink-0" :class="item.status === 'error' ? 'text-red-500' : 'text-amber-500'" />
              <div class="min-w-0 flex-1">
                <p class="font-medium text-gray-900 truncate">{{ item.filename }}</p>
                <p v-if="item.managerName" class="text-gray-600">{{ item.managerName }}</p>
                <p v-if="item.error" class="text-red-600">{{ item.error }}</p>
              </div>
              <span
                v-if="item.callType"
                class="text-[10px] font-medium px-1.5 py-0.5 rounded shrink-0"
                :class="callTypeBadgeClass(item.callType)"
              >{{ callTypeLabel(item.callType) }}</span>
              <span v-if="item.phone" class="text-xs text-gray-400 shrink-0">{{ item.phone }}</span>
            </div>
          </div>

          <div class="flex gap-2">
            <button
              class="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 bg-primary-600 text-white font-medium rounded-lg text-sm hover:bg-primary-700 transition-colors"
              @click="goToBatch"
            >
              Перейти к батчу
            </button>
            <button
              class="flex items-center justify-center gap-2 px-4 py-2.5 bg-gray-100 text-gray-700 font-medium rounded-lg text-sm hover:bg-gray-200 transition-colors"
              @click="emit('close')"
            >
              Закрыть
            </button>
          </div>
        </template>

        <template v-else>
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
            <input ref="fileInput" type="file" accept="audio/*" multiple class="hidden" @change="handleFileSelect" />
            <FolderUp class="w-10 h-10 text-gray-400 mx-auto mb-3" />
            <p class="text-sm text-gray-600 font-medium">Перетащите аудиофайлы сюда или нажмите для выбора</p>
            <p class="text-xs text-gray-400 mt-1.5">WAV, MP3, OGG, FLAC — до 100 MB каждый, до 500 файлов</p>
            <p class="text-xs text-gray-400 mt-2 font-medium">Тип звонка определяется автоматически:</p>
            <p class="text-xs text-gray-400 mt-0.5">
              <span class="inline-block px-1 py-0.5 rounded bg-purple-50 text-purple-600 font-medium">Внешний</span>
              <span class="font-mono text-[11px] ml-1">…_89248330131 , 1640 (796490)_Входящий.mp3</span>
            </p>
            <p class="text-xs text-gray-400 mt-0.5">
              <span class="inline-block px-1 py-0.5 rounded bg-blue-50 text-blue-600 font-medium">Внутренний</span>
              <span class="font-mono text-[11px] ml-1">…_1722 (796490), 1727 (796490)_Исходящий.mp3</span>
            </p>
          </div>

          <div v-if="files.length" class="space-y-2">
            <div class="flex items-center justify-between">
              <span class="text-sm font-medium text-gray-700">
                Файлов: {{ files.length }} · {{ totalSize }}
              </span>
              <button class="text-xs text-red-500 hover:text-red-700 flex items-center gap-1" @click="clearFiles">
                <Trash2 class="w-3 h-3" />
                Очистить
              </button>
            </div>
            <div class="max-h-48 overflow-y-auto space-y-1 border border-gray-200 rounded-lg p-2">
              <div
                v-for="(f, i) in files"
                :key="i"
                class="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-gray-50 group"
              >
                <FileAudio class="w-4 h-4 text-primary-500 shrink-0" />
                <span class="text-sm text-gray-800 truncate flex-1">{{ f.name }}</span>
                <span class="text-xs text-gray-400 shrink-0">{{ (f.size / 1024 / 1024).toFixed(1) }} MB</span>
                <button
                  class="text-gray-300 hover:text-red-500 opacity-0 group-hover:opacity-100 transition-opacity"
                  @click.stop="removeFile(i)"
                >
                  <X class="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
          </div>

          <div v-if="loading" class="space-y-1">
            <div class="w-full bg-gray-200 rounded-full h-2">
              <div class="bg-primary-600 h-2 rounded-full transition-all" :style="{ width: `${progress}%` }" />
            </div>
            <p class="text-xs text-gray-500 text-center">Загрузка файлов: {{ progress }}%</p>
          </div>

          <button
            :disabled="!files.length || loading"
            class="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-primary-600 text-white font-medium rounded-lg text-sm hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            @click="handleUpload"
          >
            <Upload class="w-4 h-4" />
            {{ loading ? `Загрузка ${progress}%...` : `Отправить ${files.length} файл(ов) на анализ` }}
          </button>
        </template>
      </div>
    </div>
  </div>
</template>
