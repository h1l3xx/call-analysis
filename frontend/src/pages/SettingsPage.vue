<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { telegramApi } from '@/api'
import { MessageCircle, Link2, Unlink, Copy, Check, RefreshCw, Loader2 } from 'lucide-vue-next'
import DepartmentLeadsManager from '@/components/settings/DepartmentLeadsManager.vue'

const auth = useAuthStore()

const loading = ref(true)
const linked = ref(false)
const linkCode = ref<string | null>(null)
const codeTtl = ref(5)
const generating = ref(false)
const unlinking = ref(false)
const copied = ref(false)

onMounted(async () => {
  try {
    const { data } = await telegramApi.getStatus()
    linked.value = data.linked
    linkCode.value = data.pendingCode
  } finally {
    loading.value = false
  }
})

async function generateCode() {
  generating.value = true
  try {
    const { data } = await telegramApi.generateLinkCode()
    linkCode.value = data.code
    codeTtl.value = data.ttlMinutes
  } finally {
    generating.value = false
  }
}

async function unlinkTelegram() {
  unlinking.value = true
  try {
    await telegramApi.unlink()
    linked.value = false
    linkCode.value = null
  } finally {
    unlinking.value = false
  }
}

function copyCode() {
  if (!linkCode.value) return
  navigator.clipboard.writeText(linkCode.value)
  copied.value = true
  setTimeout(() => { copied.value = false }, 2000)
}

async function refreshStatus() {
  const { data } = await telegramApi.getStatus()
  linked.value = data.linked
  if (data.linked) linkCode.value = null
}
</script>

