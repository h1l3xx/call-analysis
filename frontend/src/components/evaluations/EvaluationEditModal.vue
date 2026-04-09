<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import {
  X, Save, Loader2, RotateCcw, Trash2, AlertCircle, Check,
  Sparkles, Wand2,
} from 'lucide-vue-next'
import { promptTemplatesApi } from '@/api'
import type { PromptTemplateResponse } from '@/types'

const props = defineProps<{
  visible: boolean
  template: PromptTemplateResponse | null
  canEdit: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved'): void
  (e: 'deleted'): void
}>()

const editedContent = ref('')
const saving = ref(false)
const resetting = ref(false)
const successMsg = ref('')
const errorMsg = ref('')

const suggestionInput = ref('')
const generating = ref(false)
const suggestions = ref<string[]>([])
const selectedSuggestion = ref(-1)

const isDirty = computed(() => {
  if (!props.template) return false
  return editedContent.value !== props.template.content
})

watch(
  () => [props.visible, props.template],
  () => {
    if (props.visible && props.template) {
      editedContent.value = props.template.content
      successMsg.value = ''
      errorMsg.value = ''
      suggestions.value = []
      selectedSuggestion.value = -1
      suggestionInput.value = ''
    }
  },
  { immediate: true },
)

async function onSave() {
  if (!props.template) return
  saving.value = true
  errorMsg.value = ''
  successMsg.value = ''
  try {
    await promptTemplatesApi.update(props.template.id, editedContent.value)
    successMsg.value = 'Сохранено'
    setTimeout(() => { successMsg.value = '' }, 3000)
    emit('saved')
  } catch (e: any) {
    errorMsg.value = e.response?.data?.error || e.response?.data?.message || 'Ошибка сохранения'
  } finally {
    saving.value = false
  }
}

async function onReset() {
  if (!props.template) return
  resetting.value = true
  errorMsg.value = ''
  successMsg.value = ''
  try {
    const { data } = await promptTemplatesApi.reset(props.template.id)
    editedContent.value = data.content
    successMsg.value = 'Сброшено по умолчанию'
    setTimeout(() => { successMsg.value = '' }, 3000)
    emit('saved')
  } catch (e: any) {
    errorMsg.value = e.response?.data?.error || 'Ошибка сброса'
  } finally {
    resetting.value = false
  }
}

async function onDelete() {
  if (!props.template) return
  if (!confirm('Удалить шаблон оценки?')) return
  errorMsg.value = ''
  try {
    await promptTemplatesApi.remove(props.template.id)
    emit('deleted')
  } catch (e: any) {
    errorMsg.value = e.response?.data?.error || 'Не удалось удалить шаблон'
  }
}

async function generateSuggestions() {
  if (!props.template) return
  const desc = suggestionInput.value.trim()
  if (!desc) return

  generating.value = true
  errorMsg.value = ''
  suggestions.value = []
  selectedSuggestion.value = -1
  try {
    const { data } = await promptTemplatesApi.suggest(props.template.id, desc)
    suggestions.value = data.suggestions
  } catch (e: any) {
    errorMsg.value = e.response?.data?.error || 'Не удалось сгенерировать варианты'
  } finally {
    generating.value = false
  }
}

function applySuggestion() {
  const idx = selectedSuggestion.value
  if (idx >= 0 && suggestions.value[idx]) {
    editedContent.value = suggestions.value[idx]
    suggestions.value = []
    selectedSuggestion.value = -1
    suggestionInput.value = ''
  }
}
</script>

