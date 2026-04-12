<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ChevronLeft, ChevronRight, Phone, Plus, Trash2, Star, Loader2 } from 'lucide-vue-next'
import { managersApi } from '@/api'
import type { AddPhoneRequest, ManagerPhoneResponse, ManagerResponse } from '@/types'
import { useAuthStore } from '@/stores/auth'
import { useFormatters } from '@/composables/useFormatters'

const auth = useAuthStore()
const { formatDate } = useFormatters()

const managers = ref<ManagerResponse[]>([])
const loading = ref(true)
const page = ref(1)
const totalPages = ref(1)

// Inline phone editing state
const expandedId = ref<string | null>(null)
const addingFor = ref<string | null>(null)
const newPhone = ref('')
const newLabel = ref('')
const savingPhone = ref(false)
const deletingPhoneId = ref<string | null>(null)

async function fetchManagers() {
  loading.value = true
  try {
    const { data } = await managersApi.list({ page: page.value, pageSize: 20 })
    if (Array.isArray(data)) {
      managers.value = data
      totalPages.value = 1
    } else {
      managers.value = data.items
      totalPages.value = data.totalPages
    }
  } finally {
    loading.value = false
  }
}

function toggleExpand(id: string) {
  expandedId.value = expandedId.value === id ? null : id
  addingFor.value = null
  newPhone.value = ''
  newLabel.value = ''
}

function startAdd(managerId: string) {
  addingFor.value = managerId
  newPhone.value = ''
  newLabel.value = ''
}

async function savePhone(managerId: string) {
  if (!newPhone.value.trim()) return
  savingPhone.value = true
  try {
    const req: AddPhoneRequest = {
      phoneNumber: newPhone.value.trim(),
      label: newLabel.value.trim() || undefined,
      isPrimary: false,
    }
    const { data } = await managersApi.addPhone(managerId, req)
    const mgr = managers.value.find(m => m.id === managerId)
    if (mgr) mgr.phoneNumbers.push(data)
    addingFor.value = null
    newPhone.value = ''
    newLabel.value = ''
  } finally {
    savingPhone.value = false
  }
}

async function deletePhone(managerId: string, phone: ManagerPhoneResponse) {
  deletingPhoneId.value = phone.id
  try {
    await managersApi.removePhone(managerId, phone.id)
    const mgr = managers.value.find(m => m.id === managerId)
    if (mgr) mgr.phoneNumbers = mgr.phoneNumbers.filter(p => p.id !== phone.id)
  } finally {
    deletingPhoneId.value = null
  }
}

function formatPhone(p: string): string {
  if (p.length === 11 && p.startsWith('7')) {
    return `+7 (${p.slice(1, 4)}) ${p.slice(4, 7)}-${p.slice(7, 9)}-${p.slice(9)}`
  }
  return p
}

onMounted(fetchManagers)
</script>

