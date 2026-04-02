import axios from 'axios'
import type { AxiosRequestConfig, AxiosResponse } from 'axios'
import type {
  LoginPayload,
  TokenResponse,
  User,
  UserListResponse,
  UserBrief,
  UserCreatePayload,
  UserUpdatePayload,
} from '@/types'

// ╔══════════════════════════════════════════════════════════════╗
// ║  MOCK 모드 설정                                              ║
// ║  백엔드 연결 시 아래 값을 false로 변경하세요.                      ║
// ╚══════════════════════════════════════════════════════════════╝
const USE_MOCK = true

// ========================================
// axios 인스턴스
// ========================================
const api = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
})

// ========================================
// MOCK 데이터
// ========================================
const mockUsers: User[] = [
  {
    id: 1, email: 'admin@company.com', name: '관리자', team: '보안팀',
    role: 'admin', permission_evidence: true, permission_vuln: true,
    status: 'active', last_login_at: '2025-03-28T09:30:00',
    created_at: '2025-01-01T00:00:00', updated_at: '2025-03-28T09:30:00',
  },
  {
    id: 2, email: 'park_tl@company.com', name: '박팀장', team: '백엔드팀',
    role: 'approver', permission_evidence: false, permission_vuln: true,
    status: 'active', last_login_at: '2025-03-27T14:00:00',
    created_at: '2025-01-01T00:00:00', updated_at: '2025-03-27T14:00:00',
  },
  {
    id: 3, email: 'kim@company.com', name: '김개발', team: '백엔드팀',
    role: 'developer', permission_evidence: false, permission_vuln: true,
    status: 'active', last_login_at: '2025-03-28T10:15:00',
    created_at: '2025-01-15T00:00:00', updated_at: '2025-03-28T10:15:00',
  },
  {
    id: 4, email: 'lee@company.com', name: '이보안', team: '프론트엔드팀',
    role: 'developer', permission_evidence: false, permission_vuln: true,
    status: 'active', last_login_at: '2025-03-26T16:45:00',
    created_at: '2025-02-01T00:00:00', updated_at: '2025-03-26T16:45:00',
  },
  {
    id: 5, email: 'choi@company.com', name: '박백엔드', team: '백엔드팀',
    role: 'developer', permission_evidence: false, permission_vuln: true,
    status: 'active', last_login_at: '2025-03-25T11:00:00',
    created_at: '2025-02-15T00:00:00', updated_at: '2025-03-25T11:00:00',
  },
  {
    id: 6, email: 'jung@company.com', name: '정인프라', team: '인프라팀',
    role: 'developer', permission_evidence: true, permission_vuln: true,
    status: 'active', last_login_at: '2025-03-24T09:00:00',
    created_at: '2025-01-20T00:00:00', updated_at: '2025-03-24T09:00:00',
  },
  {
    id: 7, email: 'hwang_tl@company.com', name: '황팀장', team: '프론트엔드팀',
    role: 'approver', permission_evidence: false, permission_vuln: true,
    status: 'active', last_login_at: '2025-03-20T13:30:00',
    created_at: '2025-01-05T00:00:00', updated_at: '2025-03-20T13:30:00',
  },
  {
    id: 8, email: 'old@company.com', name: '퇴사자', team: '백엔드팀',
    role: 'developer', permission_evidence: false, permission_vuln: false,
    status: 'inactive',
    created_at: '2024-06-01T00:00:00', updated_at: '2025-02-01T00:00:00',
  },
]

const mockPasswords: Record<string, string> = {
  'admin@company.com': 'admin1234',
  'park_tl@company.com': 'park1234',
  'kim@company.com': 'dev1234',
  'lee@company.com': 'dev1234',
  'choi@company.com': 'dev1234',
  'jung@company.com': 'dev1234',
  'hwang_tl@company.com': 'hwang1234',
}

function brief(u: User): UserBrief {
  return { id: u.id, name: u.name, email: u.email, team: u.team, role: u.role }
}

