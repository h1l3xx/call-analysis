<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { LogIn } from 'lucide-vue-next'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const email = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function handleSubmit() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(email.value, password.value)
    if (auth.isSuperAdmin) {
      router.push('/admin/tenants')
    } else {
      router.push((route.query.redirect as string) || '/dashboard')
    }
  } catch (e: any) {
    const serverMsg = e.response?.data?.error
    if (e.response?.status === 401) {
      error.value = 'Неверный email или пароль'
    } else if (e.response?.status === 403) {
      error.value = 'Аккаунт деактивирован'
    } else if (serverMsg) {
      error.value = serverMsg
    } else {
      error.value = 'Ошибка авторизации. Попробуйте позже.'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="w-full max-w-md">
    <div class="login-card rounded-2xl shadow-xl p-8">
      <div class="text-center mb-8">
        <h1 class="text-3xl font-bold login-title">Caller</h1>
        <p class="mt-2 login-subtitle">Аналитика звонков</p>
      </div>

      <form @submit.prevent="handleSubmit" class="space-y-5">
        <div v-if="error" class="login-error text-sm rounded-lg px-4 py-3 flex items-center gap-2">
          <svg class="w-4 h-4 shrink-0" fill="currentColor" viewBox="0 0 20 20">
            <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd" />
          </svg>
          {{ error }}
        </div>

        <div>
          <label class="block text-sm font-medium login-label mb-1.5">Email</label>
          <input
            v-model="email"
            type="email"
            required
            autofocus
            placeholder="user@example.com"
            class="login-input w-full px-4 py-2.5 border rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none transition-shadow"
          />
        </div>

        <div>
          <label class="block text-sm font-medium login-label mb-1.5">Пароль</label>
          <input
            v-model="password"
            type="password"
            required
            placeholder="••••••••"
            class="login-input w-full px-4 py-2.5 border rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none transition-shadow"
          />
        </div>

        <button
          type="submit"
          :disabled="loading"
          class="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-primary-600 text-white font-medium rounded-lg text-sm hover:bg-primary-700 focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          <LogIn class="w-4 h-4" />
          {{ loading ? 'Вход...' : 'Войти' }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-card {
  background-color: #ffffff;
}
.login-title {
  color: #111827;
}
.login-subtitle {
  color: #6b7280;
}
.login-label {
  color: #374151;
}
.login-input {
  background-color: #ffffff;
  border-color: #d1d5db;
  color: #111827;
}
.login-error {
  background-color: #fef2f2;
  color: #b91c1c;
  border: 1px solid #fecaca;
  animation: shake 0.4s ease-in-out;
}
@keyframes shake {
  0%, 100% { transform: translateX(0); }
  20% { transform: translateX(-6px); }
  40% { transform: translateX(6px); }
  60% { transform: translateX(-4px); }
  80% { transform: translateX(4px); }
}
</style>
