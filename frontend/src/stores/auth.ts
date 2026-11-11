import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/client'
import type { AuthUser, LoginRequest, RegisterRequest } from '@/api/types'

const TOKEN_KEY = 'flowpilot.token'
const USER_KEY = 'flowpilot.user'

function readStoredToken(): string {
  try {
    return localStorage.getItem(TOKEN_KEY) ?? ''
  } catch {
    return ''
  }
}

function readStoredUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as AuthUser) : null
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(readStoredToken())
  const user = ref<AuthUser | null>(readStoredUser())
  const loading = ref(false)

  const isAuthenticated = computed(() => token.value.length > 0)
  const displayName = computed(() => user.value?.nickname || user.value?.username || '未登录')

  function persist(nextToken: string, nextUser: AuthUser | null): void {
    token.value = nextToken
    user.value = nextUser
    try {
      localStorage.setItem(TOKEN_KEY, nextToken)
      if (nextUser) {
        localStorage.setItem(USER_KEY, JSON.stringify(nextUser))
      } else {
        localStorage.removeItem(USER_KEY)
      }
    } catch {
      /* 隐私模式下 localStorage 可能不可用，忽略 */
    }
  }

  async function login(payload: LoginRequest): Promise<void> {
    loading.value = true
    try {
      const result = await authApi.login(payload)
      persist(result.token, result.user ?? null)
    } finally {
      loading.value = false
    }
  }

  async function register(payload: RegisterRequest): Promise<void> {
    loading.value = true
    try {
      await authApi.register(payload)
    } finally {
      loading.value = false
    }
  }

  /** 刷新当前用户信息；令牌失效时静默登出 */
  async function refreshProfile(): Promise<void> {
    if (!isAuthenticated.value) return
    try {
      const profile = await authApi.me()
      user.value = profile
      try {
        localStorage.setItem(USER_KEY, JSON.stringify(profile))
      } catch {
        /* ignore */
      }
    } catch {
      /* 401 由拦截器统一处理 */
    }
  }

  function logout(): void {
    token.value = ''
    user.value = null
    try {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    } catch {
      /* ignore */
    }
  }

  return {
    token,
    user,
    loading,
    isAuthenticated,
    displayName,
    login,
    register,
    refreshProfile,
    logout
  }
})