const mockVulnerabilities = [
  {
    id: 1, assessment_id: 1, category: '웹 취약점', asset: '결제 API',
    item: 'SQL Injection', content: '사용자 입력값 파라미터 바인딩 미적용',
    issue: 'DB 데이터 유출 및 변조 가능',
    assignee_id: 3, approver_id: 2, due_date: '2025-03-30', status: 'in_progress',
    action_plan: '전체 쿼리 파라미터 바인딩 적용', note: '긴급 조치 필요',
    created_at: '2025-01-15T00:00:00',
    assignee: brief(mockUsers[2]), approver: brief(mockUsers[1]),
  },
  {
    id: 2, assessment_id: 1, category: '웹 취약점', asset: '회원 서비스',
    item: 'XSS (Cross-Site Scripting)', content: '게시판 입력 필드에서 스크립트 실행 가능',
    issue: '세션 탈취 및 피싱 공격 가능',
    assignee_id: 4, approver_id: 7, due_date: '2025-04-02', status: 'in_progress',
    action_plan: '입출력 값 이스케이프 처리',
    created_at: '2025-01-15T00:00:00',
    assignee: brief(mockUsers[3]), approver: brief(mockUsers[6]),
  },
  {
    id: 3, assessment_id: 1, category: '웹 취약점', asset: '결제 API',
    item: 'CSRF', content: 'CSRF 토큰 미적용',
    issue: '사용자 의도와 무관한 요청 실행 가능',
    assignee_id: 3, approver_id: 2, due_date: '2025-04-05', status: 'pending_approval',
    action_plan: 'CSRF 토큰 적용 예정',
    created_at: '2025-01-15T00:00:00',
    assignee: brief(mockUsers[2]), approver: brief(mockUsers[1]),
  },
  {
    id: 4, assessment_id: 1, category: '인증/인가', asset: '관리자 페이지',
    item: '인증 우회', content: '관리자 API 엔드포인트 접근 제어 미흡',
    issue: '비인가 사용자의 관리자 기능 접근 가능',
    assignee_id: 5, approver_id: 2, due_date: '2025-04-10', status: 'pending_approval',
    action_plan: 'Spring Security 기반 인가 처리 강화',
    created_at: '2025-01-15T00:00:00',
    assignee: brief(mockUsers[4]), approver: brief(mockUsers[1]),
  },
  {
    id: 5, assessment_id: 1, category: '데이터 보안', asset: '회원 DB',
    item: '개인정보 평문 저장', content: '주민등록번호 암호화 미적용',
    issue: 'DB 유출 시 개인정보 노출', status: 'unassigned',
    created_at: '2025-01-15T00:00:00',
  },
  {
    id: 6, assessment_id: 1, category: '웹 취약점', asset: '결제 API',
    item: '파일 업로드 취약점', content: '업로드 파일 확장자 검증 미흡',
    issue: '웹쉘 업로드를 통한 서버 탈취 가능', status: 'unassigned',
    created_at: '2025-01-15T00:00:00',
  },
  {
    id: 7, assessment_id: 1, category: '세션 관리', asset: '회원 서비스',
    item: '세션 타임아웃 미설정', content: '장기간 미사용 세션 유지',
    issue: '세션 하이재킹 위험 증가',
    assignee_id: 3, approver_id: 2, due_date: '2025-03-01', status: 'done',
    action_plan: '세션 타임아웃 30분 설정', action_result: '전체 서비스 세션 타임아웃 30분 적용 완료',
    created_at: '2025-01-15T00:00:00',
    assignee: brief(mockUsers[2]), approver: brief(mockUsers[1]),
  },
  {
    id: 8, assessment_id: 1, category: '웹 취약점', asset: '웹서버',
    item: '디렉토리 리스팅', content: '웹 서버 디렉토리 목록 노출',
    issue: '서버 내부 파일 구조 노출',
    assignee_id: 6, approver_id: 2, due_date: '2025-02-20', status: 'done',
    action_plan: '디렉토리 리스팅 비활성화', action_result: 'Nginx 설정에서 autoindex off 적용',
    created_at: '2025-01-15T00:00:00',
    assignee: brief(mockUsers[5]), approver: brief(mockUsers[1]),
  },
  {
    id: 9, assessment_id: 2, category: '인프라', asset: '웹서버',
    item: '불필요 포트 오픈', content: 'TCP 8080, 8443 포트 외부 노출',
    issue: '공격 표면 확대',
    assignee_id: 6, approver_id: 2, due_date: '2025-04-15', status: 'in_progress',
    action_plan: '방화벽 정책 수정으로 불필요 포트 차단',
    created_at: '2025-02-01T00:00:00',
    assignee: brief(mockUsers[5]), approver: brief(mockUsers[1]),
  },
  {
    id: 10, assessment_id: 2, category: '인프라', asset: 'DB서버',
    item: '기본 계정 미변경', content: 'MariaDB root 계정 기본 비밀번호 사용',
    issue: '무단 DB 접근 가능', status: 'unassigned',
    created_at: '2025-02-01T00:00:00',
  },
  {
    id: 11, assessment_id: 2, category: '인프라', asset: '방화벽',
    item: 'Any→Any 허용 룰', content: '방화벽에 전체 허용 정책 존재',
    issue: '네트워크 구간 보안 무력화',
    assignee_id: 6, approver_id: 2, due_date: '2025-03-10', status: 'done',
    action_plan: '최소 권한 원칙에 따른 정책 재설정',
    action_result: '전체 허용 룰 삭제, 업무 필요 포트만 허용',
    created_at: '2025-02-01T00:00:00',
    assignee: brief(mockUsers[5]), approver: brief(mockUsers[1]),
  },
]

