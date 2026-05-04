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
  /**
   * Phase 3 cleanup (2026-05-04): Phase 3 (취약점 관리) 프로젝트 제거 결정.
   * BE User entity 의 permission_vuln 필드는 DB 컬럼 호환성을 위해 *보존* —
   * FE 차원에서는 표시 안 함 (AccountsView 의 라벨 제거 + auth.ts hasVulnAccess
   * getter 제거). 향후 Step 2 (spec 통합 재작성) 에서 본격 컬럼 제거 결정.
   */
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
  /** Phase 3 cleanup: BE 호환성 보존 (위 User.permissionVuln javadoc 참조). */
  permissionVuln: boolean
}

export interface UserUpdatePayload {
  name?: string
  team?: string
  role?: UserRole
  permissionEvidence?: boolean
  /** Phase 3 cleanup: BE 호환성 보존 (위 User.permissionVuln javadoc 참조). */
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
// 취약점 관리 (Phase 3) — 2026-05-04 프로젝트 제거 결정
//
// VulnStatus / Vulnerability / ApprovalRequest 인터페이스 일괄 제거됨.
// 관련 BE 도메인 (com.secuhub.domain.vulnerability) 도 동시 제거됨.
// 본 섹션은 marker 로만 유지 (Step 2 spec 통합 재작성에서 본 marker 도 제거 가능).
// ========================================