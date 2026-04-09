<script setup lang="ts">
import { ref } from 'vue'
import { X, Plus } from 'lucide-vue-next'

interface Dept {
  id: string
  name: string
}

const props = defineProps<{
  visible: boolean
  departments: Dept[]
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'add', sourceId: string, targetId: string): void
}>()

const sourceId = ref('')
const targetId = ref('')

function onAdd() {
  if (!sourceId.value || !targetId.value || sourceId.value === targetId.value) return
  emit('add', sourceId.value, targetId.value)
  sourceId.value = ''
  targetId.value = ''
}
</script>

<template>
  <teleport to="body">
    <div
      v-if="visible"
      class="fixed inset-0 z-50 flex items-center justify-center"
    >
      <div class="absolute inset-0 bg-black/40" @click="emit('close')" />
      <div class="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4">
        <div class="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <h2 class="text-lg font-semibold text-gray-900">Новая связь между отделами</h2>
          <button class="p-1.5 rounded-lg hover:bg-gray-100 text-gray-400" @click="emit('close')">
            <X class="w-5 h-5" />
          </button>
        </div>

        <div class="p-6 space-y-4">
          <p class="text-sm text-gray-500">
            Выберите два отдела для настройки политик внутренних звонков между ними.
          </p>

          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Отдел 1</label>
            <select
              v-model="sourceId"
              class="w-full appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
            >
              <option value="">Выберите отдел</option>
              <option
                v-for="d in departments"
                :key="d.id"
                :value="d.id"
                :disabled="d.id === targetId"
              >{{ d.name }}</option>
            </select>
          </div>

          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Отдел 2</label>
            <select
              v-model="targetId"
              class="w-full appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
            >
              <option value="">Выберите отдел</option>
              <option
                v-for="d in departments"
                :key="d.id"
                :value="d.id"
                :disabled="d.id === sourceId"
              >{{ d.name }}</option>
            </select>
          </div>
        </div>

        <div class="flex justify-end gap-2 px-6 py-4 border-t border-gray-100">
          <button
            class="px-4 py-2 text-sm font-medium text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200"
            @click="emit('close')"
          >
            Отмена
          </button>
          <button
            class="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700 disabled:opacity-50"
            :disabled="!sourceId || !targetId || sourceId === targetId"
            @click="onAdd"
          >
            <Plus class="w-4 h-4" />
            Создать
          </button>
        </div>
      </div>
    </div>
  </teleport>
</template>