<template>
  <div class="space-y-5">
    <h1 class="text-2xl font-bold text-gray-900">
      {{ auth.isManager ? 'Мой профиль' : 'Менеджеры' }}
    </h1>

    <div class="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <div v-if="loading" class="p-12 text-center text-gray-400">Загрузка...</div>
      <table v-else class="w-full text-sm">
        <thead class="bg-gray-50 border-b border-gray-200">
          <tr>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Имя</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Email</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Отдел</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Телефоны</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Доб.</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Статус</th>
            <th class="text-left px-5 py-3 font-medium text-gray-600">Дата создания</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-if="!managers.length">
            <td colspan="7" class="px-5 py-12 text-center text-gray-400">Нет менеджеров</td>
          </tr>
          <template v-for="m in managers" :key="m.id">
            <tr class="hover:bg-gray-50 transition-colors">
              <td class="px-5 py-3 font-medium text-gray-900">{{ m.fullName }}</td>
              <td class="px-5 py-3 text-gray-600">{{ m.email }}</td>
              <td class="px-5 py-3 text-gray-600">{{ m.departmentName || '—' }}</td>

              <!-- Phones cell -->
              <td class="px-5 py-3">
                <div v-if="!m.phoneNumbers.length" class="text-gray-400 text-xs">—</div>
                <div v-else class="flex flex-wrap gap-1 items-center">
                  <span
                    v-for="p in m.phoneNumbers"
                    :key="p.id"
                    class="inline-flex items-center gap-1 text-xs font-mono px-2 py-0.5 rounded-full"
                    :class="p.isPrimary ? 'bg-blue-50 text-blue-700' : 'bg-gray-100 text-gray-600'"
                    :title="p.label || undefined"
                  >
                    <Star v-if="p.isPrimary" class="w-2.5 h-2.5 shrink-0" />
                    {{ formatPhone(p.phoneNumber) }}
                  </span>
                  <button
                    v-if="!auth.isManager"
                    class="inline-flex items-center gap-0.5 text-[10px] text-gray-400 hover:text-primary-600 transition-colors"
                    @click="toggleExpand(m.id)"
                  >
                    <Phone class="w-3 h-3" />
                    {{ expandedId === m.id ? 'Скрыть' : 'Изменить' }}
                  </button>
                </div>
                <button
                  v-if="!auth.isManager && !m.phoneNumbers.length"
                  class="mt-0.5 inline-flex items-center gap-1 text-[11px] text-primary-600 hover:text-primary-700"
                  @click="toggleExpand(m.id); startAdd(m.id)"
                >
                  <Plus class="w-3 h-3" />Добавить
                </button>
              </td>

              <td class="px-5 py-3 text-gray-600">{{ m.extension || '—' }}</td>
              <td class="px-5 py-3">
                <span
                  :class="[
                    'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium',
                    m.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600',
                  ]"
                >
                  {{ m.isActive ? 'Активен' : 'Неактивен' }}
                </span>
              </td>
              <td class="px-5 py-3 text-gray-500">{{ formatDate(m.createdAt) }}</td>
            </tr>

            <!-- Expanded phone management row -->
            <tr v-if="expandedId === m.id && !auth.isManager" class="bg-blue-50/40">
              <td colspan="7" class="px-5 py-3">
                <div class="space-y-2">
                  <div class="text-xs font-semibold text-gray-600 mb-1 flex items-center gap-1.5">
                    <Phone class="w-3.5 h-3.5" />
                    Телефоны — {{ m.fullName }}
                  </div>

                  <!-- Existing phones -->
                  <div v-if="m.phoneNumbers.length" class="flex flex-wrap gap-2">
                    <div
                      v-for="p in m.phoneNumbers"
                      :key="p.id"
                      class="flex items-center gap-2 bg-white border border-gray-200 rounded-lg px-3 py-1.5 text-sm"
                    >
                      <Star v-if="p.isPrimary" class="w-3 h-3 text-blue-500 shrink-0" title="Основной" />
                      <span class="font-mono text-xs">{{ formatPhone(p.phoneNumber) }}</span>
                      <span v-if="p.label" class="text-[10px] text-gray-400">{{ p.label }}</span>
                      <button
                        class="text-gray-300 hover:text-red-500 transition-colors"
                        :disabled="deletingPhoneId === p.id"
                        @click="deletePhone(m.id, p)"
                      >
                        <Loader2 v-if="deletingPhoneId === p.id" class="w-3.5 h-3.5 animate-spin" />
                        <Trash2 v-else class="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </div>

                  <!-- Add form -->
                  <div v-if="addingFor === m.id" class="flex items-center gap-2 mt-1">
                    <input
                      v-model="newPhone"
                      type="text"
                      placeholder="79001234567"
                      class="w-36 px-2.5 py-1.5 text-xs border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-400 outline-none font-mono"
                      @keyup.enter="savePhone(m.id)"
                    />
                    <input
                      v-model="newLabel"
                      type="text"
                      placeholder="Метка (необяз.)"
                      class="w-32 px-2.5 py-1.5 text-xs border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-400 outline-none"
                      @keyup.enter="savePhone(m.id)"
                    />
                    <button
                      class="flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700 disabled:opacity-50"
                      :disabled="savingPhone || !newPhone.trim()"
                      @click="savePhone(m.id)"
                    >
                      <Loader2 v-if="savingPhone" class="w-3 h-3 animate-spin" />
                      <span v-else>Сохранить</span>
                    </button>
                    <button
                      class="text-xs text-gray-400 hover:text-gray-600"
                      @click="addingFor = null"
                    >Отмена</button>
                  </div>
                  <button
                    v-else
                    class="flex items-center gap-1 text-xs text-primary-600 hover:text-primary-700 mt-1"
                    @click="startAdd(m.id)"
                  >
                    <Plus class="w-3.5 h-3.5" />
                    Добавить номер
                  </button>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <div v-if="totalPages > 1" class="flex items-center justify-center gap-2">
      <button
        :disabled="page <= 1"
        class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
        @click="page--; fetchManagers()"
      >
        <ChevronLeft class="w-4 h-4" />
      </button>
      <span class="text-sm text-gray-600">{{ page }} / {{ totalPages }}</span>
      <button
        :disabled="page >= totalPages"
        class="p-2 rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
        @click="page++; fetchManagers()"
      >
        <ChevronRight class="w-4 h-4" />
      </button>
    </div>
  </div>
</template>
