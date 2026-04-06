// ========================================
// 공통 — 백엔드 ApiResponse 래핑
// ========================================
export interface ApiResponse<T> {
  success: boolean
  message?: string
  data: T
}

export interface PageResponse<T> {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

export interface MessageResponse {
  message: string
  detail?: string
}

// ========================================
// 사용자
// ========================================
export type UserRole = 'admin' | 'approver' | 'developer'
export type UserStatus = 'active' | 'inactive'

export interface User {
  id: number
  email: string
  name: string
  team?: string
  role: UserRole
  permissionEvidence: boolean
  permissionVuln: boolean
  status?: UserStatus
  lastLoginAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface UserBrief {
  id: number
  name: string
  email: string
  team?: string
  role: UserRole
}

export interface UserCreatePayload {
  email: string
  name: string
  password: string
  team?: string
  role: UserRole
  permissionEvidence: boolean
  permissionVuln: boolean
}

export interface UserUpdatePayload {
  name?: string
  team?: string
  role?: UserRole
  permissionEvidence?: boolean
  permissionVuln?: boolean
  status?: UserStatus
}

export interface UserListResponse {
  items: User[]
  total: number
}

// ========================================
// 인증
// ========================================
export interface LoginPayload {
  email: string
  password: string
}

/**
 * 백엔드 LoginResponse 구조:
 * { token: "eyJ...", user: { id, email, name, team, role, permissionEvidence, permissionVuln } }
 */
export interface TokenResponse {
  token: string
  user: User
}

// ========================================
// 증빙 수집 (Phase 2에서 확장)
// ========================================
export interface Framework {
  id: number
  name: string
  description?: string
  createdAt: string
}

export interface Control {
  id: number
  frameworkId: number
  code: string
  domain?: string
  name: string
  description?: string
  evidenceCollected?: number
  evidenceTotal?: number
  createdAt: string
}

// ========================================
// 취약점 관리 (Phase 3에서 확장)
// ========================================
export type VulnStatus = 'unassigned' | 'pending_approval' | 'in_progress' | 'done'

export interface Vulnerability {
  id: number
  category?: string
  deviceType?: string
  hostname?: string
  checkCode?: string
  problem?: string
  content?: string
  assigneeId?: number
  approverId?: number
  planDate?: string
  status: VulnStatus
  note?: string
  createdAt?: string
  assignee?: UserBrief
  approver?: UserBrief
}

export interface ApprovalRequest {
  id: number
  vulnerabilityId: number
  requesterId: number
  approverId: number
  category?: string
  content?: string
  status: 'pending' | 'approved' | 'rejected'
  createdAt?: string
  updatedAt?: string
  vulnerability?: Vulnerability
  requester?: UserBrief
  approver?: UserBrief
}
