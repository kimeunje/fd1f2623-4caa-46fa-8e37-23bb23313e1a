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
// v19.24 — 'reviewer'(심사원, 읽기 전용) 추가. approver/developer 는 레거시 담당자.
export type UserRole = 'admin' | 'approver' | 'developer' | 'reviewer'
export type UserStatus = 'active' | 'inactive'

export interface User {
  id: number
  email: string
  name: string
  team?: string
  role: UserRole
  permissionEvidence: boolean
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
}

export interface UserUpdatePayload {
  name?: string
  team?: string
  role?: UserRole
  permissionEvidence?: boolean
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
 * { token: "eyJ...", user: { id, email, name, team, role, permissionEvidence } }
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

/**
 * v15.6 controls 테이블 DROP 후의 *legacy type 잔여*. FE 측 dead 가능성 — 별도
 * cleanup phase 후보 (본 Phase 3 cleanup 범위 외). caller 검증 후 제거 결정.
 */
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
// 계정별 IP 접근 규칙 (v19.x)
// ========================================
export interface IpAccessRule {
  id: number
  userId: number
  cidr: string
  description?: string
  enabled: boolean
  createdAt?: string
  updatedAt?: string
}

export interface IpAccessRuleCreatePayload {
  cidr: string
  description?: string
  enabled?: boolean
}

export interface IpAccessRuleUpdatePayload {
  cidr?: string
  description?: string
  enabled?: boolean
}

// ========================================
// v19.27 — 관리 항목 인수인계 노트 (누적 로그)
// ========================================
/**
 * 관리 항목당 여러 개가 시간순으로 누적된다. authorName 은 로그인 계정과
 * 무관하게 작성 시 직접 입력한 이름(공용 관리자 계정 대응).
 */
export interface ControlNodeNote {
  id: number
  authorName: string
  body: string
  createdAt: string   // ISO-8601
  updatedAt: string   // ISO-8601
  edited: boolean      // 작성 후 수정 여부 → "(수정됨)" 표시용
}

export interface ControlNodeNotePayload {
  authorName: string
  body: string
}