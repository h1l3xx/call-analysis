<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { promptTemplatesApi } from '@/api'
import type { PromptTemplateResponse } from '@/types'
import {
  Loader2, AlertCircle, Plus, Pencil,
  PhoneIncoming, PhoneOutgoing, Building2, Sparkles,
} from 'lucide-vue-next'
import EvaluationEditModal from '@/components/evaluations/EvaluationEditModal.vue'

const auth = useAuthStore()
const canEdit = computed(() => auth.isClientAdmin)

const loading = ref(true)
const templates = ref<PromptTemplateResponse[]>([])
const errorMsg = ref('')

const creating = ref(false)
const createName = ref('')
const createDescription = ref('')
const showCreate = ref(false)

const selectedTemplate = ref<PromptTemplateResponse | null>(null)
const showEditModal = ref(false)

const iconMap: Record<string, any> = {
  eval_internal_incoming: Building2,
  eval_internal_outgoing: Building2,
  eval_external_incoming: PhoneIncoming,
  eval_external_outgoing: PhoneOutgoing,
}

async function loadTemplates() {
  loading.value = true
  errorMsg.value = ''
  try {
    const { data } = await promptTemplatesApi.list()
    templates.value = data
  } catch {
    errorMsg.value = 'Не удалось загрузить настройки оценки'
  } finally {
    loading.value = false
  }
}

onMounted(loadTemplates)

function openEdit(tpl: PromptTemplateResponse) {
  selectedTemplate.value = tpl
  showEditModal.value = true
}

async function onModalSaved() {
  await loadTemplates()
  if (selectedTemplate.value) {
    const updated = templates.value.find(t => t.id === selectedTemplate.value!.id)
    if (updated) selectedTemplate.value = updated
  }
}

function onModalDeleted() {
  showEditModal.value = false
  selectedTemplate.value = null
  loadTemplates()
}

async function createTemplate() {
  const name = createName.value.trim()
  if (!name) return
  creating.value = true
  errorMsg.value = ''
  try {
    await promptTemplatesApi.create({
      name,
      description: createDescription.value.trim() || undefined,
    })
    createName.value = ''
    createDescription.value = ''
    showCreate.value = false
    await loadTemplates()
  } catch (e: any) {
    errorMsg.value = e.response?.data?.error || 'Не удалось создать шаблон'
  } finally {
    creating.value = false
  }
}

function formatDate(ts: number): string {
  return new Date(ts).toLocaleDateString('ru-RU', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}
</script>

<template>
  <div class="space-y-6 max-w-4xl">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Оценка звонков</h1>
        <p class="text-sm text-gray-500 mt-1">
          Шаблоны оценки определяют, по каким критериям система анализирует звонки. Кликните по строке для редактирования.
        </p>
      </div>
      <button
        v-if="canEdit"
        class="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700"
        @click="showCreate = !showCreate"
      >
        <Plus class="w-4 h-4" />
        Новая оценка
      </button>
    </div>

    <div v-if="showCreate && canEdit" class="bg-white rounded-xl border border-gray-200 p-5 space-y-3">
      <h2 class="text-base font-semibold text-gray-900">Создать шаблон оценки</h2>
      <p class="text-sm text-gray-500">Шаблон не привязан к типу звонка и может назначаться в политиках для любого отдела/направления.</p>
      <input
        v-model="createName"
        type="text"
        placeholder="Название шаблона"
        class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
      />
      <textarea
        v-model="createDescription"
        rows="2"
        placeholder="Описание (необязательно)"
        class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm resize-none"
      />
      <div class="flex gap-2">
        <button
          class="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700 disabled:opacity-50"
          :disabled="creating || !createName.trim()"
          @click="createTemplate"
        >
          <Loader2 v-if="creating" class="w-4 h-4 animate-spin" />
          <Plus v-else class="w-4 h-4" />
          Создать
        </button>
        <button
          class="px-4 py-2 text-sm font-medium text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200"
          @click="showCreate = false"
        >
          Отмена
        </button>
      </div>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <Loader2 class="w-6 h-6 animate-spin text-gray-400" />
    </div>

    <div v-else-if="errorMsg" class="flex items-center gap-2 p-4 bg-red-50 border border-red-200 rounded-lg text-red-700 text-sm">
      <AlertCircle class="w-5 h-5 shrink-0" />
      {{ errorMsg }}
    </div>

    <div v-else class="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <table class="w-full">
        <thead>
          <tr class="border-b border-gray-100 bg-gray-50">
            <th class="text-left text-xs font-medium text-gray-500 uppercase tracking-wide px-5 py-3">Название</th>
            <th class="text-left text-xs font-medium text-gray-500 uppercase tracking-wide px-5 py-3">Тип</th>
            <th class="text-left text-xs font-medium text-gray-500 uppercase tracking-wide px-5 py-3 hidden sm:table-cell">Описание</th>
            <th class="text-left text-xs font-medium text-gray-500 uppercase tracking-wide px-5 py-3 hidden md:table-cell">Обновлено</th>
            <th class="text-right text-xs font-medium text-gray-500 uppercase tracking-wide px-5 py-3 w-20"></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="tpl in templates"
            :key="tpl.id"
            class="border-b border-gray-50 hover:bg-gray-50 cursor-pointer transition-colors"
            @click="openEdit(tpl)"
          >
            <td class="px-5 py-3.5">
              <div class="flex items-center gap-2.5">
                <div class="w-8 h-8 rounded-full bg-primary-50 flex items-center justify-center shrink-0">
                  <component :is="iconMap[tpl.id] || Sparkles" class="w-4 h-4 text-primary-600" />
                </div>
                <span class="text-sm font-medium text-gray-900">{{ tpl.name }}</span>
              </div>
            </td>
            <td class="px-5 py-3.5">
              <span
                class="text-xs px-2 py-0.5 rounded-full"
                :class="tpl.isSystem ? 'bg-gray-100 text-gray-600' : 'bg-primary-100 text-primary-700'"
              >{{ tpl.isSystem ? 'Системная' : 'Польз.' }}</span>
            </td>
            <td class="px-5 py-3.5 hidden sm:table-cell">
              <span class="text-sm text-gray-500 truncate block max-w-xs">{{ tpl.description || '—' }}</span>
            </td>
            <td class="px-5 py-3.5 hidden md:table-cell">
              <span class="text-sm text-gray-500">{{ formatDate(tpl.updatedAt) }}</span>
            </td>
            <td class="px-5 py-3.5 text-right">
              <Pencil class="w-4 h-4 text-gray-400 inline-block" />
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <EvaluationEditModal
      :visible="showEditModal"
      :template="selectedTemplate"
      :can-edit="canEdit"
      @close="showEditModal = false"
      @saved="onModalSaved"
      @deleted="onModalDeleted"
    />
  </div>
</template>
