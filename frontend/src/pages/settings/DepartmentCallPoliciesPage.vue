<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { callsApi, scriptsApi, promptTemplatesApi, departmentCallPoliciesApi } from '@/api'
import type {
  DepartmentPolicyCallDirection,
  DepartmentCallPolicyResponse,
  PromptTemplateResponse,
  ScriptResponse,
} from '@/types'

import PolicyGraph from '@/components/policies/PolicyGraph.vue'
import GlobalPolicySidebar from '@/components/policies/GlobalPolicySidebar.vue'
import DepartmentPolicyModal from '@/components/policies/DepartmentPolicyModal.vue'
import EdgePolicyModal from '@/components/policies/EdgePolicyModal.vue'
import AddEdgeModal from '@/components/policies/AddEdgeModal.vue'

interface Dept {
  id: string
  name: string
}

const loading = ref(true)
const departments = ref<Dept[]>([])
const scripts = ref<ScriptResponse[]>([])
const templates = ref<PromptTemplateResponse[]>([])
const policies = ref<DepartmentCallPolicyResponse[]>([])

const nodeModalDept = ref<Dept | null>(null)
const showNodeModal = ref(false)

const edgeSource = ref<Dept | null>(null)
const edgeTarget = ref<Dept | null>(null)
const showEdgeModal = ref(false)

const showAddEdge = ref(false)

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
  } finally {
    loading.value = false
  }
}

async function reloadPolicies() {
  const { data } = await departmentCallPoliciesApi.list()
  policies.value = data
}

function findDept(id: string): Dept | null {
  return departments.value.find(d => d.id === id) ?? null
}

function onNodeClick(deptId: string) {
  nodeModalDept.value = findDept(deptId)
  showNodeModal.value = true
}

function onEdgeClick(sourceId: string, targetId: string) {
  edgeSource.value = findDept(sourceId)
  edgeTarget.value = findDept(targetId)
  showEdgeModal.value = true
}

async function onSaveGlobal(direction: DepartmentPolicyCallDirection, scriptId: string | null, templateId: string) {
  await departmentCallPoliciesApi.upsert({
    departmentId: null,
    secondDepartmentId: null,
    callDirection: direction,
    scriptId,
    promptTemplateId: templateId,
  })
  await reloadPolicies()
}

async function onSaveDepartment(deptId: string, direction: DepartmentPolicyCallDirection, scriptId: string | null, templateId: string) {
  await departmentCallPoliciesApi.upsert({
    departmentId: deptId,
    secondDepartmentId: null,
    callDirection: direction,
    scriptId,
    promptTemplateId: templateId,
  })
  await reloadPolicies()
}

async function onResetDepartment(deptId: string) {
  const dirs: DepartmentPolicyCallDirection[] = [
    'internal_incoming', 'internal_outgoing',
    'external_incoming', 'external_outgoing', 'unknown',
  ]
  for (const dir of dirs) {
    try {
      await departmentCallPoliciesApi.removeDepartmentOverride(deptId, dir)
    } catch { /* ignore if no override */ }
  }
  await reloadPolicies()
}

async function onSaveEdge(
  sourceId: string,
  targetId: string,
  direction: DepartmentPolicyCallDirection,
  scriptId: string | null,
  templateId: string,
) {
  await departmentCallPoliciesApi.upsert({
    departmentId: sourceId,
    secondDepartmentId: targetId,
    callDirection: direction,
    scriptId,
    promptTemplateId: templateId,
  })
  await reloadPolicies()
}

async function onRemoveEdge(sourceId: string, targetId: string) {
  try {
    await departmentCallPoliciesApi.removePairPolicies(sourceId, targetId)
  } catch { /* ignore if no pair policies exist */ }
  showEdgeModal.value = false
  await reloadPolicies()
}

async function onAddEdge(sourceId: string, targetId: string) {
  showAddEdge.value = false
  edgeSource.value = findDept(sourceId)
  edgeTarget.value = findDept(targetId)
  showEdgeModal.value = true
}

onMounted(load)
</script>

<template>
  <div class="space-y-4">
    <div>
      <h1 class="text-2xl font-bold text-gray-900">Политики скриптов и оценки</h1>
      <p class="text-sm text-gray-500 mt-1">
        Клик по узлу — настройка отдела. Клик по связи — политика между отделами. Глобальные настройки — слева.
      </p>
    </div>

    <div v-if="loading" class="p-12 text-center text-gray-400 bg-white border border-gray-200 rounded-xl">
      Загрузка...
    </div>

    <div v-else class="flex gap-4" style="height: calc(100vh - 180px)">
      <div class="w-64 shrink-0 bg-white border border-gray-200 rounded-xl overflow-hidden">
        <GlobalPolicySidebar
          :policies="policies"
          :scripts="scripts"
          :templates="templates"
          @save="onSaveGlobal"
        />
      </div>

      <div class="flex-1 bg-white border border-gray-200 rounded-xl overflow-hidden">
        <PolicyGraph
          :departments="departments"
          :policies="policies"
          @node-click="onNodeClick"
          @edge-click="onEdgeClick"
          @add-edge="showAddEdge = true"
        />
      </div>
    </div>

    <DepartmentPolicyModal
      :visible="showNodeModal"
      :department="nodeModalDept"
      :policies="policies"
      :scripts="scripts"
      :templates="templates"
      @close="showNodeModal = false"
      @save="onSaveDepartment"
      @reset="onResetDepartment"
    />

    <EdgePolicyModal
      :visible="showEdgeModal"
      :source-department="edgeSource"
      :target-department="edgeTarget"
      :policies="policies"
      :scripts="scripts"
      :templates="templates"
      @close="showEdgeModal = false"
      @save="onSaveEdge"
      @remove-edge="onRemoveEdge"
    />

    <AddEdgeModal
      :visible="showAddEdge"
      :departments="departments"
      @close="showAddEdge = false"
      @add="onAddEdge"
    />
  </div>
</template>
