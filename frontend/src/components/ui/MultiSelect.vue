<script setup lang="ts">
import { ref, computed } from 'vue'
import { X } from 'lucide-vue-next'

export interface SelectOption {
  id: string
  label: string
  sublabel?: string
}

const props = withDefaults(defineProps<{
  options: SelectOption[]
  modelValue: string[]
  placeholder?: string
}>(), {
  placeholder: 'Поиск...',
})

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
}>()

const query = ref('')
const open = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)

const filtered = computed(() => {
  const q = query.value.toLowerCase().trim()
  const selected = new Set(props.modelValue)
  return props.options
    .filter(o => !selected.has(o.id))
    .filter(o => !q || o.label.toLowerCase().includes(q) || (o.sublabel?.toLowerCase().includes(q) ?? false))
})

const selectedItems = computed(() => {
  const map = new Map(props.options.map(o => [o.id, o]))
  return props.modelValue.map(id => map.get(id)).filter(Boolean) as SelectOption[]
})

function select(opt: SelectOption) {
  emit('update:modelValue', [...props.modelValue, opt.id])
  query.value = ''
  inputRef.value?.focus()
}

function remove(id: string) {
  emit('update:modelValue', props.modelValue.filter(v => v !== id))
}

function onFocus() {
  open.value = true
}

function close() {
  open.value = false
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Backspace' && !query.value && props.modelValue.length) {
    remove(props.modelValue[props.modelValue.length - 1])
  }
  if (e.key === 'Escape') {
    close()
    inputRef.value?.blur()
  }
}
</script>

<template>
  <div class="relative">
    <div
      class="flex flex-wrap items-center gap-1 min-h-[38px] bg-white px-2 py-1.5 border border-gray-300 rounded-lg text-sm focus-within:ring-2 focus-within:ring-primary-500 focus-within:border-primary-500 cursor-text"
      @click="inputRef?.focus()"
    >
      <span
        v-for="item in selectedItems"
        :key="item.id"
        class="inline-flex items-center gap-1 bg-primary-100 text-primary-700 text-xs font-medium pl-2 pr-1 py-0.5 rounded"
      >
        {{ item.label }}
        <button
          type="button"
          class="hover:bg-primary-200 rounded p-0.5 transition-colors"
          @click.stop="remove(item.id)"
        >
          <X class="w-3 h-3" />
        </button>
      </span>
      <input
        ref="inputRef"
        v-model="query"
        type="text"
        :placeholder="modelValue.length ? '' : placeholder"
        class="flex-1 min-w-[80px] outline-none bg-transparent text-sm"
        @focus="onFocus"
        @keydown="onKeydown"
      />
    </div>

    <div v-if="open" class="fixed inset-0 z-10" @click="close" />

    <div
      v-if="open && (filtered.length || query)"
      class="absolute left-0 right-0 top-full mt-1 bg-white border border-gray-200 rounded-lg shadow-lg z-20 max-h-52 overflow-auto"
    >
      <div v-if="!filtered.length" class="px-3 py-2 text-sm text-gray-400">
        Не найдено
      </div>
      <button
        v-for="opt in filtered"
        :key="opt.id"
        type="button"
        class="w-full text-left px-3 py-2 text-sm hover:bg-gray-50 transition-colors"
        @mousedown.prevent="select(opt)"
      >
        <span class="text-gray-900">{{ opt.label }}</span>
        <span v-if="opt.sublabel" class="ml-2 text-xs text-gray-400">{{ opt.sublabel }}</span>
      </button>
    </div>
  </div>
</template>
