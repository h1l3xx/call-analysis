<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { X, Save, Loader2, RotateCcw } from 'lucide-vue-next'
import type {
  DepartmentPolicyCallDirection,
  DepartmentCallPolicyResponse,
  PromptTemplateResponse,
  ScriptResponse,
} from '@/types'

interface Dept {
  id: string
  name: string
}

const props = defineProps<{
  visible: boolean
  department: Dept | null
  policies: DepartmentCallPolicyResponse[]
  scripts: ScriptResponse[]
  templates: PromptTemplateResponse[]
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'save', departmentId: string, direction: DepartmentPolicyCallDirection, scriptId: string | null, templateId: string): void
  (e: 'reset', departmentId: string): void
}>()

const directions: { value: DepartmentPolicyCallDirection; label: string }[] = [
  { value: 'internal_incoming', label: 'Внутренние входящие' },
  { value: 'internal_outgoing', label: 'Внутренние исходящие' },
  { value: 'external_incoming', label: 'Внешние входящие' },
  { value: 'external_outgoing', label: 'Внешние исходящие' },
  { value: 'unknown', label: 'Неопределённые' },
]

const localScript = ref<Record<DepartmentPolicyCallDirection, string>>({} as any)
const localTemplate = ref<Record<DepartmentPolicyCallDirection, string>>({} as any)
const savingDir = ref<DepartmentPolicyCallDirection | null>(null)
const resettingAll = ref(false)

function getPolicy(dir: DepartmentPolicyCallDirection): DepartmentCallPolicyResponse | undefined {
  if (!props.department) return undefined
  return props.policies.find(
    p => p.departmentId === props.department!.id && !p.secondDepartmentId && p.callDirection === dir,
  )
}

function getGlobalPolicy(dir: DepartmentPolicyCallDirection): DepartmentCallPolicyResponse | undefined {
  return props.policies.find(
    p => !p.departmentId && !p.secondDepartmentId && p.callDirection === dir,
  )
}

function effectiveScript(dir: DepartmentPolicyCallDirection): string {
  const own = getPolicy(dir)
  if (own?.scriptId) return own.scriptId
  const global = getGlobalPolicy(dir)
  return global?.scriptId ?? ''
}

function effectiveTemplate(dir: DepartmentPolicyCallDirection): string {
  const own = getPolicy(dir)
  if (own?.promptTemplateId) return own.promptTemplateId
  const global = getGlobalPolicy(dir)
  return global?.promptTemplateId ?? ''
}

const isInherited = (dir: DepartmentPolicyCallDirection) => !getPolicy(dir)

watch(
  () => [props.visible, props.department, props.policies],
  () => {
    if (!props.visible || !props.department) return
    for (const d of directions) {
      localScript.value[d.value] = effectiveScript(d.value)
      localTemplate.value[d.value] = effectiveTemplate(d.value)
    }
  },
  { immediate: true, deep: true },
)

const hasAnyOverride = computed(() => {
  if (!props.department) return false
  return directions.some(d => !isInherited(d.value))
})

async function onSave(dir: DepartmentPolicyCallDirection) {
  if (!props.department) return
  savingDir.value = dir
  emit('save', props.department.id, dir, localScript.value[dir] || null, localTemplate.value[dir])
  setTimeout(() => { savingDir.value = null }, 600)
}

function onResetAll() {
  if (!props.department) return
  resettingAll.value = true
  emit('reset', props.department.id)
  setTimeout(() => { resettingAll.value = false }, 600)
}
</script>

<template>
  <teleport to="body">
    <div
      v-if="visible && department"
      class="fixed inset-0 z-50 flex items-center justify-center"
    >
      <div class="absolute inset-0 bg-black/40" @click="emit('close')" />
      <div class="relative bg-white rounded-2xl shadow-xl w-full max-w-2xl mx-4 max-h-[90vh] overflow-y-auto">
        <div class="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <div>
            <h2 class="text-lg font-semibold text-gray-900">{{ department.name }}</h2>
            <p class="text-sm text-gray-500 mt-0.5">Настройка скрипта и оценки по направлениям</p>
          </div>
          <button class="p-1.5 rounded-lg hover:bg-gray-100 text-gray-400" @click="emit('close')">
            <X class="w-5 h-5" />
          </button>
        </div>

        <div class="p-6 space-y-4">
          <div
            v-for="d in directions"
            :key="d.value"
            class="rounded-lg border p-4 space-y-3"
            :class="isInherited(d.value) ? 'border-gray-200 bg-gray-50' : 'border-primary-200 bg-primary-50/30'"
          >
            <div class="flex items-center justify-between">
              <span class="text-sm font-medium text-gray-800">{{ d.label }}</span>
              <span
                class="text-xs px-2 py-0.5 rounded-full"
                :class="isInherited(d.value) ? 'bg-gray-200 text-gray-600' : 'bg-primary-100 text-primary-700'"
              >{{ isInherited(d.value) ? 'По умолчанию' : 'Настроено' }}</span>
            </div>

            <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label class="block text-xs text-gray-500 mb-1">Скрипт</label>
                <select
                  v-model="localScript[d.value]"
                  class="w-full appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
                >
                  <option value="">Без скрипта</option>
                  <option v-for="s in scripts" :key="s.id" :value="s.id">{{ s.name }}</option>
                </select>
              </div>
              <div>
                <label class="block text-xs text-gray-500 mb-1">Оценка</label>
                <select
                  v-model="localTemplate[d.value]"
                  class="w-full appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
                >
                  <option v-for="t in templates" :key="t.id" :value="t.id">{{ t.name }}</option>
                </select>
              </div>
            </div>

            <div class="flex justify-end">
              <button
                class="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg border border-primary-200 text-primary-700 hover:bg-primary-50 disabled:opacity-50"
                :disabled="savingDir === d.value || !localTemplate[d.value]"
                @click="onSave(d.value)"
              >
                <Loader2 v-if="savingDir === d.value" class="w-3.5 h-3.5 animate-spin" />
                <Save v-else class="w-3.5 h-3.5" />
                Сохранить
              </button>
            </div>
          </div>
        </div>

        <div class="flex items-center justify-between px-6 py-4 border-t border-gray-100">
          <button
            v-if="hasAnyOverride"
            class="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-gray-700 bg-gray-100 border border-gray-200 rounded-lg hover:bg-gray-200 disabled:opacity-50"
            :disabled="resettingAll"
            @click="onResetAll"
          >
            <Loader2 v-if="resettingAll" class="w-3.5 h-3.5 animate-spin" />
            <RotateCcw v-else class="w-3.5 h-3.5" />
            Сбросить по умолчанию
          </button>
          <div v-else />
          <button
            class="px-4 py-2 text-sm font-medium text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200"
            @click="emit('close')"
          >
            Закрыть
          </button>
        </div>
      </div>
    </div>
  </teleport>
</template>
