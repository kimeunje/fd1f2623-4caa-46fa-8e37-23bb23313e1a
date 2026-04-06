import axios from 'axios'
import type {
  ApiResponse,
  LoginPayload,
  TokenResponse,
  User,
  UserListResponse,
  UserBrief,
  UserCreatePayload,
  UserUpdatePayload,
} from '@/types'

// ========================================
// axios 인스턴스
// ========================================
const api = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  timeout: 15000,
})

// ========================================
// 요청 인터셉터 — JWT 토큰 자동 첨부
// ========================================
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// ========================================
// 응답 인터셉터 — 401 시 자동 로그아웃
// ========================================
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('access_token')
      localStorage.removeItem('user')
      // 로그인 페이지가 아닌 경우에만 리다이렉트
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  }
)

// ========================================
// Auth API
// ========================================
export const authApi = {
  /**
   * 로그인
   * POST /api/v1/auth/login
   * 응답: ApiResponse<TokenResponse>
   */
  login(data: LoginPayload) {
    return api.post<ApiResponse<TokenResponse>>('/auth/login', data)
  },

  /**
   * 내 정보 조회
   * GET /api/v1/auth/me
   * 응답: ApiResponse<User>
   */
  getMe() {
    return api.get<ApiResponse<User>>('/auth/me')
  },
}

// ========================================
// Users API
// ========================================
export const usersApi = {
  list(params?: { page?: number; size?: number; role?: string; status?: string; search?: string }) {
    return api.get<ApiResponse<UserListResponse>>('/users', { params })
  },
  get(id: number) {
    return api.get<ApiResponse<User>>(`/users/${id}`)
  },
  create(data: UserCreatePayload) {
    return api.post<ApiResponse<User>>('/users', data)
  },
  update(id: number, data: UserUpdatePayload) {
    return api.patch<ApiResponse<User>>(`/users/${id}`, data)
  },
  delete(id: number) {
    return api.delete(`/users/${id}`)
  },
  getApprovers() {
    return api.get<ApiResponse<UserBrief[]>>('/users/approvers')
  },
  getDevelopers(team?: string) {
    return api.get<ApiResponse<UserBrief[]>>('/users/developers', { params: { team } })
  },
  changePassword(currentPassword: string, newPassword: string) {
    return api.patch('/users/me/password', {
      currentPassword,
      newPassword,
    })
  },
}

export default api
