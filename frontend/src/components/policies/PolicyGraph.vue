<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { VNetworkGraph } from 'v-network-graph'
import 'v-network-graph/lib/style.css'
import type { DepartmentCallPolicyResponse } from '@/types'
import { Plus, ZoomIn, ZoomOut, Maximize2, Info } from 'lucide-vue-next'

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

const graphRef = ref<InstanceType<typeof VNetworkGraph>>()
const zoomLevel = ref(1)
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
    if (Object.keys(layouts.value.nodes).length > 0) return
    const radius = Math.max(140, deps.length * 45)
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

const pairPolicies = computed(() =>
  props.policies.filter(p => p.departmentId && p.secondDepartmentId),
)

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

function shortName(name: string): string {
  if (name.length <= 16) return name
  return name.slice(0, 14) + '…'
}

const NODE_RADIUS = 20

function initials(name: string): string {
  const words = name.trim().split(/\s+/)
  if (words.length >= 2) return (words[0][0] + words[1][0]).toUpperCase()
  return name.slice(0, 2).toUpperCase()
}

const configs = computed(() => ({
  view: {
    autoPanAndZoomOnLoad: 'fit-content' as const,
    minZoomLevel: 0.2,
    maxZoomLevel: 4,
    grid: {
      visible: false,
    },
  },
  node: {
    normal: {
      type: 'circle' as const,
      radius: NODE_RADIUS,
      color: (node: Record<string, unknown>) => {
        const dept = props.departments.find(d => d.name === node.name)
        if (dept && hasDepartmentOverride(dept.id)) return '#3b82f6'
        return '#cbd5e1'
      },
      strokeWidth: 2,
      strokeColor: (node: Record<string, unknown>) => {
        const dept = props.departments.find(d => d.name === node.name)
        if (dept && hasDepartmentOverride(dept.id)) return '#2563eb'
        return '#94a3b8'
      },
    },
    hover: {
      radius: NODE_RADIUS + 2,
      color: '#2563eb',
      strokeColor: '#1d4ed8',
      strokeWidth: 2,
    },
    selected: {
      radius: NODE_RADIUS + 2,
      color: '#1d4ed8',
      strokeColor: '#1e40af',
      strokeWidth: 2,
    },
    focusring: {
      visible: false,
    },
    label: {
      visible: true,
      fontSize: 11,
      fontFamily: 'Inter, system-ui, sans-serif',
      color: '#374151',
      direction: 'south' as const,
      margin: 4,
    },
  },
  edge: {
    normal: {
      color: '#94a3b8',
      width: 1.5,
    },
    hover: {
      color: '#3b82f6',
      width: 2.5,
    },
    selected: {
      color: '#2563eb',
      width: 2.5,
    },
    marker: {
      target: { type: 'none' as const },
      source: { type: 'none' as const },
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

function doZoomIn() { graphRef.value?.zoomIn() }
function doZoomOut() { graphRef.value?.zoomOut() }
function doFit() { graphRef.value?.fitToContents({ margin: 0.1 }) }

const zoomPercent = computed(() => Math.round(zoomLevel.value * 100))
const totalOverrides = computed(() =>
  props.policies.filter(p => p.departmentId && !p.secondDepartmentId).length,
)
const totalPairs = computed(() => Object.keys(edges.value).length)
</script>

<template>
  <div class="relative w-full h-full bg-gradient-to-br from-slate-50 to-gray-100">
    <v-network-graph
      ref="graphRef"
      class="w-full h-full"
      :nodes="nodes"
      :edges="edges"
      :configs="configs"
      :event-handlers="eventHandlers"
      v-model:layouts="layouts"
      v-model:zoom-level="zoomLevel"
    >
      <defs>
        <filter id="nodeShadow" x="-30%" y="-30%" width="160%" height="160%">
          <feDropShadow dx="0" dy="1" stdDeviation="2" flood-color="#00000014" />
        </filter>
      </defs>

      <template #override-node="{ nodeId, scale, config }">
        <circle
          :r="config.radius * scale"
          :fill="config.color"
          :stroke="config.strokeColor"
          :stroke-width="config.strokeWidth * scale"
          filter="url(#nodeShadow)"
          class="cursor-pointer"
        />
        <text
          y="1"
          :font-size="11 * scale"
          font-family="Inter, system-ui, sans-serif"
          font-weight="600"
          text-anchor="middle"
          dominant-baseline="central"
          :fill="hasDepartmentOverride(nodeId) ? '#ffffff' : '#64748b'"
          class="select-none pointer-events-none"
        >{{ initials(departments.find(d => d.id === nodeId)?.name ?? '') }}</text>
      </template>

      <template #override-node-label="{ nodeId, scale, x, y, config, textAnchor }">
        <text
          :x="x"
          :y="y + 2 * scale"
          :font-size="config.fontSize * scale"
          :font-family="config.fontFamily"
          :text-anchor="textAnchor"
          :fill="config.color"
          dominant-baseline="hanging"
          class="select-none pointer-events-none"
        >{{ shortName(departments.find(d => d.id === nodeId)?.name ?? '') }}</text>
      </template>
    </v-network-graph>

    <!-- Zoom controls -->
    <div class="absolute top-3 right-3 flex flex-col items-center gap-1 bg-white/90 backdrop-blur-sm border border-gray-200 rounded-xl p-1.5 shadow-sm">
      <button
        class="p-1.5 rounded-lg hover:bg-gray-100 text-gray-600 transition-colors"
        title="Приблизить"
        @click="doZoomIn"
      ><ZoomIn class="w-4 h-4" /></button>

      <div class="text-[10px] font-medium text-gray-500 tabular-nums w-10 text-center">
        {{ zoomPercent }}%
      </div>

      <button
        class="p-1.5 rounded-lg hover:bg-gray-100 text-gray-600 transition-colors"
        title="Отдалить"
        @click="doZoomOut"
      ><ZoomOut class="w-4 h-4" /></button>

      <div class="w-6 border-t border-gray-200 my-0.5" />

      <button
        class="p-1.5 rounded-lg hover:bg-gray-100 text-gray-600 transition-colors"
        title="Показать всё"
        @click="doFit"
      ><Maximize2 class="w-4 h-4" /></button>
    </div>

    <!-- Legend -->
    <div class="absolute top-3 left-3 bg-white/90 backdrop-blur-sm border border-gray-200 rounded-xl px-3 py-2 shadow-sm space-y-1.5">
      <div class="flex items-center gap-2">
        <div class="w-2.5 h-2.5 rounded-full bg-blue-500 border border-blue-600 shrink-0" />
        <span class="text-[11px] text-gray-600">Настроено ({{ totalOverrides }})</span>
      </div>
      <div class="flex items-center gap-2">
        <div class="w-2.5 h-2.5 rounded-full bg-slate-300 border border-slate-400 shrink-0" />
        <span class="text-[11px] text-gray-600">По умолчанию</span>
      </div>
      <div v-if="totalPairs > 0" class="flex items-center gap-2">
        <div class="w-3 h-px bg-slate-400 shrink-0" />
        <span class="text-[11px] text-gray-600">Связи ({{ totalPairs }})</span>
      </div>
    </div>

    <!-- Hint -->
    <div class="absolute bottom-3 left-3 flex items-center gap-1.5 text-[11px] text-gray-400">
      <Info class="w-3.5 h-3.5" />
      Перетаскивайте узлы, колесо мыши для масштаба
    </div>

    <!-- Add edge button -->
    <div class="absolute bottom-3 right-3">
      <button
        class="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700 shadow-md transition-colors"
        @click="emit('add-edge')"
      >
        <Plus class="w-3.5 h-3.5" />
        Добавить связь
      </button>
    </div>
  </div>
</template>