<template>
  <teleport to="body">
    <div
      v-if="visible && template"
      class="fixed inset-0 z-50 flex items-center justify-center"
    >
      <div class="absolute inset-0 bg-black/40" @click="emit('close')" />
      <div class="relative bg-white rounded-2xl shadow-xl w-full max-w-3xl mx-4 max-h-[90vh] flex flex-col">
        <div class="flex items-center justify-between px-6 py-4 border-b border-gray-100 shrink-0">
          <div class="flex-1 min-w-0">
            <h2 class="text-lg font-semibold text-gray-900 truncate">{{ template.name }}</h2>
            <p v-if="template.description" class="text-sm text-gray-500 mt-0.5 truncate">{{ template.description }}</p>
          </div>
          <div class="flex items-center gap-2 shrink-0 ml-4">
            <span
              class="text-xs px-2 py-0.5 rounded-full"
              :class="template.isSystem ? 'bg-gray-100 text-gray-600' : 'bg-primary-100 text-primary-700'"
            >{{ template.isSystem ? 'Системная' : 'Пользовательская' }}</span>
            <button class="p-1.5 rounded-lg hover:bg-gray-100 text-gray-400" @click="emit('close')">
              <X class="w-5 h-5" />
            </button>
          </div>
        </div>

        <div class="flex-1 overflow-y-auto p-6 space-y-5">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">Инструкции оценки</label>
            <textarea
              v-model="editedContent"
              :readonly="!canEdit"
              class="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-3 text-sm text-gray-800 leading-relaxed focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent resize-y"
              :class="{ 'bg-white': canEdit }"
              rows="10"
            />
          </div>

          <div v-if="canEdit" class="bg-gradient-to-br from-violet-50 to-indigo-50 rounded-xl p-5 space-y-4 border border-violet-100">
            <div class="flex items-center gap-2">
              <Wand2 class="w-5 h-5 text-violet-600" />
              <h3 class="text-sm font-semibold text-violet-900">Помощник</h3>
            </div>

            <div class="flex gap-2">
              <input
                v-model="suggestionInput"
                type="text"
                placeholder="Опишите, как хотите оценивать звонки..."
                class="flex-1 rounded-lg border border-violet-200 bg-white px-4 py-2.5 text-sm text-gray-800 placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-violet-400 focus:border-transparent"
                @keydown.enter="generateSuggestions"
              />
              <button
                class="flex items-center gap-2 px-4 py-2.5 text-sm font-medium text-white bg-violet-600 rounded-lg hover:bg-violet-700 transition-colors disabled:opacity-50 shrink-0"
                :disabled="generating || !suggestionInput.trim()"
                @click="generateSuggestions"
              >
                <Loader2 v-if="generating" class="w-4 h-4 animate-spin" />
                <Sparkles v-else class="w-4 h-4" />
                Сгенерировать
              </button>
            </div>

            <div v-if="suggestions.length" class="space-y-3">
              <p class="text-xs font-medium text-violet-700 uppercase tracking-wide">Выберите вариант</p>
              <div
                v-for="(s, idx) in suggestions"
                :key="idx"
                class="relative rounded-lg border-2 p-4 cursor-pointer transition-all text-sm text-gray-700 leading-relaxed whitespace-pre-line"
                :class="selectedSuggestion === idx
                  ? 'border-violet-500 bg-violet-50'
                  : 'border-gray-200 bg-white hover:border-violet-300'"
                @click="selectedSuggestion = idx"
              >
                <div
                  class="absolute top-3 right-3 w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0"
                  :class="selectedSuggestion === idx ? 'border-violet-500 bg-violet-500' : 'border-gray-300'"
                >
                  <div v-if="selectedSuggestion === idx" class="w-2 h-2 rounded-full bg-white" />
                </div>
                {{ s }}
              </div>
              <button
                class="flex items-center gap-2 px-4 py-2 text-sm font-medium text-violet-700 bg-violet-100 border border-violet-200 rounded-lg hover:bg-violet-200 transition-colors disabled:opacity-50"
                :disabled="selectedSuggestion < 0"
                @click="applySuggestion"
              >
                <Check class="w-4 h-4" />
                Применить выбранный вариант
              </button>
            </div>
          </div>

          <div v-if="errorMsg" class="flex items-center gap-2 text-sm text-red-600">
            <AlertCircle class="w-4 h-4 shrink-0" />
            {{ errorMsg }}
          </div>
          <div v-if="successMsg" class="flex items-center gap-2 text-sm text-green-600">
            <Check class="w-4 h-4 shrink-0" />
            {{ successMsg }}
          </div>
        </div>

        <div v-if="canEdit" class="flex items-center justify-between px-6 py-4 border-t border-gray-100 shrink-0">
          <div class="flex items-center gap-2">
            <button
              v-if="!template.isSystem"
              class="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-red-700 bg-red-50 border border-red-200 rounded-lg hover:bg-red-100"
              @click="onDelete"
            >
              <Trash2 class="w-3.5 h-3.5" />
              Удалить
            </button>
            <button
              v-if="template.isSystem"
              class="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-gray-700 bg-gray-100 border border-gray-200 rounded-lg hover:bg-gray-200 disabled:opacity-50"
              :disabled="resetting"
              @click="onReset"
            >
              <Loader2 v-if="resetting" class="w-3.5 h-3.5 animate-spin" />
              <RotateCcw v-else class="w-3.5 h-3.5" />
              Сбросить
            </button>
          </div>
          <div class="flex items-center gap-2">
            <button
              class="px-4 py-2 text-sm font-medium text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200"
              @click="emit('close')"
            >
              Закрыть
            </button>
            <button
              class="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700 disabled:opacity-50"
              :disabled="saving || !isDirty"
              @click="onSave"
            >
              <Loader2 v-if="saving" class="w-4 h-4 animate-spin" />
              <Save v-else class="w-4 h-4" />
              Сохранить
            </button>
          </div>
        </div>
      </div>
    </div>
  </teleport>
</template>
