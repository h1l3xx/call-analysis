<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { VNetworkGraph } from 'v-network-graph'
import 'v-network-graph/lib/style.css'
import type { DepartmentCallPolicyResponse } from '@/types'
import { Plus } from 'lucide-vue-next'

interface Dept {
  id: string
  name: string
}

const props = defineProps<{
  departments: Dept[]
  policies: DepartmentCallPolicyResponse[]
}>()

const emit = defineEmits<{
  (e: 'node-click', departmentId: string): void
  (e: 'edge-click', sourceId: string, targetId: string): void
  (e: 'add-edge'): void
}>()

const layouts = ref<{ nodes: Record<string, { x: number; y: number }> }>({ nodes: {} })

function arrangeCircle(count: number, radius: number): { x: number; y: number }[] {
  const points: { x: number; y: number }[] = []
  for (let i = 0; i < count; i++) {
    const angle = (2 * Math.PI * i) / count - Math.PI / 2
    points.push({
      x: Math.round(Math.cos(angle) * radius),
      y: Math.round(Math.sin(angle) * radius),
    })
  }
  return points
}

watch(
  () => props.departments,
  (deps) => {
    if (!deps.length) return
    const existing = layouts.value.nodes
    const hasExisting = Object.keys(existing).length > 0
    if (hasExisting) return
    const radius = Math.max(150, deps.length * 40)
    const points = arrangeCircle(deps.length, radius)
    const nodes: Record<string, { x: number; y: number }> = {}
    deps.forEach((d, i) => {
      nodes[d.id] = points[i]
    })
    layouts.value = { nodes }
  },
  { immediate: true },
)

const nodes = computed(() => {
  const map: Record<string, { name: string }> = {}
  for (const d of props.departments) {
    map[d.id] = { name: d.name }
  }
  return map
})

const pairPolicies = computed(() => {
  return props.policies.filter(p => p.departmentId && p.secondDepartmentId)
})

function edgeKey(a: string, b: string): string {
  return [a, b].sort().join('::')
}

const edges = computed(() => {
  const seen = new Set<string>()
  const map: Record<string, { source: string; target: string }> = {}
  for (const p of pairPolicies.value) {
    const k = edgeKey(p.departmentId!, p.secondDepartmentId!)
    if (seen.has(k)) continue
    seen.add(k)
    map[`edge_${k}`] = { source: p.departmentId!, target: p.secondDepartmentId! }
  }
  return map
})

function hasDepartmentOverride(deptId: string): boolean {
  return props.policies.some(
    p => p.departmentId === deptId && !p.secondDepartmentId,
  )
}

const configs = computed(() => ({
  view: {
    autoPanAndZoomOnLoad: 'fit-content' as const,
    minZoomLevel: 0.3,
    maxZoomLevel: 3,
  },
  node: {
    normal: {
      type: 'circle' as const,
      radius: 28,
      color: (node: Record<string, unknown>) => {
        const dept = props.departments.find(d => d.name === node.name)
        if (dept && hasDepartmentOverride(dept.id)) return '#3b82f6'
        return '#9ca3af'
      },
      strokeWidth: 2,
      strokeColor: '#ffffff',
    },
    hover: {
      color: '#2563eb',
    },
    label: {
      visible: true,
      fontSize: 12,
      color: '#374151',
      direction: 'south' as const,
      margin: 6,
    },
  },
  edge: {
    normal: {
      color: '#d1d5db',
      width: 2,
    },
    hover: {
      color: '#3b82f6',
      width: 3,
    },
    marker: {
      target: { type: 'none' as const },
      source: { type: 'none' as const },
    },
    label: {
      fontSize: 10,
      color: '#6b7280',
    },
  },
}))

const eventHandlers = {
  'node:click': ({ node }: { node: string; event: MouseEvent }) => {
    emit('node-click', node)
  },
  'edge:click': ({ edge, edges: edgeIds }: { edge?: string; edges: string[]; summarized: boolean; event: MouseEvent }) => {
    const id = edge ?? edgeIds[0]
    if (!id) return
    const e = edges.value[id]
    if (e) emit('edge-click', e.source, e.target)
  },
}
</script>

<template>
  <div class="relative w-full h-full">
    <v-network-graph
      class="w-full h-full"
      :nodes="nodes"
      :edges="edges"
      :configs="configs"
      :event-handlers="eventHandlers"
      v-model:layouts="layouts"
    >
      <template #override-node-label="{ nodeId, scale, text, x, y, config, textAnchor }">
        <text
          :x="x"
          :y="y"
          :font-size="config.fontSize * scale"
          :text-anchor="textAnchor"
          :fill="config.color"
          :dominant-baseline="'hanging'"
          class="select-none pointer-events-none"
        >{{ text }}</text>
      </template>
    </v-network-graph>

    <div class="absolute bottom-3 right-3">
      <button
        class="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700 shadow-md"
        @click="emit('add-edge')"
      >
        <Plus class="w-3.5 h-3.5" />
        Добавить связь
      </button>
    </div>
  </div>
</template>
