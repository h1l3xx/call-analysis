<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { promptTemplatesApi } from '@/api'
import type { PromptTemplateResponse } from '@/types'
import {
  Loader2, Save, RotateCcw, AlertCircle, Check,
  Sparkles, PhoneIncoming, PhoneOutgoing, Wand2,
} from 'lucide-vue-next'

const auth = useAuthStore()
const canEdit = computed(() => auth.isClientAdmin)

const loading = ref(true)
const templates = ref<PromptTemplateResponse[]>([])
const editedContent = ref<Record<string, string>>({})
const saving = ref<Record<string, boolean>>({})
const resetting = ref<Record<string, boolean>>({})
const successMsg = ref<Record<string, string>>({})
const errorMsg = ref<Record<string, string>>({})

const suggestionInput = ref<Record<string, string>>({})
const generating = ref<Record<string, boolean>>({})
const suggestions = ref<Record<string, string[]>>({})
const selectedSuggestion = ref<Record<string, number>>({})

const templateMeta: Record<string, { icon: any; hint: string }> = {
  internal_eval: {
    icon: PhoneIncoming,
    hint: 'Например: «Оценивать деловой тон, наличие конкретных договорённостей и соблюдение субординации»',
  },
  external_eval: {
    icon: PhoneOutgoing,
    hint: 'Например: «Оценивать вежливость, выявление потребности и работу с возражениями»',
  },
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
    errorMsg.value['_global'] = 'Не удалось загрузить настройки оценки'
  } finally {
    loading.value = false
  }
})

async function generateSuggestions(id: string) {
  const desc = suggestionInput.value[id]?.trim()
  if (!desc) return

  generating.value[id] = true
  errorMsg.value[id] = ''
  suggestions.value[id] = []
  selectedSuggestion.value[id] = -1
  try {
    const { data } = await promptTemplatesApi.suggest(id, desc)
    suggestions.value[id] = data.suggestions
  } catch (e: any) {
    errorMsg.value[id] = e.response?.data?.error || 'Не удалось сгенерировать варианты'
  } finally {
    generating.value[id] = false
  }
}

function applySuggestion(id: string) {
  const idx = selectedSuggestion.value[id]
  const list = suggestions.value[id]
  if (idx >= 0 && list?.[idx]) {
    editedContent.value[id] = list[idx]
    suggestions.value[id] = []
    selectedSuggestion.value[id] = -1
    suggestionInput.value[id] = ''
  }
}

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
    <div>
      <h1 class="text-2xl font-bold text-gray-900">Оценка звонков</h1>
      <p class="text-sm text-gray-500 mt-1">
        Настройте, как система будет оценивать звонки. Опишите свои требования — и мы подберём подходящие инструкции.
      </p>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <Loader2 class="w-6 h-6 animate-spin text-gray-400" />
    </div>

    <div v-else-if="errorMsg['_global']" class="flex items-center gap-2 p-4 bg-red-50 border border-red-200 rounded-lg text-red-700 text-sm">
      <AlertCircle class="w-5 h-5 shrink-0" />
      {{ errorMsg['_global'] }}
    </div>

    <div v-else class="space-y-8">
      <div
        v-for="tpl in templates"
        :key="tpl.id"
        class="bg-white rounded-xl border border-gray-200 overflow-hidden"
      >
        <!-- Header -->
        <div class="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
          <div class="w-10 h-10 rounded-full bg-primary-50 flex items-center justify-center shrink-0">
            <component :is="templateMeta[tpl.id]?.icon || Sparkles" class="w-5 h-5 text-primary-600" />
          </div>
          <div>
            <h2 class="text-lg font-semibold text-gray-900">{{ tpl.name }}</h2>
            <p v-if="tpl.description" class="text-sm text-gray-500">{{ tpl.description }}</p>
          </div>
        </div>

        <div class="p-6 space-y-5">
          <!-- Current instructions -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">Текущие инструкции</label>
            <textarea
              v-model="editedContent[tpl.id]"
              :readonly="!canEdit"
              class="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-3 text-sm text-gray-800 leading-relaxed focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent resize-y"
              :class="{ 'bg-white': canEdit }"
              rows="8"
            />
          </div>

          <!-- AI Assistant (CLIENT_ADMIN only) -->
          <div v-if="canEdit" class="bg-gradient-to-br from-violet-50 to-indigo-50 rounded-xl p-5 space-y-4 border border-violet-100">
            <div class="flex items-center gap-2">
              <Wand2 class="w-5 h-5 text-violet-600" />
              <h3 class="text-sm font-semibold text-violet-900">Помощник</h3>
            </div>

            <div class="flex gap-2">
              <input
                v-model="suggestionInput[tpl.id]"
                type="text"
                :placeholder="templateMeta[tpl.id]?.hint || 'Опишите, как хотите оценивать звонки...'"
                class="flex-1 rounded-lg border border-violet-200 bg-white px-4 py-2.5 text-sm text-gray-800 placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-violet-400 focus:border-transparent"
                @keydown.enter="generateSuggestions(tpl.id)"
              />
              <button
                class="flex items-center gap-2 px-4 py-2.5 text-sm font-medium text-white bg-violet-600 rounded-lg hover:bg-violet-700 transition-colors disabled:opacity-50 shrink-0"
                :disabled="generating[tpl.id] || !suggestionInput[tpl.id]?.trim()"
                @click="generateSuggestions(tpl.id)"
              >
                <Loader2 v-if="generating[tpl.id]" class="w-4 h-4 animate-spin" />
                <Sparkles v-else class="w-4 h-4" />
                Сгенерировать
              </button>
            </div>

            <!-- Suggestions -->
            <div v-if="suggestions[tpl.id]?.length" class="space-y-3">
              <p class="text-xs font-medium text-violet-700 uppercase tracking-wide">Выберите вариант</p>
              <div
                v-for="(s, idx) in suggestions[tpl.id]"
                :key="idx"
                class="relative rounded-lg border-2 p-4 cursor-pointer transition-all text-sm text-gray-700 leading-relaxed whitespace-pre-line"
                :class="selectedSuggestion[tpl.id] === idx
                  ? 'border-violet-500 bg-violet-50'
                  : 'border-gray-200 bg-white hover:border-violet-300'"
                @click="selectedSuggestion[tpl.id] = idx"
              >
                <div class="absolute top-3 right-3 w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0"
                     :class="selectedSuggestion[tpl.id] === idx ? 'border-violet-500 bg-violet-500' : 'border-gray-300'"
                >
                  <div v-if="selectedSuggestion[tpl.id] === idx" class="w-2 h-2 rounded-full bg-white" />
                </div>
                {{ s }}
              </div>

              <button
                class="flex items-center gap-2 px-4 py-2 text-sm font-medium text-violet-700 bg-violet-100 border border-violet-200 rounded-lg hover:bg-violet-200 transition-colors disabled:opacity-50"
                :disabled="selectedSuggestion[tpl.id] == null || selectedSuggestion[tpl.id] < 0"
                @click="applySuggestion(tpl.id)"
              >
                <Check class="w-4 h-4" />
                Применить выбранный вариант
              </button>
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
          <div v-if="canEdit" class="flex items-center gap-3 pt-2">
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
              Сбросить по умолчанию
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