const mockAssessments = [
  {
    id: 1, name: '2025년 1분기 웹 취약점 점검', assessor: '한국정보보호센터',
    assessed_at: '2025-01-15', description: '주요 웹 애플리케이션 대상 모의해킹 점검',
    status: 'in_progress', total_count: 8, done_count: 2, in_progress_count: 3, pending_count: 3,
    progress_percent: 25, created_at: '2025-01-10T00:00:00',
  },
  {
    id: 2, name: '2025년 1분기 인프라 취약점 점검', assessor: '보안컨설팅(주)',
    assessed_at: '2025-02-01', description: '서버/네트워크 장비 대상 취약점 진단',
    status: 'in_progress', total_count: 3, done_count: 1, in_progress_count: 1, pending_count: 1,
    progress_percent: 33, created_at: '2025-01-25T00:00:00',
  },
]

const mockApprovalRequests: any[] = [
  {
    id: 1, vulnerability_id: 3, requester_id: 3, approver_id: 2,
    due_date: '2025-04-05', action_plan: 'CSRF 토큰 적용 예정', status: 'pending',
    created_at: '2025-03-20T10:00:00',
    vulnerability: mockVulnerabilities[2], requester: brief(mockUsers[2]), approver: brief(mockUsers[1]),
  },
  {
    id: 2, vulnerability_id: 4, requester_id: 5, approver_id: 2,
    due_date: '2025-04-10', action_plan: 'Spring Security 기반 인가 처리 강화', status: 'pending',
    created_at: '2025-03-22T14:30:00',
    vulnerability: mockVulnerabilities[3], requester: brief(mockUsers[4]), approver: brief(mockUsers[1]),
  },
]

const mockFrameworks = [
  { id: 1, name: 'ISMS-P', description: '정보보호 및 개인정보보호 관리체계 인증', created_at: '2025-01-01T00:00:00' },
]

