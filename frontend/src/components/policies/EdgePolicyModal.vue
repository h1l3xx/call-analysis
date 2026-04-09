<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { X, Save, Loader2, Trash2 } from 'lucide-vue-next'
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
  sourceDepartment: Dept | null
  targetDepartment: Dept | null
  policies: DepartmentCallPolicyResponse[]
  scripts: ScriptResponse[]
  templates: PromptTemplateResponse[]
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'save', sourceId: string, targetId: string, direction: DepartmentPolicyCallDirection, scriptId: string | null, templateId: string): void
  (e: 'remove-edge', sourceId: string, targetId: string): void
}>()

const internalDirs = computed(() => {
  const src = props.sourceDepartment?.name ?? '?'
  const tgt = props.targetDepartment?.name ?? '?'
  return [
    { value: 'internal_outgoing' as DepartmentPolicyCallDirection, label: `${src} → ${tgt}` },
    { value: 'internal_incoming' as DepartmentPolicyCallDirection, label: `${tgt} → ${src}` },
  ]
})

const localScript = ref<Record<string, string>>({})
const localTemplate = ref<Record<string, string>>({})
const savingDir = ref<DepartmentPolicyCallDirection | null>(null)

function getPairPolicy(dir: DepartmentPolicyCallDirection): DepartmentCallPolicyResponse | undefined {
  if (!props.sourceDepartment || !props.targetDepartment) return undefined
  const src = props.sourceDepartment.id
  const tgt = props.targetDepartment.id
  return props.policies.find(p =>
    p.callDirection === dir && (
      (p.departmentId === src && p.secondDepartmentId === tgt) ||
      (p.departmentId === tgt && p.secondDepartmentId === src)
    ),
  )
}

function getGlobalPolicy(dir: DepartmentPolicyCallDirection): DepartmentCallPolicyResponse | undefined {
  return props.policies.find(
    p => !p.departmentId && !p.secondDepartmentId && p.callDirection === dir,
  )
}

function effectiveScript(dir: DepartmentPolicyCallDirection): string {
  const pair = getPairPolicy(dir)
  if (pair?.scriptId) return pair.scriptId
  const global = getGlobalPolicy(dir)
  return global?.scriptId ?? ''
}

function effectiveTemplate(dir: DepartmentPolicyCallDirection): string {
  const pair = getPairPolicy(dir)
  if (pair?.promptTemplateId) return pair.promptTemplateId
  const global = getGlobalPolicy(dir)
  return global?.promptTemplateId ?? ''
}

const hasPairPolicy = (dir: DepartmentPolicyCallDirection) => !!getPairPolicy(dir)

watch(
  () => [props.visible, props.sourceDepartment, props.targetDepartment, props.policies],
  () => {
    if (!props.visible || !props.sourceDepartment || !props.targetDepartment) return
    for (const d of internalDirs.value) {
      localScript.value[d.value] = effectiveScript(d.value)
      localTemplate.value[d.value] = effectiveTemplate(d.value)
    }
  },
  { immediate: true, deep: true },
)

function onSave(dir: DepartmentPolicyCallDirection) {
  if (!props.sourceDepartment || !props.targetDepartment) return
  savingDir.value = dir
  emit('save', props.sourceDepartment.id, props.targetDepartment.id, dir, localScript.value[dir] || null, localTemplate.value[dir])
  setTimeout(() => { savingDir.value = null }, 600)
}

function onRemoveEdge() {
  if (!props.sourceDepartment || !props.targetDepartment) return
  if (!confirm('Удалить межотдельную связь? Будут использоваться настройки по умолчанию.')) return
  emit('remove-edge', props.sourceDepartment.id, props.targetDepartment.id)
}
</script>

<template>
  <teleport to="body">
    <div
      v-if="visible && sourceDepartment && targetDepartment"
      class="fixed inset-0 z-50 flex items-center justify-center"
    >
      <div class="absolute inset-0 bg-black/40" @click="emit('close')" />
      <div class="relative bg-white rounded-2xl shadow-xl w-full max-w-xl mx-4 max-h-[90vh] overflow-y-auto">
        <div class="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <div>
            <h2 class="text-lg font-semibold text-gray-900">
              {{ sourceDepartment.name }} ↔ {{ targetDepartment.name }}
            </h2>
            <p class="text-sm text-gray-500 mt-0.5">Политики внутренних звонков между отделами</p>
          </div>
          <button class="p-1.5 rounded-lg hover:bg-gray-100 text-gray-400" @click="emit('close')">
            <X class="w-5 h-5" />
          </button>
        </div>

        <div class="p-6 space-y-4">
          <div
            v-for="d in internalDirs"
            :key="d.value"
            class="rounded-lg border p-4 space-y-3"
            :class="hasPairPolicy(d.value) ? 'border-primary-200 bg-primary-50/30' : 'border-gray-200 bg-gray-50'"
          >
            <div class="flex items-center justify-between">
              <span class="text-sm font-medium text-gray-800">{{ d.label }}</span>
              <span
                class="text-xs px-2 py-0.5 rounded-full"
                :class="hasPairPolicy(d.value) ? 'bg-primary-100 text-primary-700' : 'bg-gray-200 text-gray-600'"
              >{{ hasPairPolicy(d.value) ? 'Настроено' : 'По умолчанию' }}</span>
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
            class="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-red-700 bg-red-50 border border-red-200 rounded-lg hover:bg-red-100"
            @click="onRemoveEdge"
          >
            <Trash2 class="w-3.5 h-3.5" />
            Удалить связь
          </button>
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