<template>
  <div class="space-y-6">
    <h1 class="text-2xl font-bold text-gray-900">Настройки</h1>

    <!-- Profile info -->
    <div class="bg-white rounded-xl border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Профиль</h2>
      <div class="space-y-3">
        <div class="flex justify-between text-sm">
          <span class="text-gray-500">Имя</span>
          <span class="font-medium text-gray-900">{{ auth.user?.fullName }}</span>
        </div>
        <div class="flex justify-between text-sm">
          <span class="text-gray-500">Email</span>
          <span class="font-medium text-gray-900">{{ auth.user?.email }}</span>
        </div>
        <div class="flex justify-between text-sm">
          <span class="text-gray-500">Роль</span>
          <span class="font-medium text-gray-900">{{ auth.user?.role }}</span>
        </div>
      </div>
    </div>

    <!-- Telegram section -->
    <div class="bg-white rounded-xl border border-gray-200 p-6">
      <div class="flex items-center gap-3 mb-4">
        <div class="w-10 h-10 rounded-full bg-blue-50 flex items-center justify-center">
          <MessageCircle class="w-5 h-5 text-blue-600" />
        </div>
        <div>
          <h2 class="text-lg font-semibold text-gray-900">Telegram</h2>
          <p class="text-sm text-gray-500">Получайте отчёты прямо в Telegram</p>
        </div>
      </div>

      <div v-if="loading" class="flex items-center gap-2 text-sm text-gray-500">
        <Loader2 class="w-4 h-4 animate-spin" /> Загрузка...
      </div>

      <!-- Linked state -->
      <div v-else-if="linked" class="space-y-4">
        <div class="flex items-center gap-2 px-4 py-3 bg-green-50 border border-green-200 rounded-lg">
          <Check class="w-5 h-5 text-green-600" />
          <span class="text-sm font-medium text-green-800">Telegram привязан</span>
        </div>
        <p class="text-sm text-gray-600">
          Вы получаете ежедневные и еженедельные отчёты.
          Отвяжите Telegram, если хотите прекратить получать уведомления.
        </p>
        <button
          class="flex items-center gap-2 px-4 py-2 text-sm font-medium text-red-600 bg-red-50 border border-red-200 rounded-lg hover:bg-red-100 transition-colors"
          :disabled="unlinking"
          @click="unlinkTelegram"
        >
          <Loader2 v-if="unlinking" class="w-4 h-4 animate-spin" />
          <Unlink v-else class="w-4 h-4" />
          Отвязать Telegram
        </button>
      </div>

      <!-- Not linked -->
      <div v-else class="space-y-4">
        <p class="text-sm text-gray-600">
          Привяжите Telegram, чтобы получать персональные отчёты по звонкам.
        </p>

        <!-- Show code if generated -->
        <div v-if="linkCode" class="space-y-3">
          <div class="p-4 bg-blue-50 border border-blue-200 rounded-lg">
            <p class="text-sm text-blue-800 mb-2">Ваш код привязки (действует {{ codeTtl }} мин):</p>
            <div class="flex items-center gap-2">
              <code class="flex-1 text-2xl font-mono font-bold tracking-widest text-blue-900 text-center">
                {{ linkCode }}
              </code>
              <button
                class="p-2 rounded-lg hover:bg-blue-100 transition-colors"
                title="Скопировать"
                @click="copyCode"
              >
                <Check v-if="copied" class="w-5 h-5 text-green-600" />
                <Copy v-else class="w-5 h-5 text-blue-600" />
              </button>
            </div>
          </div>

          <div class="text-sm text-gray-600 space-y-1">
            <p><b>1.</b> Откройте Telegram-бота Malikov</p>
            <p><b>2.</b> Отправьте боту команду: <code class="px-1.5 py-0.5 bg-gray-100 rounded text-xs font-mono">/link {{ linkCode }}</code></p>
            <p><b>3.</b> Или просто отправьте код <code class="px-1.5 py-0.5 bg-gray-100 rounded text-xs font-mono">{{ linkCode }}</code></p>
          </div>

          <div class="flex gap-2">
            <button
              class="flex items-center gap-2 px-3 py-2 text-sm text-gray-600 bg-gray-50 border border-gray-200 rounded-lg hover:bg-gray-100 transition-colors"
              @click="refreshStatus"
            >
              <RefreshCw class="w-4 h-4" />
              Проверить статус
            </button>
            <button
              class="flex items-center gap-2 px-3 py-2 text-sm text-gray-600 bg-gray-50 border border-gray-200 rounded-lg hover:bg-gray-100 transition-colors"
              :disabled="generating"
              @click="generateCode"
            >
              <RefreshCw class="w-4 h-4" />
              Новый код
            </button>
          </div>
        </div>

        <!-- No code yet -->
        <button
          v-else
          class="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors"
          :disabled="generating"
          @click="generateCode"
        >
          <Loader2 v-if="generating" class="w-4 h-4 animate-spin" />
          <Link2 v-else class="w-4 h-4" />
          Привязать Telegram
        </button>
      </div>
    </div>

    <!-- Department leads (CLIENT_ADMIN only) -->
    <DepartmentLeadsManager v-if="auth.isClientAdmin" />

    <!-- Report info -->
    <div class="bg-gray-50 rounded-xl border border-gray-200 p-6">
      <h3 class="text-sm font-semibold text-gray-700 mb-2">Какие отчёты вы получите?</h3>
      <ul class="text-sm text-gray-600 space-y-1.5">
        <li v-if="auth.isManager">
          <b>Менеджер:</b> ваша личная статистика — количество звонков, средний балл, лучший и худший звонок
        </li>
        <li v-if="auth.isTeamLead">
          <b>Руководитель отдела:</b> сводка по каждому курируемому отделу — рейтинг менеджеров, средний балл, динамика
        </li>
        <li v-if="auth.isClientAdmin">
          <b>Администратор:</b> сводка по всей компании — все отделы, топ-менеджеры, общая динамика
        </li>
        <li class="text-gray-500">Отчёты отправляются ежедневно утром и еженедельно по понедельникам.</li>
      </ul>
    </div>
  </div>
</template>
