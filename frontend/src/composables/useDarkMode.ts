import { ref, watch, onMounted } from 'vue'

const isDark = ref(false)

export function useDarkMode() {
  function toggle() {
    isDark.value = !isDark.value
  }

  function apply() {
    document.documentElement.classList.toggle('dark', isDark.value)
    localStorage.setItem('theme', isDark.value ? 'dark' : 'light')
  }

  onMounted(() => {
    const stored = localStorage.getItem('theme')
    if (stored === 'dark') isDark.value = true
    else if (stored === 'light') isDark.value = false
    else isDark.value = window.matchMedia('(prefers-color-scheme: dark)').matches
    apply()
  })

  watch(isDark, apply)

  return { isDark, toggle }
}
