<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { promptTemplatesApi } from '@/api'
import type { PromptTemplateResponse } from '@/types'
import { Loader2, Save, RotateCcw, FileText, AlertCircle, Check } from 'lucide-vue-next'

const auth = useAuthStore()
const canEdit = computed(() => auth.isClientAdmin)

const loading = ref(true)
const templates = ref<PromptTemplateResponse[]>([])
const editedContent = ref<Record<string, string>>({})
const saving = ref<Record<string, boolean>>({})
const resetting = ref<Record<string, boolean>>({})
const successMsg = ref<Record<string, string>>({})
const errorMsg = ref<Record<string, string>>({})

const placeholderInfo: Record<string, { placeholder: string; description: string }[]> = {
  system: [],
  internal_eval: [
    { placeholder: '{transcription}', description: 'Текст транскрипции звонка' },
  ],
  external_eval: [
    { placeholder: '{transcription}', description: 'Текст транскрипции звонка' },
    { placeholder: '{criteria}', description: 'Список критериев оценки из скрипта' },
    { placeholder: '{scriptName}', description: 'Название скрипта продаж' },
  ],
}

const isDirty = (id: string) => {
  const tpl = templates.value.find((t) => t.id === id)
  return tpl ? editedContent.value[id] !== tpl.content : false
}

onMounted(async () => {
  try {
    const { data } = await promptTemplatesApi.list()
    templates.value = data
    for (const t of data) {
      editedContent.value[t.id] = t.content
    }
  } catch {
    errorMsg.value['_global'] = 'Не удалось загрузить шаблоны'
  } finally {
    loading.value = false
  }
})

async function saveTemplate(id: string) {
  saving.value[id] = true
  errorMsg.value[id] = ''
  successMsg.value[id] = ''
  try {
    const { data } = await promptTemplatesApi.update(id, editedContent.value[id])
    const idx = templates.value.findIndex((t) => t.id === id)
    if (idx >= 0) templates.value[idx] = data
    editedContent.value[id] = data.content
    successMsg.value[id] = 'Сохранено'
    setTimeout(() => { successMsg.value[id] = '' }, 3000)
  } catch (e: any) {
    errorMsg.value[id] = e.response?.data?.error || e.response?.data?.message || 'Ошибка сохранения'
  } finally {
    saving.value[id] = false
  }
}

async function resetTemplate(id: string) {
  resetting.value[id] = true
  errorMsg.value[id] = ''
  successMsg.value[id] = ''
  try {
    const { data } = await promptTemplatesApi.reset(id)
    const idx = templates.value.findIndex((t) => t.id === id)
    if (idx >= 0) templates.value[idx] = data
    editedContent.value[id] = data.content
    successMsg.value[id] = 'Сброшено по умолчанию'
    setTimeout(() => { successMsg.value[id] = '' }, 3000)
  } catch (e: any) {
    errorMsg.value[id] = e.response?.data?.error || 'Ошибка сброса'
  } finally {
    resetting.value[id] = false
  }
}
</script>

<template>
  <div class="space-y-6 max-w-4xl">
    <h1 class="text-2xl font-bold text-gray-900">Промпты LLM</h1>
    <p class="text-sm text-gray-500">
      Шаблоны запросов к языковой модели для оценки звонков. Плейсхолдеры в фигурных скобках будут заменены при отправке.
    </p>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <Loader2 class="w-6 h-6 animate-spin text-gray-400" />
    </div>

    <div v-else-if="errorMsg['_global']" class="flex items-center gap-2 p-4 bg-red-50 border border-red-200 rounded-lg text-red-700 text-sm">
      <AlertCircle class="w-5 h-5 shrink-0" />
      {{ errorMsg['_global'] }}
    </div>

    <div v-else class="space-y-6">
      <div
        v-for="tpl in templates"
        :key="tpl.id"
        class="bg-white rounded-xl border border-gray-200 p-6 space-y-4"
      >
        <div class="flex items-start gap-3">
          <div class="w-10 h-10 rounded-full bg-primary-50 flex items-center justify-center shrink-0">
            <FileText class="w-5 h-5 text-primary-600" />
          </div>
          <div class="flex-1 min-w-0">
            <h2 class="text-lg font-semibold text-gray-900">{{ tpl.name }}</h2>
            <p v-if="tpl.description" class="text-sm text-gray-500 mt-0.5">{{ tpl.description }}</p>
          </div>
        </div>

        <textarea
          v-model="editedContent[tpl.id]"
          :readonly="!canEdit"
          class="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-3 text-sm font-mono text-gray-800 leading-relaxed focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent resize-y disabled:opacity-60"
          :class="{ 'bg-white': canEdit }"
          rows="14"
        />

        <!-- Placeholder legend -->
        <div v-if="placeholderInfo[tpl.id]?.length" class="flex flex-wrap gap-3">
          <div
            v-for="ph in placeholderInfo[tpl.id]"
            :key="ph.placeholder"
            class="inline-flex items-center gap-1.5 px-2.5 py-1 bg-gray-100 rounded-md"
          >
            <code class="text-xs font-mono font-semibold text-primary-700">{{ ph.placeholder }}</code>
            <span class="text-xs text-gray-500">— {{ ph.description }}</span>
          </div>
        </div>

        <!-- Status messages -->
        <div v-if="errorMsg[tpl.id]" class="flex items-center gap-2 text-sm text-red-600">
          <AlertCircle class="w-4 h-4 shrink-0" />
          {{ errorMsg[tpl.id] }}
        </div>
        <div v-if="successMsg[tpl.id]" class="flex items-center gap-2 text-sm text-green-600">
          <Check class="w-4 h-4 shrink-0" />
          {{ successMsg[tpl.id] }}
        </div>

        <!-- Actions -->
        <div v-if="canEdit" class="flex items-center gap-3">
          <button
            class="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="saving[tpl.id] || !isDirty(tpl.id)"
            @click="saveTemplate(tpl.id)"
          >
            <Loader2 v-if="saving[tpl.id]" class="w-4 h-4 animate-spin" />
            <Save v-else class="w-4 h-4" />
            Сохранить
          </button>
          <button
            class="flex items-center gap-2 px-4 py-2 text-sm font-medium text-gray-700 bg-gray-100 border border-gray-200 rounded-lg hover:bg-gray-200 transition-colors disabled:opacity-50"
            :disabled="resetting[tpl.id]"
            @click="resetTemplate(tpl.id)"
          >
            <Loader2 v-if="resetting[tpl.id]" class="w-4 h-4 animate-spin" />
            <RotateCcw v-else class="w-4 h-4" />
            Сбросить
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
