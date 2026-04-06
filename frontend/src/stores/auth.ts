import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User, LoginPayload } from '@/types'
import { authApi } from '@/services/api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const token = ref<string | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  // ========================================
  // Getters
  // ========================================
  const isAuthenticated = computed(() => !!token.value)
  const isAdmin = computed(() => user.value?.role === 'admin')
  const isApprover = computed(() => user.value?.role === 'approver')
  const isDeveloper = computed(() => user.value?.role === 'developer')
  const hasEvidenceAccess = computed(() => user.value?.permissionEvidence ?? false)
  const hasVulnAccess = computed(() => user.value?.permissionVuln ?? false)

  // ========================================
  // Actions
  // ========================================

  /**
   * localStorage에서 토큰/사용자 정보 복원
   * main.ts에서 앱 시작 시 호출
   */
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

  /**
   * 로그인 처리
   *
   * 백엔드 응답 구조:
   * {
   *   success: true,
   *   message: "로그인 성공",
   *   data: {
   *     token: "eyJ...",
   *     user: { id, email, name, team, role, permissionEvidence, permissionVuln }
   *   }
   * }
   */
  async function login(payload: LoginPayload) {
    loading.value = true
    error.value = null
    try {
      const response = await authApi.login(payload)
      const apiResponse = response.data // ApiResponse<TokenResponse>

      if (!apiResponse.success) {
        throw new Error(apiResponse.message || '로그인에 실패했습니다.')
      }

      const { token: jwtToken, user: userData } = apiResponse.data

      token.value = jwtToken
      user.value = userData

      localStorage.setItem('access_token', jwtToken)
      localStorage.setItem('user', JSON.stringify(userData))

      return userData
    } catch (err: any) {
      // 백엔드 에러 응답: { success: false, message: "..." }
      const message =
        err.response?.data?.message ||
        err.message ||
        '로그인에 실패했습니다.'
      error.value = message
      throw err
    } finally {
      loading.value = false
    }
  }

  /**
   * 현재 로그인된 사용자 정보 갱신
   * 토큰은 유지하되 사용자 정보만 서버에서 다시 가져옴
   *
   * 백엔드 응답 구조:
   * {
   *   success: true,
   *   data: { id, email, name, team, role, permissionEvidence, permissionVuln }
   * }
   */
  async function fetchMe() {
    try {
      const response = await authApi.getMe()
      const apiResponse = response.data

      if (apiResponse.success && apiResponse.data) {
        user.value = apiResponse.data
        localStorage.setItem('user', JSON.stringify(apiResponse.data))
      } else {
        logout()
      }
    } catch {
      logout()
    }
  }

  /**
   * 로그아웃
   */
  function logout() {
    user.value = null
    token.value = null
    localStorage.removeItem('access_token')
    localStorage.removeItem('user')
  }

  return {
    // State
    user,
    token,
    loading,
    error,
    // Getters
    isAuthenticated,
    isAdmin,
    isApprover,
    isDeveloper,
    hasEvidenceAccess,
    hasVulnAccess,
    // Actions
    initialize,
    login,
    fetchMe,
    logout,
  }
})
