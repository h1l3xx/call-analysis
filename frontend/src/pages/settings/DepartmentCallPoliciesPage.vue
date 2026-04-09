<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { callsApi, scriptsApi, promptTemplatesApi, departmentCallPoliciesApi } from '@/api'
import type { DepartmentCallPolicyResponse, PromptTemplateResponse, ScriptResponse } from '@/types'

type Direction = 'internal' | 'external_incoming' | 'external_outgoing'

const loading = ref(true)
const savingKey = ref<string | null>(null)
const departments = ref<{ id: string; name: string }[]>([])
const scripts = ref<ScriptResponse[]>([])
const templates = ref<PromptTemplateResponse[]>([])
const policies = ref<DepartmentCallPolicyResponse[]>([])

const directions: { value: Direction; label: string }[] = [
  { value: 'internal', label: 'Внутренние' },
  { value: 'external_incoming', label: 'Внешние входящие' },
  { value: 'external_outgoing', label: 'Внешние исходящие' },
]

const rows = computed(() => ([
  { id: null as string | null, name: 'Глобально (fallback)' },
  ...departments.value.map(d => ({ id: d.id, name: d.name })),
]))

const selectedScriptByKey = ref<Record<string, string>>({})
const selectedTemplateByKey = ref<Record<string, string>>({})

const templateOptions = computed(() =>
  templates.value.filter(t => ['eval_internal', 'eval_external_incoming', 'eval_external_outgoing'].includes(t.id))
)

function key(departmentId: string | null, direction: Direction): string {
  return `${departmentId ?? 'global'}::${direction}`
}

function getExistingPolicy(departmentId: string | null, direction: Direction): DepartmentCallPolicyResponse | undefined {
  return policies.value.find(p => p.departmentId === departmentId && p.callDirection === direction)
}

function defaultTemplate(direction: Direction): string {
  if (direction === 'internal') return 'eval_internal'
  if (direction === 'external_incoming') return 'eval_external_incoming'
  return 'eval_external_outgoing'
}

function initSelection() {
  for (const row of rows.value) {
    for (const d of directions) {
      const k = key(row.id, d.value)
      const policy = getExistingPolicy(row.id, d.value)
      selectedScriptByKey.value[k] = policy?.scriptId || scripts.value[0]?.id || ''
      selectedTemplateByKey.value[k] = policy?.promptTemplateId || defaultTemplate(d.value)
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
    scripts.value = scriptRes.data.items
    templates.value = tplRes.data
    policies.value = policyRes.data
    initSelection()
  } finally {
    loading.value = false
  }
}

async function saveRow(departmentId: string | null, direction: Direction) {
  const k = key(departmentId, direction)
  const scriptId = selectedScriptByKey.value[k]
  const promptTemplateId = selectedTemplateByKey.value[k]
  if (!scriptId || !promptTemplateId) return

  savingKey.value = k
  try {
    await departmentCallPoliciesApi.upsert({
      departmentId,
      callDirection: direction,
      scriptId,
      promptTemplateId,
    })
    const { data } = await departmentCallPoliciesApi.list()
    policies.value = data
  } finally {
    savingKey.value = null
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
      <div
        v-for="row in rows"
        :key="row.id ?? 'global'"
        class="bg-white border border-gray-200 rounded-xl p-4 space-y-3"
      >
        <h2 class="font-semibold text-gray-900">{{ row.name }}</h2>

        <div
          v-for="d in directions"
          :key="d.value"
          class="grid grid-cols-1 lg:grid-cols-[220px_1fr_1fr_120px] gap-2 items-center"
        >
          <div class="text-sm text-gray-700">{{ d.label }}</div>

          <select
            v-model="selectedScriptByKey[key(row.id, d.value)]"
            class="appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          >
            <option v-for="s in scripts" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>

          <select
            v-model="selectedTemplateByKey[key(row.id, d.value)]"
            class="appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          >
            <option v-for="t in templateOptions" :key="t.id" :value="t.id">{{ t.name }}</option>
          </select>

          <button
            class="px-3 py-2 text-sm font-medium rounded-lg border border-primary-200 text-primary-700 hover:bg-primary-50 disabled:opacity-50"
            :disabled="savingKey === key(row.id, d.value)"
            @click="saveRow(row.id, d.value)"
          >
            {{ savingKey === key(row.id, d.value) ? 'Сохранение...' : 'Сохранить' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

