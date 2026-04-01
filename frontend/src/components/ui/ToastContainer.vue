<script setup lang="ts">
import { useToast } from '@/composables/useToast'
import { X, CheckCircle2, AlertCircle, Info } from 'lucide-vue-next'

const { toasts } = useToast()

const icons = { success: CheckCircle2, error: AlertCircle, info: Info }
const colors = {
  success: 'bg-green-50 border-green-200 text-green-800',
  error: 'bg-red-50 border-red-200 text-red-800',
  info: 'bg-blue-50 border-blue-200 text-blue-800',
}
const iconColors = {
  success: 'text-green-500',
  error: 'text-red-500',
  info: 'text-blue-500',
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed top-4 right-4 z-[100] space-y-2 max-w-sm w-full pointer-events-none">
      <TransitionGroup name="toast">
        <div
          v-for="toast in toasts"
          :key="toast.id"
          :class="['flex items-center gap-3 px-4 py-3 rounded-lg border shadow-lg pointer-events-auto text-sm', colors[toast.type]]"
        >
          <component :is="icons[toast.type]" :class="['w-5 h-5 shrink-0', iconColors[toast.type]]" />
          <span class="flex-1">{{ toast.message }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-enter-active { transition: all 0.3s ease; }
.toast-leave-active { transition: all 0.2s ease; }
.toast-enter-from { opacity: 0; transform: translateX(20px); }
.toast-leave-to { opacity: 0; transform: translateX(20px); }
</style>