const mockControls = [
  { id: 1, framework_id: 1, code: '1.1.1', domain: '관리체계 수립', name: '정보보호 정책 수립', evidence_collected: 2, evidence_total: 3, created_at: '2025-01-01T00:00:00' },
  { id: 2, framework_id: 1, code: '1.1.2', domain: '관리체계 수립', name: '최고책임자의 지정', evidence_collected: 1, evidence_total: 1, created_at: '2025-01-01T00:00:00' },
  { id: 3, framework_id: 1, code: '1.2.1', domain: '위험 관리', name: '정보자산 식별', evidence_collected: 0, evidence_total: 2, created_at: '2025-01-01T00:00:00' },
  { id: 4, framework_id: 1, code: '2.1.1', domain: '접근 통제', name: '접근권한 관리', evidence_collected: 1, evidence_total: 2, created_at: '2025-01-01T00:00:00' },
]

// ========================================
// MOCK 라우터
// ========================================
const tokenMap: Record<string, User> = {}

function delay(): Promise<void> {
  return new Promise((r) => setTimeout(r, Math.random() * 120 + 30))
}

function matchPath(url: string, pattern: string): Record<string, string> | null {
  const u = url.split('/').filter(Boolean)
  const p = pattern.split('/').filter(Boolean)
  if (u.length !== p.length) return null
  const params: Record<string, string> = {}
  for (let i = 0; i < p.length; i++) {
    if (p[i].startsWith(':')) params[p[i].slice(1)] = u[i]
    else if (p[i] !== u[i]) return null
  }
  return params
}

function qp(config: AxiosRequestConfig): Record<string, string> {
  const r: Record<string, string> = {}
  if (config.params) Object.entries(config.params).forEach(([k, v]) => { if (v != null) r[k] = String(v) })
  return r
}

function body(config: AxiosRequestConfig): any {
  return typeof config.data === 'string' ? JSON.parse(config.data) : config.data
}

function ok(data: any): AxiosResponse {
  return { data, status: 200, statusText: 'OK', headers: {}, config: {} as any }
}

function fail(msg: string, status = 400): never {
  const e: any = new Error(msg)
  e.response = { data: { detail: msg }, status, statusText: 'Error', headers: {}, config: {} as any }
  throw e
}

