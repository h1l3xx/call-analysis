<script setup lang="ts">
import { computed } from 'vue'
import { ChevronRight, Link2, Settings2 } from 'lucide-vue-next'
import type { DepartmentCallPolicyResponse, PromptTemplateResponse, ScriptResponse } from '@/types'

interface Dept {
  id: string
  name: string
}

const props = defineProps<{
  departments: Dept[]
  policies: DepartmentCallPolicyResponse[]
  scripts: ScriptResponse[]
  templates: PromptTemplateResponse[]
}>()

const emit = defineEmits<{
  (e: 'node-click', departmentId: string): void
  (e: 'edge-click', sourceId: string, targetId: string): void
  (e: 'add-edge'): void
}>()

const dirLabels: Record<string, string> = {
  internal_incoming: 'Вн. вход.',
  internal_outgoing: 'Вн. исход.',
  external_incoming: 'Внеш. вход.',
  external_outgoing: 'Внеш. исход.',
  unknown: 'Неопр.',
}

const allDirs = ['internal_incoming', 'internal_outgoing', 'external_incoming', 'external_outgoing', 'unknown'] as const

function scriptName(id: string | null | undefined): string {
  if (!id) return '—'
  return props.scripts.find(s => s.id === id)?.name ?? '—'
}

function templateName(id: string | null | undefined): string {
  if (!id) return '—'
  return props.templates.find(t => t.id === id)?.name ?? id
}

function deptPolicy(deptId: string, dir: string): DepartmentCallPolicyResponse | undefined {
  return props.policies.find(p => p.departmentId === deptId && !p.secondDepartmentId && p.callDirection === dir)
}

function globalPolicy(dir: string): DepartmentCallPolicyResponse | undefined {
  return props.policies.find(p => !p.departmentId && !p.secondDepartmentId && p.callDirection === dir)
}

function effectiveForDept(deptId: string, dir: string): { scriptId: string | null; templateId: string | null; isOwn: boolean } {
  const own = deptPolicy(deptId, dir)
  if (own) return { scriptId: own.scriptId ?? null, templateId: own.promptTemplateId, isOwn: true }
  const g = globalPolicy(dir)
  return { scriptId: g?.scriptId ?? null, templateId: g?.promptTemplateId ?? null, isOwn: false }
}

function deptOverrideCount(deptId: string): number {
  return allDirs.filter(d => !!deptPolicy(deptId, d)).length
}

interface PairGroup {
  key: string
  deptA: Dept
  deptB: Dept
  policies: DepartmentCallPolicyResponse[]
}

const pairGroups = computed<PairGroup[]>(() => {
  const pairPolicies = props.policies.filter(p => p.departmentId && p.secondDepartmentId)
  const seen = new Map<string, PairGroup>()
  for (const p of pairPolicies) {
    const ids = [p.departmentId!, p.secondDepartmentId!].sort()
    const key = ids.join('::')
    if (!seen.has(key)) {
      const a = props.departments.find(d => d.id === ids[0])
      const b = props.departments.find(d => d.id === ids[1])
      if (!a || !b) continue
      seen.set(key, { key, deptA: a, deptB: b, policies: [] })
    }
    seen.get(key)!.policies.push(p)
  }
  return Array.from(seen.values())
})
</script>

<template>
  <div class="space-y-6">
    <!-- Department policies -->
    <div>
      <h3 class="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
        <Settings2 class="w-4 h-4 text-gray-400" />
        Политики отделов
      </h3>
      <div class="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <table class="w-full text-sm">
          <thead>
            <tr class="bg-gray-50 border-b border-gray-200">
              <th class="text-left px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wider">Отдел</th>
              <th
                v-for="dir in allDirs"
                :key="dir"
                class="text-center px-2 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wider"
              >{{ dirLabels[dir] }}</th>
              <th class="w-10" />
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-100">
            <tr
              v-for="dept in departments"
              :key="dept.id"
              class="hover:bg-blue-50/40 cursor-pointer transition-colors"
              @click="emit('node-click', dept.id)"
            >
              <td class="px-4 py-3">
                <div class="flex items-center gap-2">
                  <span class="font-medium text-gray-900">{{ dept.name }}</span>
                  <span
                    v-if="deptOverrideCount(dept.id) > 0"
                    class="text-[10px] px-1.5 py-0.5 rounded-full bg-blue-100 text-blue-700 font-medium"
                  >{{ deptOverrideCount(dept.id) }}</span>
                </div>
              </td>
              <td
                v-for="dir in allDirs"
                :key="dir"
                class="text-center px-2 py-3"
              >
                <div v-if="effectiveForDept(dept.id, dir).isOwn" class="space-y-0.5">
                  <div class="text-xs text-blue-700 font-medium truncate max-w-[120px] mx-auto" :title="scriptName(effectiveForDept(dept.id, dir).scriptId)">
                    {{ scriptName(effectiveForDept(dept.id, dir).scriptId) }}
                  </div>
                  <div class="text-[10px] text-gray-500 truncate max-w-[120px] mx-auto" :title="templateName(effectiveForDept(dept.id, dir).templateId)">
                    {{ templateName(effectiveForDept(dept.id, dir).templateId) }}
                  </div>
                </div>
                <span v-else class="text-xs text-gray-300">—</span>
              </td>
              <td class="px-2 py-3">
                <ChevronRight class="w-4 h-4 text-gray-300" />
              </td>
            </tr>
            <tr v-if="departments.length === 0">
              <td :colspan="allDirs.length + 2" class="px-4 py-8 text-center text-gray-400">Нет отделов</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Pair policies -->
    <div>
      <div class="flex items-center justify-between mb-3">
        <h3 class="text-sm font-semibold text-gray-700 flex items-center gap-2">
          <Link2 class="w-4 h-4 text-gray-400" />
          Межотдельные связи
          <span v-if="pairGroups.length > 0" class="text-xs text-gray-400 font-normal">({{ pairGroups.length }})</span>
        </h3>
        <button
          class="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700 transition-colors"
          @click="emit('add-edge')"
        >
          Добавить связь
        </button>
      </div>

      <div v-if="pairGroups.length === 0" class="bg-white border border-gray-200 rounded-xl px-4 py-8 text-center text-sm text-gray-400">
        Нет настроенных связей между отделами
      </div>

      <div v-else class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
        <div
          v-for="pair in pairGroups"
          :key="pair.key"
          class="bg-white border border-gray-200 rounded-xl p-4 hover:border-blue-300 hover:shadow-sm cursor-pointer transition-all"
          @click="emit('edge-click', pair.deptA.id, pair.deptB.id)"
        >
          <div class="flex items-center gap-2 mb-2">
            <span class="text-sm font-medium text-gray-900 truncate">{{ pair.deptA.name }}</span>
            <span class="text-xs text-gray-400">↔</span>
            <span class="text-sm font-medium text-gray-900 truncate">{{ pair.deptB.name }}</span>
          </div>
          <div class="flex flex-wrap gap-1.5">
            <span
              v-for="p in pair.policies"
              :key="p.id"
              class="inline-flex items-center text-[10px] px-1.5 py-0.5 rounded bg-blue-50 text-blue-700 font-medium"
            >
              {{ dirLabels[p.callDirection] || p.callDirection }}
            </span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
