<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ChevronDown, ChevronRight } from 'lucide-vue-next'
import { callsApi, scriptsApi, promptTemplatesApi, departmentCallPoliciesApi } from '@/api'
import type { DepartmentPolicyCallDirection, DepartmentCallPolicyResponse, PromptTemplateResponse, ScriptResponse } from '@/types'

const loading = ref(true)
const savingKey = ref<string | null>(null)
const departments = ref<{ id: string; name: string }[]>([])
const scripts = ref<ScriptResponse[]>([])
const templates = ref<PromptTemplateResponse[]>([])
const policies = ref<DepartmentCallPolicyResponse[]>([])
const expandedDeptIds = ref<string[]>([])
const peerDepartmentByDept = ref<Record<string, string>>({})

const directions: { value: DepartmentPolicyCallDirection; label: string }[] = [
  { value: 'internal_incoming', label: 'Внутренние входящие' },
  { value: 'internal_outgoing', label: 'Внутренние исходящие' },
  { value: 'external_incoming', label: 'Внешние входящие' },
  { value: 'external_outgoing', label: 'Внешние исходящие' },
  { value: 'unknown', label: 'Неопределённые' },
]

const selectedScriptByKey = ref<Record<string, string>>({})
const selectedTemplateByKey = ref<Record<string, string>>({})
const internalDirections: DepartmentPolicyCallDirection[] = ['internal_incoming', 'internal_outgoing']

const templateOptions = computed(() => templates.value)

function key(
  departmentId: string | null,
  secondDepartmentId: string | null,
  direction: DepartmentPolicyCallDirection,
): string {
  return `${departmentId ?? 'global'}::${secondDepartmentId ?? 'none'}::${direction}`
}

function getPolicy(
  departmentId: string | null,
  secondDepartmentId: string | null,
  direction: DepartmentPolicyCallDirection,
): DepartmentCallPolicyResponse | undefined {
  if (departmentId && secondDepartmentId) {
    return policies.value.find(p =>
      p.callDirection === direction && (
        (p.departmentId === departmentId && p.secondDepartmentId === secondDepartmentId) ||
        (p.departmentId === secondDepartmentId && p.secondDepartmentId === departmentId)
      )
    )
  }
  return policies.value.find(
    p =>
      p.departmentId === departmentId &&
      p.secondDepartmentId === secondDepartmentId &&
      p.callDirection === direction,
  )
}

function defaultTemplate(direction: DepartmentPolicyCallDirection): string {
  if (direction === 'internal_incoming') return 'eval_internal_incoming'
  if (direction === 'internal_outgoing') return 'eval_internal_outgoing'
  if (direction === 'external_incoming') return 'eval_external_incoming'
  if (direction === 'external_outgoing') return 'eval_external_outgoing'
  return 'eval_external_incoming'
}

function effectiveScript(
  departmentId: string | null,
  secondDepartmentId: string | null,
  direction: DepartmentPolicyCallDirection,
): string {
  const exact = getPolicy(departmentId, secondDepartmentId, direction)
  if (exact?.scriptId) return exact.scriptId
  if (departmentId && secondDepartmentId) {
    const byDept = getPolicy(departmentId, null, direction)
    if (byDept?.scriptId) return byDept.scriptId
  }
  const global = getPolicy(null, null, direction)
  if (global?.scriptId) return global.scriptId
  return ''
}

function effectiveTemplate(
  departmentId: string | null,
  secondDepartmentId: string | null,
  direction: DepartmentPolicyCallDirection,
): string {
  const exact = getPolicy(departmentId, secondDepartmentId, direction)
  if (exact?.promptTemplateId) return exact.promptTemplateId
  if (departmentId && secondDepartmentId) {
    const byDept = getPolicy(departmentId, null, direction)
    if (byDept?.promptTemplateId) return byDept.promptTemplateId
  }
  const global = getPolicy(null, null, direction)
  if (global?.promptTemplateId) return global.promptTemplateId
  return defaultTemplate(direction)
}

function isInherited(departmentId: string, direction: DepartmentPolicyCallDirection): boolean {
  return !getPolicy(departmentId, null, direction)
}

function initSelection() {
  for (const d of directions) {
    const k = key(null, null, d.value)
    selectedScriptByKey.value[k] = effectiveScript(null, null, d.value)
    selectedTemplateByKey.value[k] = effectiveTemplate(null, null, d.value)
  }
  for (const dep of departments.value) {
    for (const d of directions) {
      const k = key(dep.id, null, d.value)
      selectedScriptByKey.value[k] = effectiveScript(dep.id, null, d.value)
      selectedTemplateByKey.value[k] = effectiveTemplate(dep.id, null, d.value)
    }
    const peer = peerDepartmentByDept.value[dep.id]
    if (peer) {
      for (const d of internalDirections) {
        const k = key(dep.id, peer, d)
        selectedScriptByKey.value[k] = effectiveScript(dep.id, peer, d)
        selectedTemplateByKey.value[k] = effectiveTemplate(dep.id, peer, d)
      }
    }
  }
}