type H = (c: AxiosRequestConfig, p: Record<string, string>) => Promise<AxiosResponse>
const routes: { m: string; p: string; h: H }[] = [
  // Auth
  { m: 'post', p: '/auth/login', h: async (c) => {
    const { email, password } = body(c)
    if (mockPasswords[email] === password) {
      const user = mockUsers.find((u) => u.email === email)!
      const token = `mock-${user.id}-${Date.now()}`
      tokenMap[token] = user
      return ok({ access_token: token, token_type: 'bearer', user })
    }
    fail('이메일 또는 비밀번호가 올바르지 않습니다.', 401)
  }},
  { m: 'get', p: '/auth/me', h: async (c) => {
    const t = (c.headers?.Authorization?.toString() || '').replace('Bearer ', '')
    if (tokenMap[t]) return ok(tokenMap[t])
    fail('인증이 필요합니다.', 401)
  }},

  // Users (구체 경로 먼저)
  { m: 'get', p: '/users/approvers', h: async () =>
    ok(mockUsers.filter((u) => u.role === 'approver' && u.status === 'active').map(brief))
  },
  { m: 'get', p: '/users/developers', h: async (c) => {
    const p = qp(c)
    let d = mockUsers.filter((u) => (u.role === 'developer' || u.role === 'approver') && u.status === 'active')
    if (p.team) d = d.filter((u) => u.team === p.team)
    return ok(d.map(brief))
  }},
  { m: 'patch', p: '/users/me/password', h: async () => ok({ message: '비밀번호가 변경되었습니다.' }) },
  { m: 'get', p: '/users', h: async (c) => {
    const p = qp(c)
    let f = [...mockUsers]
    if (p.search) { const s = p.search.toLowerCase(); f = f.filter((u) => u.name.toLowerCase().includes(s) || u.email.toLowerCase().includes(s)) }
    if (p.role) f = f.filter((u) => u.role === p.role)
    if (p.status) f = f.filter((u) => u.status === p.status)
    const pg = parseInt(p.page || '0'), sz = parseInt(p.size || '20')
    return ok({ items: f.slice(pg * sz, pg * sz + sz), total: f.length })
  }},
  { m: 'get', p: '/users/:id', h: async (_c, p) => {
    const u = mockUsers.find((u) => u.id === +p.id)
    if (!u) fail('사용자를 찾을 수 없습니다.', 404)
    return ok(u)
  }},
  { m: 'post', p: '/users', h: async (c) => {
    const b = body(c)
    const u: User = { id: mockUsers.length + 1, email: b.email, name: b.name, team: b.team, role: b.role,
      permission_evidence: b.permission_evidence ?? false, permission_vuln: b.permission_vuln ?? true,
      status: 'active', created_at: new Date().toISOString(), updated_at: new Date().toISOString() }
    mockUsers.push(u)
    return ok(u)
  }},
  { m: 'patch', p: '/users/:id', h: async (c, p) => {
    const u = mockUsers.find((u) => u.id === +p.id)
    if (!u) fail('not found', 404)
    Object.assign(u!, body(c), { updated_at: new Date().toISOString() })
    return ok(u)
  }},
  { m: 'delete', p: '/users/:id', h: async (_c, p) => {
    const i = mockUsers.findIndex((u) => u.id === +p.id)
    if (i === -1) fail('not found', 404)
    mockUsers.splice(i, 1)
    return ok({ message: '삭제되었습니다.' })
  }},

  // Frameworks / Controls
  { m: 'get', p: '/frameworks', h: async () => ok(mockFrameworks) },
  { m: 'get', p: '/controls', h: async (c) => {
    const p = qp(c)
    let f = [...mockControls]
    if (p.framework_id) f = f.filter((x) => x.framework_id === +p.framework_id)
    return ok(f)
  }},

  // Assessments
  { m: 'get', p: '/assessments', h: async () => ok(mockAssessments) },
  { m: 'get', p: '/assessments/:id', h: async (_c, p) => {
    const a = mockAssessments.find((x) => x.id === +p.id)
    if (!a) fail('not found', 404)
    return ok(a)
  }},

  // Vulnerabilities
  { m: 'get', p: '/vulnerabilities', h: async (c) => {
    const p = qp(c)
    let f = [...mockVulnerabilities] as any[]
    if (p.assessment_id) f = f.filter((v: any) => v.assessment_id === +p.assessment_id)
    if (p.status) f = f.filter((v: any) => v.status === p.status)
    if (p.assignee_id) f = f.filter((v: any) => v.assignee_id === +p.assignee_id)
    if (p.category) f = f.filter((v: any) => v.category === p.category)
    const pg = parseInt(p.page || '0'), sz = parseInt(p.size || '20')
    return ok({ items: f.slice(pg * sz, pg * sz + sz), total: f.length })
  }},
  { m: 'get', p: '/vulnerabilities/:id', h: async (_c, p) => {
    const v = mockVulnerabilities.find((x) => x.id === +p.id)
    if (!v) fail('not found', 404)
    return ok(v)
  }},
  { m: 'patch', p: '/vulnerabilities/:id', h: async (c, p) => {
    const v = mockVulnerabilities.find((x) => x.id === +p.id) as any
    if (!v) fail('not found', 404)
    const b = body(c)
    if (b.assignee_id) { const a = mockUsers.find((u) => u.id === b.assignee_id); if (a) v.assignee = brief(a) }
    if (b.approver_id) { const a = mockUsers.find((u) => u.id === b.approver_id); if (a) v.approver = brief(a) }
    Object.assign(v, b)
    return ok(v)
  }},

  // Approvals
  { m: 'get', p: '/approvals', h: async (c) => {
    const p = qp(c)
    let f = [...mockApprovalRequests]
    if (p.approver_id) f = f.filter((a) => a.approver_id === +p.approver_id)
    if (p.status) f = f.filter((a) => a.status === p.status)
    return ok(f)
  }},
  { m: 'post', p: '/approvals/:id/approve', h: async (_c, p) => {
    const r = mockApprovalRequests.find((a) => a.id === +p.id)
    if (!r) fail('not found', 404)
    r.status = 'approved'
    const v = mockVulnerabilities.find((x) => x.id === r.vulnerability_id) as any
    if (v) v.status = 'in_progress'
    return ok(r)
  }},
  { m: 'post', p: '/approvals/:id/reject', h: async (c, p) => {
    const r = mockApprovalRequests.find((a) => a.id === +p.id)
    if (!r) fail('not found', 404)
    r.status = 'rejected'
    r.reject_reason = body(c)?.reason || '반려'
    const v = mockVulnerabilities.find((x) => x.id === r.vulnerability_id) as any
    if (v) { v.status = 'unassigned'; v.assignee_id = undefined; v.assignee = undefined; v.approver_id = undefined; v.approver = undefined; v.due_date = undefined }
    return ok(r)
  }},

  // Dashboard
  { m: 'get', p: '/dashboard/stats', h: async () => {
    const t = mockVulnerabilities.length
    const d = mockVulnerabilities.filter((v) => v.status === 'done').length
    const ip = mockVulnerabilities.filter((v) => v.status === 'in_progress').length
    return ok({
      evidence: { total: 247, completed: 198, uncollected: 42, failed: 7, progressPercent: 80 },
      vulnerability: { total: t, done: d, inProgress: ip, pending: t - d - ip, progressPercent: Math.round((d / t) * 100) },
    })
  }},
]

