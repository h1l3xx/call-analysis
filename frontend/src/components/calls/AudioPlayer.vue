<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Play, Pause, Volume2, VolumeX, AlertCircle } from 'lucide-vue-next'
import client from '@/api/client'

const props = defineProps<{
  callId: string
}>()

const audioRef = ref<HTMLAudioElement | null>(null)
const blobUrl = ref<string | null>(null)
const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const playbackRate = ref(1)
const isMuted = ref(false)
const hasError = ref(false)
const isLoading = ref(true)

const speeds = [0.5, 0.75, 1, 1.25, 1.5, 2]

async function loadAudio() {
  try {
    const response = await client.get(`/api/v1/calls/${props.callId}/audio`, {
      responseType: 'blob',
      timeout: 120_000,
    })
    const blob = response.data as Blob
    blobUrl.value = URL.createObjectURL(blob)
  } catch {
    hasError.value = true
    isLoading.value = false
  }
}

function togglePlay() {
  if (!audioRef.value) return
  if (isPlaying.value) {
    audioRef.value.pause()
  } else {
    audioRef.value.play()
  }
}

function toggleMute() {
  if (!audioRef.value) return
  audioRef.value.muted = !audioRef.value.muted
  isMuted.value = audioRef.value.muted
}

function setSpeed(rate: number) {
  if (!audioRef.value) return
  audioRef.value.playbackRate = rate
  playbackRate.value = rate
}

function seek(e: Event) {
  if (!audioRef.value) return
  const val = parseFloat((e.target as HTMLInputElement).value)
  audioRef.value.currentTime = val
  currentTime.value = val
}

function formatTime(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}

onMounted(async () => {
  await loadAudio()

  const audio = audioRef.value
  if (!audio) return

  audio.addEventListener('loadedmetadata', () => {
    duration.value = audio.duration
    isLoading.value = false
  })
  audio.addEventListener('timeupdate', () => {
    currentTime.value = audio.currentTime
  })
  audio.addEventListener('play', () => { isPlaying.value = true })
  audio.addEventListener('pause', () => { isPlaying.value = false })
  audio.addEventListener('ended', () => { isPlaying.value = false })
  audio.addEventListener('error', () => {
    hasError.value = true
    isLoading.value = false
  })
})

onUnmounted(() => {
  if (blobUrl.value) URL.revokeObjectURL(blobUrl.value)
})
</script>

<template>
  <div v-if="hasError" class="flex items-center gap-2 text-sm text-gray-400 py-2">
    <AlertCircle class="w-4 h-4" />
    Запись недоступна или удалена
  </div>

  <div v-else class="flex items-center gap-3 bg-gray-50 rounded-xl px-4 py-3">
    <audio ref="audioRef" :src="blobUrl ?? undefined" preload="metadata" />

    <button
      @click="togglePlay"
      class="flex-shrink-0 w-9 h-9 flex items-center justify-center rounded-full bg-primary-600 text-white hover:bg-primary-700 transition-colors"
      :disabled="isLoading"
    >
      <Pause v-if="isPlaying" class="w-4 h-4" />
      <Play v-else class="w-4 h-4 ml-0.5" />
    </button>

    <span class="text-xs text-gray-500 tabular-nums w-10 text-right">{{ formatTime(currentTime) }}</span>

    <input
      type="range"
      :min="0"
      :max="duration || 0"
      :value="currentTime"
      step="0.1"
      @input="seek"
      class="flex-1 h-1.5 rounded-full appearance-none bg-gray-200 accent-primary-600 cursor-pointer"
      :disabled="isLoading"
    />

    <span class="text-xs text-gray-500 tabular-nums w-10">{{ formatTime(duration) }}</span>

    <button @click="toggleMute" class="text-gray-400 hover:text-gray-600 transition-colors">
      <VolumeX v-if="isMuted" class="w-4 h-4" />
      <Volume2 v-else class="w-4 h-4" />
    </button>

    <div class="flex gap-1">
      <button
        v-for="speed in speeds"
        :key="speed"
        @click="setSpeed(speed)"
        :class="[
          'text-xs px-1.5 py-0.5 rounded transition-colors',
          playbackRate === speed
            ? 'bg-primary-100 text-primary-700 font-medium'
            : 'text-gray-400 hover:text-gray-600',
        ]"
      >
        {{ speed }}x
      </button>
    </div>
  </div>
</template>