async function load() {
  loading.value = true
  try {
    const [deptRes, scriptRes, tplRes, policyRes] = await Promise.all([
      callsApi.departments(),
      scriptsApi.list({ page: 1, pageSize: 500, isActive: true }),
      promptTemplatesApi.list(),
      departmentCallPoliciesApi.list(),
    ])
    departments.value = deptRes.data
    for (const dep of departments.value) {
      if (!peerDepartmentByDept.value[dep.id]) {
        const peer = departments.value.find(d => d.id !== dep.id)
        if (peer) peerDepartmentByDept.value[dep.id] = peer.id
      }
    }
    scripts.value = scriptRes.data.items
    templates.value = tplRes.data
    policies.value = policyRes.data
    initSelection()
  } finally {
    loading.value = false
  }
}

async function saveRow(
  departmentId: string | null,
  secondDepartmentId: string | null,
  direction: DepartmentPolicyCallDirection,
) {
  const k = key(departmentId, secondDepartmentId, direction)
  const scriptId = selectedScriptByKey.value[k]
  const promptTemplateId = selectedTemplateByKey.value[k]
  if (!promptTemplateId) return

  savingKey.value = k
  try {
    await departmentCallPoliciesApi.upsert({
      departmentId,
      secondDepartmentId,
      callDirection: direction,
      scriptId: scriptId || null,
      promptTemplateId,
    })
    const { data } = await departmentCallPoliciesApi.list()
    policies.value = data
  } finally {
    savingKey.value = null
  }
}

async function applyGlobalToDepartment(departmentId: string) {
  for (const d of directions) {
    selectedScriptByKey.value[key(departmentId, null, d.value)] = selectedScriptByKey.value[key(null, null, d.value)]
    selectedTemplateByKey.value[key(departmentId, null, d.value)] = selectedTemplateByKey.value[key(null, null, d.value)]
    await saveRow(departmentId, null, d.value)
  }
}

async function resetToGlobal(departmentId: string) {
  for (const d of directions) {
    const k = key(departmentId, null, d.value)
    savingKey.value = k
    try {
      await departmentCallPoliciesApi.removeDepartmentOverride(departmentId, d.value)
    } catch {
      // if no override exists - ignore
    } finally {
      savingKey.value = null
    }
  }
  const { data } = await departmentCallPoliciesApi.list()
  policies.value = data
  initSelection()
}

function onPeerDepartmentChange(departmentId: string) {
  const peer = peerDepartmentByDept.value[departmentId]
  if (!peer) return
  for (const d of internalDirections) {
    const k = key(departmentId, peer, d)
    selectedScriptByKey.value[k] = effectiveScript(departmentId, peer, d)
    selectedTemplateByKey.value[k] = effectiveTemplate(departmentId, peer, d)
  }
}

function toggleDepartment(departmentId: string) {
  if (expandedDeptIds.value.includes(departmentId)) {
    expandedDeptIds.value = expandedDeptIds.value.filter(id => id !== departmentId)
  } else {
    expandedDeptIds.value = [...expandedDeptIds.value, departmentId]
  }
}

onMounted(load)
</script>

