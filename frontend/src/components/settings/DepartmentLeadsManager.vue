<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { managersApi, departmentLeadsApi, usersApi } from '@/api'
import type { ManagerResponse } from '@/types'
import type { DepartmentLeadResponse, UserSearchResponse } from '@/api/telegram'
import { UserPlus, Trash2, Building2, Loader2, Search, X } from 'lucide-vue-next'

interface Department {
  id: string
  name: string
}

const loading = ref(true)
const departments = ref<Department[]>([])
const leadsByDept = ref<Record<string, DepartmentLeadResponse[]>>({})

const selectedDept = ref<string>('')
const assigning = ref(false)
const removing = ref<string | null>(null)

// ── User search / autocomplete ─────────────────────────────────────────────
const searchQuery = ref('')
const searchResults = ref<UserSearchResponse[]>([])
const selectedUser = ref<UserSearchResponse | null>(null)
const searchLoading = ref(false)
const showDropdown = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | null = null

function onSearchInput() {
  selectedUser.value = null
  if (searchTimer) clearTimeout(searchTimer)
  const q = searchQuery.value.trim()
  if (q.length < 2) {
    searchResults.value = []
    showDropdown.value = false
    return
  }
  searchTimer = setTimeout(async () => {
    searchLoading.value = true
    try {
      const { data } = await usersApi.search(q)
      searchResults.value = data
      showDropdown.value = data.length > 0
    } finally {
      searchLoading.value = false
    }
  }, 250)
}

function selectUser(u: UserSearchResponse) {
  selectedUser.value = u
  searchQuery.value = u.fullName
  showDropdown.value = false
}

function clearUser() {
  selectedUser.value = null
  searchQuery.value = ''
  searchResults.value = []
  showDropdown.value = false
}

function onBlur() {
  // Небольшая задержка, чтобы клик по опции успел сработать
  setTimeout(() => { showDropdown.value = false }, 150)
}

onBeforeUnmount(() => { if (searchTimer) clearTimeout(searchTimer) })

// ── Data loading ───────────────────────────────────────────────────────────
onMounted(async () => {
  try {
    const { data } = await managersApi.list({ pageSize: 1000 })
    const mgrs: ManagerResponse[] = Array.isArray(data) ? data : data.items

    const deptMap = new Map<string, string>()
    for (const m of mgrs) {
      if (m.departmentId && m.departmentName) {
        deptMap.set(m.departmentId, m.departmentName)
      }
    }
    departments.value = [...deptMap.entries()].map(([id, name]) => ({ id, name }))

    for (const dept of departments.value) {
      try {
        const { data: leads } = await departmentLeadsApi.list(dept.id)
        leadsByDept.value[dept.id] = leads
      } catch {
        leadsByDept.value[dept.id] = []
      }
    }
  } finally {
    loading.value = false
  }
})

// ── Actions ────────────────────────────────────────────────────────────────
async function assignLead() {
  if (!selectedDept.value || !selectedUser.value) return
  assigning.value = true
  try {
    await departmentLeadsApi.assign(selectedDept.value, selectedUser.value.id)
    const { data: leads } = await departmentLeadsApi.list(selectedDept.value)
    leadsByDept.value[selectedDept.value] = leads
    clearUser()
  } finally {
    assigning.value = false
  }
}

async function removeLead(deptId: string, userId: string) {
  removing.value = `${deptId}:${userId}`
  try {
    await departmentLeadsApi.remove(deptId, userId)
    leadsByDept.value[deptId] = (leadsByDept.value[deptId] || []).filter(l => l.userId !== userId)
  } finally {
    removing.value = null
  }
}
</script>

