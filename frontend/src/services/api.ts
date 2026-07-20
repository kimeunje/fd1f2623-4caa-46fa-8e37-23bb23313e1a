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
  IpAccessRule,
  IpAccessRuleCreatePayload,
  IpAccessRuleUpdatePayload,
  ControlNodeNote,
  ControlNodeNotePayload,
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

// ========================================
// IP Access Rules API (v19.x)
// ========================================
export const ipRulesApi = {
  list(userId: number) {
    return api.get<ApiResponse<IpAccessRule[]>>(`/users/${userId}/ip-rules`)
  },
  create(userId: number, data: IpAccessRuleCreatePayload) {
    return api.post<ApiResponse<IpAccessRule>>(`/users/${userId}/ip-rules`, data)
  },
  update(userId: number, ruleId: number, data: IpAccessRuleUpdatePayload) {
    return api.patch<ApiResponse<IpAccessRule>>(`/users/${userId}/ip-rules/${ruleId}`, data)
  },
  delete(userId: number, ruleId: number) {
    return api.delete(`/users/${userId}/ip-rules/${ruleId}`)
  },
}


// ========================================
// v19.27 — 관리 항목 인수인계 노트 API
// ========================================
export const notesApi = {
  /** 특정 관리 항목의 노트 목록 (작성순) */
  list(nodeId: number) {
    return api.get<ApiResponse<ControlNodeNote[]>>(`/control-nodes/${nodeId}/notes`)
  },
  /** 노트 추가 */
  create(nodeId: number, data: ControlNodeNotePayload) {
    return api.post<ApiResponse<ControlNodeNote>>(`/control-nodes/${nodeId}/notes`, data)
  },
  /** 노트 수정 (작성자/내용) */
  update(nodeId: number, noteId: number, data: ControlNodeNotePayload) {
    return api.patch<ApiResponse<ControlNodeNote>>(
      `/control-nodes/${nodeId}/notes/${noteId}`,
      data,
    )
  },
  /** 노트 삭제 */
  delete(nodeId: number, noteId: number) {
    return api.delete(`/control-nodes/${nodeId}/notes/${noteId}`)
  },
}

export default api