<template>
  <div class="space-y-5">
    <h1 class="text-2xl font-bold text-gray-900">Политики скриптов и оценки</h1>
    <p class="text-sm text-gray-500">
      Настройка выбора скрипта и шаблона оценки по отделу и направлению звонка.
    </p>

    <div v-if="loading" class="p-8 text-center text-gray-400 bg-white border border-gray-200 rounded-xl">
      Загрузка...
    </div>

    <div v-else class="space-y-4">
      <div class="bg-white border border-gray-200 rounded-xl p-4 space-y-3">
        <h2 class="font-semibold text-gray-900">Глобальные настройки</h2>
        <div
          v-for="d in directions"
          :key="d.value"
          class="grid grid-cols-1 lg:grid-cols-[240px_1fr_1fr_120px] gap-2 items-center"
        >
          <div class="text-sm text-gray-700">{{ d.label }}</div>
          <select
            v-model="selectedScriptByKey[key(null, null, d.value)]"
            class="appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          >
            <option value="">Без скрипта</option>
            <option v-for="s in scripts" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
          <select
            v-model="selectedTemplateByKey[key(null, null, d.value)]"
            class="appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          >
            <option v-for="t in templateOptions" :key="t.id" :value="t.id">{{ t.name }}</option>
          </select>
          <button
            class="px-3 py-2 text-sm font-medium rounded-lg border border-primary-200 text-primary-700 hover:bg-primary-50 disabled:opacity-50"
            :disabled="savingKey === key(null, null, d.value)"
            @click="saveRow(null, null, d.value)"
          >
            {{ savingKey === key(null, null, d.value) ? 'Сохранение...' : 'Сохранить' }}
          </button>
        </div>
      </div>

      <div class="space-y-3">
        <div
          v-for="dep in departments"
          :key="dep.id"
          class="bg-white border border-gray-200 rounded-xl"
        >
          <button
            class="w-full flex items-center justify-between px-4 py-3 text-left"
            @click="toggleDepartment(dep.id)"
          >
            <span class="font-medium text-gray-900">{{ dep.name }}</span>
            <component :is="expandedDeptIds.includes(dep.id) ? ChevronDown : ChevronRight" class="w-4 h-4 text-gray-500" />
          </button>

          <div v-if="expandedDeptIds.includes(dep.id)" class="border-t border-gray-100 p-4 space-y-3">
            <div class="flex items-center gap-2">
              <button
                class="px-3 py-1.5 text-xs font-medium rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-50"
                @click="applyGlobalToDepartment(dep.id)"
              >
                Применить глобальные в отдел
              </button>
              <button
                class="px-3 py-1.5 text-xs font-medium rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-50"
                @click="resetToGlobal(dep.id)"
              >
                Сбросить к глобальным
              </button>
            </div>

            <div
              v-for="d in directions"
              :key="d.value"
              class="grid grid-cols-1 lg:grid-cols-[240px_1fr_1fr_150px_120px] gap-2 items-center"
            >
              <div class="text-sm text-gray-700">{{ d.label }}</div>
              <select
                v-model="selectedScriptByKey[key(dep.id, null, d.value)]"
                class="appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
              >
                <option value="">Без скрипта</option>
                <option v-for="s in scripts" :key="s.id" :value="s.id">{{ s.name }}</option>
              </select>
              <select
                v-model="selectedTemplateByKey[key(dep.id, null, d.value)]"
                class="appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
              >
                <option v-for="t in templateOptions" :key="t.id" :value="t.id">{{ t.name }}</option>
              </select>
              <span
                class="text-xs px-2 py-1 rounded w-fit"
                :class="isInherited(dep.id, d.value) ? 'bg-gray-100 text-gray-600' : 'bg-primary-100 text-primary-700'"
              >
                {{ isInherited(dep.id, d.value) ? 'Наследуется' : 'Переопределено' }}
              </span>
              <button
                class="px-3 py-2 text-sm font-medium rounded-lg border border-primary-200 text-primary-700 hover:bg-primary-50 disabled:opacity-50"
                :disabled="savingKey === key(dep.id, null, d.value)"
                @click="saveRow(dep.id, null, d.value)"
              >
                {{ savingKey === key(dep.id, null, d.value) ? 'Сохранение...' : 'Сохранить' }}
              </button>
            </div>

            <div class="pt-2 mt-2 border-t border-gray-100 space-y-2">
              <div class="text-xs font-semibold text-gray-500 uppercase">Между отделами</div>
              <div class="grid grid-cols-1 lg:grid-cols-[240px_1fr] gap-2 items-center">
                <div class="text-sm text-gray-700">С кем общается отдел</div>
                <select
                  v-model="peerDepartmentByDept[dep.id]"
                  class="appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
                  @change="onPeerDepartmentChange(dep.id)"
                >
                  <option v-for="d2 in departments.filter(x => x.id !== dep.id)" :key="d2.id" :value="d2.id">{{ d2.name }}</option>
                </select>
              </div>

              <div
                v-for="d in directions.filter(x => x.value === 'internal_incoming' || x.value === 'internal_outgoing')"
                :key="`pair-${dep.id}-${d.value}`"
                class="grid grid-cols-1 lg:grid-cols-[240px_1fr_1fr_120px] gap-2 items-center"
              >
                <div class="text-sm text-gray-700">{{ d.label }}</div>
                <select
                  v-model="selectedScriptByKey[key(dep.id, peerDepartmentByDept[dep.id] || null, d.value)]"
                  class="appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
                >
                  <option value="">Без скрипта</option>
                  <option v-for="s in scripts" :key="s.id" :value="s.id">{{ s.name }}</option>
                </select>
                <select
                  v-model="selectedTemplateByKey[key(dep.id, peerDepartmentByDept[dep.id] || null, d.value)]"
                  class="appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
                >
                  <option v-for="t in templateOptions" :key="t.id" :value="t.id">{{ t.name }}</option>
                </select>
                <button
                  class="px-3 py-2 text-sm font-medium rounded-lg border border-primary-200 text-primary-700 hover:bg-primary-50 disabled:opacity-50"
                  :disabled="savingKey === key(dep.id, peerDepartmentByDept[dep.id] || null, d.value) || !peerDepartmentByDept[dep.id]"
                  @click="saveRow(dep.id, peerDepartmentByDept[dep.id] || null, d.value)"
                >
                  {{ savingKey === key(dep.id, peerDepartmentByDept[dep.id] || null, d.value) ? 'Сохранение...' : 'Сохранить' }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