// ========================================
// MOCK Adapter 설치
// ========================================
if (USE_MOCK) {
  api.defaults.adapter = async (config: AxiosRequestConfig): Promise<AxiosResponse> => {
    const method = (config.method || 'get').toLowerCase()
    const rawUrl = config.url || ''
    const path = rawUrl.replace(/^https?:\/\/[^/]+/, '').replace(/^\/api\/v1/, '').split('?')[0] || '/'

    await delay()

    for (const route of routes) {
      if (route.m !== method) continue
      if (route.p === path) return route.h(config, {})
      const params = matchPath(path, route.p)
      if (params) return route.h(config, params)
    }

    console.warn(`[Mock] 매칭 없음: ${method.toUpperCase()} ${path}`)
    return ok({ message: 'Mock: 아직 구현되지 않은 API입니다.' })
  }

  console.log(
    '%c🔧 Mock API 활성화됨 %c— 백엔드 없이 임시 데이터로 동작합니다.',
    'color: #10b981; font-weight: bold; font-size: 14px;',
    'color: #6b7280; font-size: 12px;'
  )
}

// ========================================
// 인터셉터 (Mock/실제 공통)
// ========================================
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('access_token')
      localStorage.removeItem('user')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

// ========================================
// Auth API
// ========================================
export const authApi = {
  login(data: LoginPayload) {
    return api.post<TokenResponse>('/auth/login', data)
  },
  getMe() {
    return api.get<User>('/auth/me')
  },
}

// ========================================
// Users API
// ========================================
export const usersApi = {
  list(params?: { page?: number; size?: number; role?: string; status?: string; search?: string }) {
    return api.get<UserListResponse>('/users', { params })
  },
  get(id: number) {
    return api.get<User>(`/users/${id}`)
  },
  create(data: UserCreatePayload) {
    return api.post<User>('/users', data)
  },
  update(id: number, data: UserUpdatePayload) {
    return api.patch<User>(`/users/${id}`, data)
  },
  delete(id: number) {
    return api.delete(`/users/${id}`)
  },
  getApprovers() {
    return api.get<UserBrief[]>('/users/approvers')
  },
  getDevelopers(team?: string) {
    return api.get<UserBrief[]>('/users/developers', { params: { team } })
  },
  changePassword(currentPassword: string, newPassword: string) {
    return api.patch('/users/me/password', {
      current_password: currentPassword,
      new_password: newPassword,
    })
  },
}

export default api
