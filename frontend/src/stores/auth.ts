import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User, LoginPayload } from '@/types'
import { authApi } from '@/services/api'
import { useRouter } from 'vue-router'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const token = ref<string | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  // Getters
  const isAuthenticated = computed(() => !!token.value)
  const isAdmin = computed(() => user.value?.role === 'admin')
  const isApprover = computed(() => user.value?.role === 'approver')
  const isDeveloper = computed(() => user.value?.role === 'developer')
  const hasEvidenceAccess = computed(() => user.value?.permission_evidence ?? false)
  const hasVulnAccess = computed(() => user.value?.permission_vuln ?? false)

  // Actions
  function initialize() {
    const savedToken = localStorage.getItem('access_token')
    const savedUser = localStorage.getItem('user')
    if (savedToken && savedUser) {
      token.value = savedToken
      try {
        user.value = JSON.parse(savedUser)
      } catch {
        logout()
      }
    }
  }

  async function login(payload: LoginPayload) {
    loading.value = true
    error.value = null
    try {
      const response = await authApi.login(payload)
      const data = response.data

      token.value = data.access_token
      user.value = data.user

      localStorage.setItem('access_token', data.access_token)
      localStorage.setItem('user', JSON.stringify(data.user))

      return data.user
    } catch (err: any) {
      error.value = err.response?.data?.detail || '로그인에 실패했습니다.'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function fetchMe() {
    try {
      const response = await authApi.getMe()
      user.value = response.data
      localStorage.setItem('user', JSON.stringify(response.data))
    } catch {
      logout()
    }
  }

  function logout() {
    user.value = null
    token.value = null
    localStorage.removeItem('access_token')
    localStorage.removeItem('user')
  }

  return {
    user,
    token,
    loading,
    error,
    isAuthenticated,
    isAdmin,
    isApprover,
    isDeveloper,
    hasEvidenceAccess,
    hasVulnAccess,
    initialize,
    login,
    fetchMe,
    logout,
  }
})
