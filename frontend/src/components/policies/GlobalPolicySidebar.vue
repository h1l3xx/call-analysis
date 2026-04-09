<script setup lang="ts">
import { ref, watch } from 'vue'
import { Save, Loader2, Globe } from 'lucide-vue-next'
import type {
  DepartmentPolicyCallDirection,
  DepartmentCallPolicyResponse,
  PromptTemplateResponse,
  ScriptResponse,
} from '@/types'

const props = defineProps<{
  policies: DepartmentCallPolicyResponse[]
  scripts: ScriptResponse[]
  templates: PromptTemplateResponse[]
}>()

const emit = defineEmits<{
  (e: 'save', direction: DepartmentPolicyCallDirection, scriptId: string | null, templateId: string): void
}>()

const directions: { value: DepartmentPolicyCallDirection; label: string }[] = [
  { value: 'external_incoming', label: 'Внешн. вход.' },
  { value: 'external_outgoing', label: 'Внешн. исход.' },
  { value: 'internal_incoming', label: 'Внутр. вход.' },
  { value: 'internal_outgoing', label: 'Внутр. исход.' },
  { value: 'unknown', label: 'Неопред.' },
]

const localScript = ref<Record<DepartmentPolicyCallDirection, string>>({} as any)
const localTemplate = ref<Record<DepartmentPolicyCallDirection, string>>({} as any)
const savingDir = ref<DepartmentPolicyCallDirection | null>(null)

function getGlobal(dir: DepartmentPolicyCallDirection): DepartmentCallPolicyResponse | undefined {
  return props.policies.find(
    p => !p.departmentId && !p.secondDepartmentId && p.callDirection === dir,
  )
}

watch(
  () => props.policies,
  () => {
    for (const d of directions) {
      const g = getGlobal(d.value)
      localScript.value[d.value] = g?.scriptId ?? ''
      localTemplate.value[d.value] = g?.promptTemplateId ?? ''
    }
  },
  { immediate: true, deep: true },
)

function onSave(dir: DepartmentPolicyCallDirection) {
  savingDir.value = dir
  emit('save', dir, localScript.value[dir] || null, localTemplate.value[dir])
  setTimeout(() => { savingDir.value = null }, 600)
}
</script>

<template>
  <div class="flex flex-col h-full">
    <div class="flex items-center gap-2 px-4 py-3 border-b border-gray-100">
      <Globe class="w-4 h-4 text-primary-600" />
      <h3 class="text-sm font-semibold text-gray-900">Глобальные настройки</h3>
    </div>

    <div class="flex-1 overflow-y-auto px-3 py-3 space-y-3">
      <div
        v-for="d in directions"
        :key="d.value"
        class="rounded-lg border border-gray-200 bg-gray-50 p-3 space-y-2"
      >
        <div class="text-xs font-medium text-gray-700">{{ d.label }}</div>

        <div class="space-y-1.5">
          <select
            v-model="localScript[d.value]"
            class="w-full appearance-auto bg-white px-2 py-1.5 border border-gray-300 rounded text-xs focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          >
            <option value="">Без скрипта</option>
            <option v-for="s in scripts" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>

          <select
            v-model="localTemplate[d.value]"
            class="w-full appearance-auto bg-white px-2 py-1.5 border border-gray-300 rounded text-xs focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          >
            <option v-for="t in templates" :key="t.id" :value="t.id">{{ t.name }}</option>
          </select>
        </div>

        <button
          class="flex items-center gap-1 px-2 py-1 text-[10px] font-medium rounded border border-primary-200 text-primary-700 hover:bg-primary-50 disabled:opacity-50 w-full justify-center"
          :disabled="savingDir === d.value || !localTemplate[d.value]"
          @click="onSave(d.value)"
        >
          <Loader2 v-if="savingDir === d.value" class="w-3 h-3 animate-spin" />
          <Save v-else class="w-3 h-3" />
          Сохранить
        </button>
      </div>
    </div>
  </div>
</template>
