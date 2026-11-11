import { onMounted, ref, watch } from 'vue'

const STORAGE_KEY = 'flowpilot.theme'
export type ThemeMode = 'light' | 'dark'

function readInitial(): ThemeMode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === 'light' || stored === 'dark') return stored
  } catch {
    /* ignore */
  }
  const prefersDark =
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  return prefersDark ? 'dark' : 'light'
}

const mode = ref<ThemeMode>(readInitial())

function apply(next: ThemeMode): void {
  const root = document.documentElement
  root.dataset.theme = next
  // Element Plus 深色主题依赖 html.dark
  root.classList.toggle('dark', next === 'dark')
}

export function useTheme() {
  function setTheme(next: ThemeMode): void {
    mode.value = next
  }

  function toggleTheme(): void {
    mode.value = mode.value === 'dark' ? 'light' : 'dark'
  }

  onMounted(() => {
    apply(mode.value)
  })

  watch(mode, (next) => {
    apply(next)
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      /* ignore */
    }
  })

  return { mode, setTheme, toggleTheme }
}