<template>
  <div class="bg-white rounded-xl border border-gray-200 p-6">
    <div class="flex items-center gap-3 mb-4">
      <div class="w-10 h-10 rounded-full bg-purple-50 flex items-center justify-center">
        <Building2 class="w-5 h-5 text-purple-600" />
      </div>
      <div>
        <h2 class="text-lg font-semibold text-gray-900">Руководители отделов</h2>
        <p class="text-sm text-gray-500">Назначьте тимлидов на отделы для получения отчётов</p>
      </div>
    </div>

    <div v-if="loading" class="flex items-center gap-2 text-sm text-gray-500">
      <Loader2 class="w-4 h-4 animate-spin" /> Загрузка...
    </div>

    <div v-else class="space-y-4">
      <!-- Assign form -->
      <div class="flex gap-2 flex-wrap items-end">

        <!-- Отдел -->
        <div class="flex-1 min-w-[150px]">
          <label class="block text-xs text-gray-500 mb-1">Отдел</label>
          <select
            v-model="selectedDept"
            class="appearance-auto bg-white w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-1 focus:ring-primary-500 outline-none"
          >
            <option value="">Выберите отдел</option>
            <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </div>

        <!-- Поиск пользователя (autocomplete) -->
        <div class="flex-1 min-w-[200px] relative">
          <label class="block text-xs text-gray-500 mb-1">Тимлид</label>
          <div class="relative">
            <Search class="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-400 pointer-events-none" />
            <input
              v-model="searchQuery"
              :class="[
                'w-full pl-8 pr-7 py-2 border rounded-lg text-sm outline-none transition-colors',
                selectedUser
                  ? 'border-purple-400 bg-purple-50 text-purple-900 focus:ring-1 focus:ring-purple-400'
                  : 'border-gray-300 focus:ring-1 focus:ring-primary-500',
              ]"
              placeholder="Поиск по имени или email..."
              autocomplete="off"
              @input="onSearchInput"
              @focus="showDropdown = searchResults.length > 0"
              @blur="onBlur"
            />
            <button
              v-if="searchQuery"
              class="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
              tabindex="-1"
              @mousedown.prevent="clearUser"
            >
              <X class="w-3.5 h-3.5" />
            </button>
            <Loader2 v-if="searchLoading" class="absolute right-2 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-400 animate-spin" />
          </div>

          <!-- Dropdown -->
          <div
            v-if="showDropdown && searchResults.length"
            class="absolute z-20 top-full mt-1 w-full bg-white border border-gray-200 rounded-lg shadow-lg overflow-hidden"
          >
            <button
              v-for="u in searchResults"
              :key="u.id"
              class="w-full text-left px-3 py-2 hover:bg-purple-50 transition-colors flex items-center justify-between gap-2"
              @mousedown.prevent="selectUser(u)"
            >
              <div>
                <p class="text-sm font-medium text-gray-900">{{ u.fullName }}</p>
                <p class="text-xs text-gray-500">{{ u.email }}</p>
              </div>
              <span class="text-[10px] px-1.5 py-0.5 rounded bg-purple-100 text-purple-700 shrink-0">
                Тимлид
              </span>
            </button>
          </div>

          <!-- Выбран пользователь -->
          <p v-if="selectedUser" class="mt-1 text-xs text-purple-600">
            ✓ {{ selectedUser.email }}
          </p>
        </div>

        <button
          class="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-purple-600 rounded-lg hover:bg-purple-700 transition-colors disabled:opacity-50"
          :disabled="!selectedDept || !selectedUser || assigning"
          @click="assignLead"
        >
          <Loader2 v-if="assigning" class="w-4 h-4 animate-spin" />
          <UserPlus v-else class="w-4 h-4" />
          Назначить
        </button>
      </div>

      <!-- Departments with leads -->
      <div v-for="dept in departments" :key="dept.id" class="border border-gray-100 rounded-lg p-4">
        <h3 class="text-sm font-semibold text-gray-800 mb-2">{{ dept.name }}</h3>
        <div v-if="!leadsByDept[dept.id]?.length" class="text-xs text-gray-400">
          Нет назначенных руководителей
        </div>
        <div v-else class="space-y-1.5">
          <div
            v-for="lead in leadsByDept[dept.id]"
            :key="lead.userId"
            class="flex items-center justify-between px-3 py-2 bg-gray-50 rounded-lg"
          >
            <div class="text-sm">
              <span class="font-medium text-gray-900">{{ lead.fullName }}</span>
              <span class="text-gray-500 ml-2">{{ lead.email }}</span>
            </div>
            <button
              class="p-1 text-gray-400 hover:text-red-500 transition-colors"
              :disabled="removing === `${dept.id}:${lead.userId}`"
              @click="removeLead(dept.id, lead.userId)"
            >
              <Loader2 v-if="removing === `${dept.id}:${lead.userId}`" class="w-4 h-4 animate-spin" />
              <Trash2 v-else class="w-4 h-4" />
            </button>
          </div>
        </div>
      </div>

      <p v-if="!departments.length" class="text-sm text-gray-400">
        Нет отделов. Создайте отделы и добавьте менеджеров.
      </p>
    </div>
  </div>
</template>
