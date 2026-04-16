<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { Download, FileDown, Calendar, Filter } from 'lucide-vue-next'
import { callsApi, managersApi } from '@/api'
import type { ManagerResponse } from '@/types'
import MultiSelect from '@/components/ui/MultiSelect.vue'
import type { SelectOption } from '@/components/ui/MultiSelect.vue'

const departments = ref<{ id: string; name: string }[]>([])
const allManagers = ref<ManagerResponse[]>([])
const exporting = ref(false)

const selectedDepartments = ref<string[]>([])
const selectedManagers = ref<string[]>([])
const status = ref('')
const callType = ref('')
const dateFrom = ref('')
const dateTo = ref('')
const search = ref('')

const departmentOptions = computed<SelectOption[]>(() =>
  departments.value.map(d => ({ id: d.id, label: d.name }))
)

const managerOptions = computed<SelectOption[]>(() =>
  allManagers.value.map(m => ({
    id: m.id,
    label: m.fullName,
    sublabel: m.departmentName || undefined,
  }))
)

const statusOptions = [
  { value: '', label: 'Все статусы' },
  { value: 'done', label: 'Завершён' },
  { value: 'failed', label: 'Ошибка' },
  { value: 'processing', label: 'В обработке' },
  { value: 'queued', label: 'В очереди' },
  { value: 'no_speech', label: 'Нет речи' },
]

const callTypeOptions = [
  { value: '', label: 'Все типы' },
  { value: 'internal', label: 'Внутренние' },
  { value: 'external', label: 'Внешние' },
]

const hasFilters = computed(() =>
  selectedDepartments.value.length || selectedManagers.value.length || status.value || callType.value || dateFrom.value || dateTo.value || search.value
)

onMounted(async () => {
  try {
    const [depts, mgrs] = await Promise.all([
      callsApi.departments(),
      managersApi.allActive(),
    ])
    departments.value = depts.data
    allManagers.value = mgrs
  } catch { /* ignore */ }
})

function resetFilters() {
  selectedDepartments.value = []
  selectedManagers.value = []
  status.value = ''
  callType.value = ''
  dateFrom.value = ''
  dateTo.value = ''
  search.value = ''
}

async function doExport() {
  if (exporting.value) return
  exporting.value = true
  try {
    const params: Record<string, string | number> = {}
    if (selectedDepartments.value.length) params.departmentIds = selectedDepartments.value.join(',')
    if (selectedManagers.value.length) params.managerIds = selectedManagers.value.join(',')
    if (status.value) params.status = status.value
    if (callType.value) params.callType = callType.value
    if (dateFrom.value) params.sinceMs = new Date(dateFrom.value).getTime()
    if (dateTo.value) params.untilMs = new Date(dateTo.value + 'T23:59:59').getTime()
    if (search.value.trim()) params.search = search.value.trim()
    await callsApi.exportCsv(params)
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="space-y-6 max-w-3xl">
    <div class="flex items-center gap-3">
      <FileDown class="w-6 h-6 text-primary-600" />
      <h1 class="text-2xl font-bold text-gray-900">Выгрузка данных</h1>
    </div>

    <div class="bg-white rounded-xl border border-gray-200 p-6 space-y-5">
      <div class="flex items-center gap-2 text-sm text-gray-500">
        <Filter class="w-4 h-4" />
        <span>Настройте фильтры для выгрузки</span>
      </div>

      <!-- Employees -->
      <div v-if="managerOptions.length">
        <label class="block text-sm font-medium text-gray-700 mb-1">Сотрудники</label>
        <MultiSelect
          v-model="selectedManagers"
          :options="managerOptions"
          placeholder="Выберите сотрудников..."
        />
      </div>

      <!-- Filters grid -->
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <!-- Departments -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Отделы</label>
          <MultiSelect
            v-model="selectedDepartments"
            :options="departmentOptions"
            placeholder="Все отделы..."
          />
        </div>

        <!-- Status -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Статус</label>
          <select
            v-model="status"
            class="w-full appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          >
            <option v-for="opt in statusOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
        </div>

        <!-- Call type -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Тип звонка</label>
          <select
            v-model="callType"
            class="w-full appearance-auto bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          >
            <option v-for="opt in callTypeOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
        </div>

        <!-- Search -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Поиск по файлу</label>
          <input
            v-model="search"
            type="text"
            placeholder="Имя файла..."
            class="w-full bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          />
        </div>

        <!-- Date from -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">
            <Calendar class="w-3.5 h-3.5 inline mr-1 text-gray-400" />
            Дата от
          </label>
          <input
            v-model="dateFrom"
            type="date"
            class="w-full bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          />
        </div>

        <!-- Date to -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">
            <Calendar class="w-3.5 h-3.5 inline mr-1 text-gray-400" />
            Дата до
          </label>
          <input
            v-model="dateTo"
            type="date"
            class="w-full bg-white px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
          />
        </div>
      </div>

      <!-- Actions -->
      <div class="flex items-center justify-between pt-3 border-t border-gray-100">
        <button
          v-if="hasFilters"
          class="text-sm text-gray-500 hover:text-gray-700 underline decoration-dotted"
          @click="resetFilters"
        >
          Сбросить фильтры
        </button>
        <span v-else></span>

        <button
          :disabled="exporting"
          class="flex items-center gap-2 px-5 py-2.5 bg-primary-600 text-white font-medium rounded-lg hover:bg-primary-700 disabled:opacity-50 transition-colors"
          @click="doExport"
        >
          <Download class="w-4 h-4" :class="{ 'animate-bounce': exporting }" />
          {{ exporting ? 'Выгрузка...' : 'Скачать CSV' }}
        </button>
      </div>
    </div>

    <p class="text-xs text-gray-400">
      Файл CSV содержит информацию о сотрудниках, оценках, транскрипциях и метриках разговоров.
      Выгрузка может занять некоторое время при большом количестве звонков.
    </p>
  </div>
</template>
