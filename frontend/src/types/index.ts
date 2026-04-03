// ========================================
// 공통
// ========================================
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
  permission_evidence: boolean
  permission_vuln: boolean
  status: UserStatus
  last_login_at?: string
  created_at: string
  updated_at: string
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
  permission_evidence: boolean
  permission_vuln: boolean
}

export interface UserUpdatePayload {
  name?: string
  team?: string
  role?: UserRole
  permission_evidence?: boolean
  permission_vuln?: boolean
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

export interface TokenResponse {
  access_token: string
  token_type: string
  user: User
}

// ========================================
// 증빙 수집 (Phase 2에서 확장)
// ========================================
export interface Framework {
  id: number
  name: string
  description?: string
  created_at: string
}

export interface Control {
  id: number
  framework_id: number
  code: string
  domain?: string
  name: string
  description?: string
  evidence_collected?: number
  evidence_total?: number
  created_at: string
}

// ========================================
// 취약점 관리 (Phase 3에서 확장)
// ========================================
export type AssessmentStatus = 'in_progress' | 'completed'
export type VulnStatus = 'unassigned' | 'pending_schedule' | 'pending_approval' | 'in_progress' | 'done'

export interface Assessment {
  id: number
  name: string
  assessor?: string
  assessed_at?: string
  description?: string
  status: AssessmentStatus
  total_count?: number
  done_count?: number
  in_progress_count?: number
  pending_count?: number
  progress_percent?: number
  created_at: string
}

export interface Vulnerability {
  id: number
  assessment_id: number
  category?: string
  asset?: string
  item: string
  content?: string
  issue?: string
  assignee_id?: number
  approver_id?: number
  due_date?: string
  status: VulnStatus
  action_plan?: string
  action_result?: string
  note?: string
  created_at: string
  assignee?: UserBrief
  approver?: UserBrief
}
