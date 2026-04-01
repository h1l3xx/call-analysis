<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { ArrowLeft, Save } from 'lucide-vue-next'
import { scriptsApi } from '@/api'
import type { ScriptDetailResponse, CreateCriterionRequest } from '@/types'
import { useAuthStore } from '@/stores/auth'
import CriteriaEditor from '@/components/scripts/CriteriaEditor.vue'

const route = useRoute()
const auth = useAuthStore()

const script = ref<ScriptDetailResponse | null>(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const success = ref('')

const editName = ref('')
const editCallType = ref('')
const editDescription = ref('')
const editIsActive = ref(true)
const editCriteria = ref<CreateCriterionRequest[]>([])
const criteriaEditorRef = ref<InstanceType<typeof CriteriaEditor> | null>(null)

const canEdit = computed(() => auth.isClientAdmin)

onMounted(async () => {
  try {
    const { data } = await scriptsApi.get(route.params.id as string)
    script.value = data
    editName.value = data.name
    editCallType.value = data.callType
    editDescription.value = data.description || ''
    editIsActive.value = data.isActive
    editCriteria.value = data.criteria.map((c) => ({
      orderNum: c.orderNum,
      name: c.name,
      description: c.description,
      groupType: c.groupType,
      weight: c.weight,
      scoringType: c.scoringType,
    }))
  } finally {
    loading.value = false
  }
})

async function handleSave() {
  if (!script.value) return

  if (!editName.value.trim()) {
    error.value = 'Название скрипта обязательно'
    return
  }
  if (!editCallType.value.trim()) {
    error.value = 'Тип звонка обязателен'
    return
  }
  if (criteriaEditorRef.value && !criteriaEditorRef.value.validate()) {
    error.value = 'Заполните все обязательные поля критериев'
    return
  }

  saving.value = true
  error.value = ''
  success.value = ''
  try {
    const { data } = await scriptsApi.update(script.value.id, {
      name: editName.value,
      callType: editCallType.value,
      description: editDescription.value || undefined,
      isActive: editIsActive.value,
      criteria: editCriteria.value,
    })
    script.value = data
    success.value = 'Сохранено'
    setTimeout(() => (success.value = ''), 3000)
  } catch (e: any) {
    error.value = e.response?.data?.error || 'Ошибка сохранения'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="space-y-5">
    <div class="flex items-center gap-3">
      <RouterLink to="/scripts" class="p-2 rounded-lg hover:bg-gray-100 text-gray-500 transition-colors">
        <ArrowLeft class="w-5 h-5" />
      </RouterLink>
      <h1 class="text-2xl font-bold text-gray-900">{{ script?.name || 'Скрипт' }}</h1>
    </div>

    <div v-if="loading" class="text-center py-12 text-gray-400">Загрузка...</div>

    <template v-else-if="script">
      <div v-if="error" class="bg-red-50 text-red-700 text-sm rounded-lg px-4 py-3">{{ error }}</div>
      <div v-if="success" class="bg-green-50 text-green-700 text-sm rounded-lg px-4 py-3">{{ success }}</div>

      <div class="bg-white rounded-xl border border-gray-200 p-5 space-y-4">
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1.5">Название</label>
            <input
              v-model="editName"
              :disabled="!canEdit"
              class="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 outline-none disabled:bg-gray-50"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1.5">Тип звонка</label>
            <input
              v-model="editCallType"
              :disabled="!canEdit"
              class="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 outline-none disabled:bg-gray-50"
            />
          </div>
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1.5">Описание</label>
          <textarea
            v-model="editDescription"
            :disabled="!canEdit"
            rows="3"
            class="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 outline-none resize-none disabled:bg-gray-50"
          />
        </div>

        <label v-if="canEdit" class="flex items-center gap-2">
          <input v-model="editIsActive" type="checkbox" class="rounded border-gray-300 text-primary-600 focus:ring-primary-500" />
          <span class="text-sm text-gray-700">Активен</span>
        </label>
      </div>

      <div class="bg-white rounded-xl border border-gray-200 p-5">
        <CriteriaEditor v-if="canEdit" ref="criteriaEditorRef" v-model="editCriteria" />
        <div v-else>
          <h3 class="text-sm font-medium text-gray-700 mb-3">Критерии ({{ script.criteria.length }})</h3>
          <div v-for="c in script.criteria" :key="c.id" class="flex items-start gap-3 py-2 border-b border-gray-100 last:border-0">
            <span class="text-xs text-gray-400 mt-0.5">{{ c.orderNum }}.</span>
            <div>
              <p class="text-sm font-medium text-gray-900">{{ c.name }}</p>
              <p class="text-xs text-gray-500">{{ c.description }}</p>
            </div>
            <span class="ml-auto text-xs px-2 py-0.5 rounded-full" :class="c.groupType === 'required' ? 'bg-blue-100 text-blue-700' : 'bg-gray-100 text-gray-600'">
              {{ c.groupType === 'required' ? 'Обязательный' : 'Опциональный' }}
            </span>
          </div>
        </div>
      </div>

      <div v-if="canEdit" class="flex justify-end">
        <button
          :disabled="saving"
          class="flex items-center gap-2 px-5 py-2.5 bg-primary-600 text-white font-medium rounded-lg text-sm hover:bg-primary-700 disabled:opacity-50 transition-colors"
          @click="handleSave"
        >
          <Save class="w-4 h-4" />
          {{ saving ? 'Сохранение...' : 'Сохранить' }}
        </button>
      </div>
    </template>
  </div>
</template>
