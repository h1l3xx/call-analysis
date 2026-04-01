<script setup lang="ts">
import { ref, computed } from 'vue'
import { Plus, Trash2, GripVertical, ChevronUp, ChevronDown, AlertCircle } from 'lucide-vue-next'
import type { CreateCriterionRequest } from '@/types'

const model = defineModel<CreateCriterionRequest[]>({ required: true })

const dragIndex = ref<number | null>(null)
const dropTargetIndex = ref<number | null>(null)
const showErrors = ref(false)

defineExpose({ validate })

function validate(): boolean {
  showErrors.value = true
  return !model.value.some((c) => !c.name.trim() || !c.description.trim())
}

function hasError(criterion: CreateCriterionRequest): { name: boolean; description: boolean } {
  if (!showErrors.value) return { name: false, description: false }
  return {
    name: !criterion.name.trim(),
    description: !criterion.description.trim(),
  }
}

function addCriterion() {
  model.value.push({
    orderNum: model.value.length + 1,
    name: '',
    description: '',
    groupType: 'required',
    weight: 1.0,
    scoringType: 'binary',
  })
}

function removeCriterion(index: number) {
  model.value.splice(index, 1)
  renumber()
}

function moveUp(index: number) {
  if (index <= 0) return
  swap(index, index - 1)
}

function moveDown(index: number) {
  if (index >= model.value.length - 1) return
  swap(index, index + 1)
}

function swap(from: number, to: number) {
  const item = model.value.splice(from, 1)[0]
  model.value.splice(to, 0, item)
  renumber()
}

function renumber() {
  model.value.forEach((c, i) => (c.orderNum = i + 1))
}

const invalidCount = computed(() =>
  model.value.filter((c) => !c.name.trim() || !c.description.trim()).length,
)

function onDragStart(index: number, e: DragEvent) {
  dragIndex.value = index
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', String(index))
  }
}

function onDragOver(index: number, e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
  dropTargetIndex.value = index
}

function onDragLeave() {
  dropTargetIndex.value = null
}

function onDrop(index: number, e: DragEvent) {
  e.preventDefault()
  if (dragIndex.value !== null && dragIndex.value !== index) {
    swap(dragIndex.value, index)
  }
  dragIndex.value = null
  dropTargetIndex.value = null
}

function onDragEnd() {
  dragIndex.value = null
  dropTargetIndex.value = null
}
</script>

<template>
  <div class="space-y-3">
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-medium text-gray-700">
        Критерии оценки
        <span v-if="model.length" class="text-gray-400 font-normal">({{ model.length }})</span>
      </h3>
      <button
        type="button"
        class="flex items-center gap-1.5 text-sm text-primary-600 hover:text-primary-700"
        @click="addCriterion"
      >
        <Plus class="w-4 h-4" />
        Добавить
      </button>
    </div>

    <div
      v-if="showErrors && invalidCount > 0"
      class="flex items-center gap-2 text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2"
    >
      <AlertCircle class="w-4 h-4 shrink-0" />
      Заполните название и описание у {{ invalidCount }} {{ invalidCount === 1 ? 'критерия' : 'критериев' }}
    </div>

    <div v-if="!model.length" class="text-center py-6 text-gray-400 text-sm">
      Нет критериев. Нажмите «Добавить» для создания.
    </div>

    <div
      v-for="(criterion, i) in model"
      :key="criterion.orderNum"
      draggable="true"
      class="rounded-lg p-4 space-y-3 border-2 transition-colors"
      :class="[
        dropTargetIndex === i && dragIndex !== i
          ? 'border-primary-400 bg-primary-50'
          : hasError(criterion).name || hasError(criterion).description
            ? 'border-red-300 bg-red-50/50'
            : 'border-transparent bg-gray-50',
        dragIndex === i ? 'opacity-50' : '',
      ]"
      @dragstart="onDragStart(i, $event)"
      @dragover="onDragOver(i, $event)"
      @dragleave="onDragLeave"
      @drop="onDrop(i, $event)"
      @dragend="onDragEnd"
    >
      <div class="flex items-center gap-2">
        <div class="flex flex-col shrink-0">
          <button
            type="button"
            :disabled="i === 0"
            class="text-gray-400 hover:text-gray-700 disabled:opacity-25 disabled:cursor-not-allowed"
            title="Переместить вверх"
            @click="moveUp(i)"
          >
            <ChevronUp class="w-4 h-4" />
          </button>
          <button
            type="button"
            :disabled="i === model.length - 1"
            class="text-gray-400 hover:text-gray-700 disabled:opacity-25 disabled:cursor-not-allowed"
            title="Переместить вниз"
            @click="moveDown(i)"
          >
            <ChevronDown class="w-4 h-4" />
          </button>
        </div>
        <GripVertical class="w-4 h-4 text-gray-400 shrink-0 cursor-grab active:cursor-grabbing" />
        <span class="text-xs text-gray-500 shrink-0">{{ criterion.orderNum }}.</span>
        <input
          v-model="criterion.name"
          placeholder="Название критерия *"
          class="flex-1 px-3 py-1.5 border rounded-md text-sm focus:ring-1 focus:ring-primary-500 outline-none"
          :class="hasError(criterion).name ? 'border-red-400 bg-red-50' : 'border-gray-300'"
        />
        <button type="button" class="text-gray-400 hover:text-danger-500" @click="removeCriterion(i)">
          <Trash2 class="w-4 h-4" />
        </button>
      </div>
      <textarea
        v-model="criterion.description"
        placeholder="Описание критерия *"
        rows="2"
        class="w-full px-3 py-1.5 border rounded-md text-sm focus:ring-1 focus:ring-primary-500 outline-none resize-none"
        :class="hasError(criterion).description ? 'border-red-400 bg-red-50' : 'border-gray-300'"
      />
      <div class="flex gap-3 flex-wrap items-center">
        <select
          v-model="criterion.groupType"
          class="appearance-auto bg-white px-2 py-1.5 border border-gray-300 rounded-md text-xs focus:ring-1 focus:ring-primary-500 outline-none"
        >
          <option value="required">Обязательный</option>
          <option value="optional">Опциональный</option>
        </select>
        <label class="flex items-center gap-1 text-xs text-gray-600">
          Вес:
          <input
            v-model.number="criterion.weight"
            type="number"
            step="0.5"
            min="0"
            max="10"
            class="w-16 px-2 py-1.5 border border-gray-300 rounded-md text-xs focus:ring-1 focus:ring-primary-500 outline-none"
          />
        </label>
      </div>
    </div>
  </div>
</template>
