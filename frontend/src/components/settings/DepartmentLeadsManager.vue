<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { managersApi, departmentLeadsApi } from '@/api'
import type { ManagerResponse } from '@/types'
import type { DepartmentLeadResponse } from '@/api/telegram'
import { UserPlus, Trash2, Building2, Loader2 } from 'lucide-vue-next'

interface Department {
  id: string
  name: string
}

const loading = ref(true)
const departments = ref<Department[]>([])
const leadsByDept = ref<Record<string, DepartmentLeadResponse[]>>({})
const teamLeads = ref<ManagerResponse[]>([])

const selectedDept = ref<string>('')
const selectedUser = ref<string>('')
const assigning = ref(false)
const removing = ref<string | null>(null)

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

async function assignLead() {
  if (!selectedDept.value || !selectedUser.value) return
  assigning.value = true
  try {
    await departmentLeadsApi.assign(selectedDept.value, selectedUser.value)
    const { data: leads } = await departmentLeadsApi.list(selectedDept.value)
    leadsByDept.value[selectedDept.value] = leads
    selectedUser.value = ''
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
        <div class="flex-1 min-w-[150px]">
          <label class="block text-xs text-gray-500 mb-1">ID пользователя (TEAM_LEAD)</label>
          <input
            v-model="selectedUser"
            placeholder="UUID тимлида"
            class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-1 focus:ring-primary-500 outline-none"
          />
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
