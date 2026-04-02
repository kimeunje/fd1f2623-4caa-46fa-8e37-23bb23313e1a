/**
 * Mock API Adapter
 *
 * axios의 defaults.adapter를 직접 교체하여 HTTP 요청 자체를 차단합니다.
 * Vite proxy를 완전히 우회하므로 백엔드 없이 동작합니다.
 *
 * 환경변수 VITE_USE_MOCK=true 일 때만 활성화됩니다.
 */
import type { AxiosInstance, AxiosRequestConfig, AxiosResponse } from 'axios'
import {
  mockUsers,
  mockPasswords,
  mockVulnerabilities,
  mockAssessments,
  mockApprovalRequests,
  mockControls,
  mockFrameworks,
  toUserBrief,
} from './data'
import type { User } from '@/types'

// ========================================
// 유틸리티
// ========================================

function delay(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, Math.random() * 150 + 50))
}

function matchPath(url: string, pattern: string): Record<string, string> | null {
  const urlParts = url.split('/').filter(Boolean)
  const patternParts = pattern.split('/').filter(Boolean)

  if (urlParts.length !== patternParts.length) return null

  const params: Record<string, string> = {}
  for (let i = 0; i < patternParts.length; i++) {
    if (patternParts[i].startsWith(':')) {
      params[patternParts[i].slice(1)] = urlParts[i]
    } else if (patternParts[i] !== urlParts[i]) {
      return null
    }
  }
  return params
}

function getQueryParams(config: AxiosRequestConfig): Record<string, string> {
  const params: Record<string, string> = {}
  if (config.params) {
    Object.entries(config.params).forEach(([k, v]) => {
      if (v !== undefined && v !== null) params[k] = String(v)
    })
  }
  return params
}

function getBody(config: AxiosRequestConfig): any {
  return typeof config.data === 'string' ? JSON.parse(config.data) : config.data
}

function ok(data: any): AxiosResponse {
  return { data, status: 200, statusText: 'OK', headers: { 'content-type': 'application/json' }, config: {} as any }
}

function fail(message: string, status = 400): never {
  const error: any = new Error(message)
  error.response = { data: { detail: message }, status, statusText: 'Error', headers: {}, config: {} as any }
  throw error
}

// ========================================
// 토큰 관리
// ========================================
const tokenUserMap: Record<string, User> = {}

function generateToken(user: User): string {
  const token = `mock-jwt-${user.id}-${Date.now()}`
  tokenUserMap[token] = user
  return token
}

function getUserFromConfig(config: AxiosRequestConfig): User | null {
  const authHeader = config.headers?.Authorization?.toString() || ''
  const token = authHeader.replace('Bearer ', '')
  return tokenUserMap[token] || null
}

// ========================================
// Route 정의
// ========================================
type Handler = (config: AxiosRequestConfig, params: Record<string, string>) => Promise<AxiosResponse>

const routes: { method: string; pattern: string; handler: Handler }[] = [
  // ── Auth ──
  {
    method: 'post', pattern: '/auth/login',
    handler: async (config) => {
      const { email, password } = getBody(config)
      if (mockPasswords[email] && mockPasswords[email] === password) {
        const user = mockUsers.find((u) => u.email === email)!
        return ok({ access_token: generateToken(user), token_type: 'bearer', user })
      }
      fail('이메일 또는 비밀번호가 올바르지 않습니다.', 401)
    },
  },
  {
    method: 'get', pattern: '/auth/me',
    handler: async (config) => {
      const user = getUserFromConfig(config)
      if (user) return ok(user)
      fail('인증이 필요합니다.', 401)
    },
  },

  // ── Users (구체적인 경로 먼저!) ──
  {
    method: 'get', pattern: '/users/approvers',
    handler: async () => ok(mockUsers.filter((u) => u.role === 'approver' && u.status === 'active').map(toUserBrief)),
  },
  {
    method: 'get', pattern: '/users/developers',
    handler: async (config) => {
      const params = getQueryParams(config)
      let devs = mockUsers.filter((u) => (u.role === 'developer' || u.role === 'approver') && u.status === 'active')
      if (params.team) devs = devs.filter((u) => u.team === params.team)
      return ok(devs.map(toUserBrief))
    },
  },
  {
    method: 'patch', pattern: '/users/me/password',
    handler: async () => ok({ message: '비밀번호가 변경되었습니다.' }),
  },
  {
    method: 'get', pattern: '/users',
    handler: async (config) => {
      const params = getQueryParams(config)
      let filtered = [...mockUsers]
      if (params.search) {
        const s = params.search.toLowerCase()
        filtered = filtered.filter((u) => u.name.toLowerCase().includes(s) || u.email.toLowerCase().includes(s))
      }
      if (params.role) filtered = filtered.filter((u) => u.role === params.role)
      if (params.status) filtered = filtered.filter((u) => u.status === params.status)
      const page = parseInt(params.page || '0')
      const size = parseInt(params.size || '20')
      const start = page * size
      return ok({ items: filtered.slice(start, start + size), total: filtered.length })
    },
  },
  {
    method: 'get', pattern: '/users/:id',
    handler: async (_c, p) => {
      const user = mockUsers.find((u) => u.id === parseInt(p.id))
      if (!user) fail('사용자를 찾을 수 없습니다.', 404)
      return ok(user)
    },
  },
  {
    method: 'post', pattern: '/users',
    handler: async (config) => {
      const body = getBody(config)
      const newUser: User = {
        id: mockUsers.length + 1, email: body.email, name: body.name, team: body.team, role: body.role,
        permission_evidence: body.permission_evidence ?? false, permission_vuln: body.permission_vuln ?? true,
        status: 'active', created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
      }
      mockUsers.push(newUser)
      if (body.password) mockPasswords[body.email] = body.password
      return ok(newUser)
    },
  },
  {
    method: 'patch', pattern: '/users/:id',
    handler: async (config, p) => {
      const user = mockUsers.find((u) => u.id === parseInt(p.id))
      if (!user) fail('사용자를 찾을 수 없습니다.', 404)
      Object.assign(user!, getBody(config), { updated_at: new Date().toISOString() })
      return ok(user)
    },
  },
  {
    method: 'delete', pattern: '/users/:id',
    handler: async (_c, p) => {
      const idx = mockUsers.findIndex((u) => u.id === parseInt(p.id))
      if (idx === -1) fail('사용자를 찾을 수 없습니다.', 404)
      mockUsers.splice(idx, 1)
      return ok({ message: '삭제되었습니다.' })
    },
  },

  // ── Frameworks / Controls ──
  { method: 'get', pattern: '/frameworks', handler: async () => ok(mockFrameworks) },
  {
    method: 'get', pattern: '/controls',
    handler: async (config) => {
      const params = getQueryParams(config)
      let filtered = [...mockControls]
      if (params.framework_id) filtered = filtered.filter((c) => c.framework_id === parseInt(params.framework_id))
      return ok(filtered)
    },
  },

  // ── Assessments ──
  { method: 'get', pattern: '/assessments', handler: async () => ok(mockAssessments) },
  {
    method: 'get', pattern: '/assessments/:id',
    handler: async (_c, p) => {
      const a = mockAssessments.find((a) => a.id === parseInt(p.id))
      if (!a) fail('점검을 찾을 수 없습니다.', 404)
      return ok(a)
    },
  },

  // ── Vulnerabilities ──
  {
    method: 'get', pattern: '/vulnerabilities',
    handler: async (config) => {
      const params = getQueryParams(config)
      let filtered = [...mockVulnerabilities]
      if (params.assessment_id) filtered = filtered.filter((v) => v.assessment_id === parseInt(params.assessment_id))
      if (params.status) filtered = filtered.filter((v) => v.status === params.status)
      if (params.assignee_id) filtered = filtered.filter((v) => v.assignee_id === parseInt(params.assignee_id))
      if (params.category) filtered = filtered.filter((v) => v.category === params.category)
      const page = parseInt(params.page || '0')
      const size = parseInt(params.size || '20')
      const start = page * size
      return ok({ items: filtered.slice(start, start + size), total: filtered.length })
    },
  },
  {
    method: 'get', pattern: '/vulnerabilities/:id',
    handler: async (_c, p) => {
      const v = mockVulnerabilities.find((v) => v.id === parseInt(p.id))
      if (!v) fail('취약점을 찾을 수 없습니다.', 404)
      return ok(v)
    },
  },
  {
    method: 'patch', pattern: '/vulnerabilities/:id',
    handler: async (config, p) => {
      const vuln = mockVulnerabilities.find((v) => v.id === parseInt(p.id))
      if (!vuln) fail('취약점을 찾을 수 없습니다.', 404)
      const body = getBody(config)
      if (body.assignee_id) {
        const a = mockUsers.find((u) => u.id === body.assignee_id)
        if (a) vuln.assignee = toUserBrief(a)
      }
      if (body.approver_id) {
        const a = mockUsers.find((u) => u.id === body.approver_id)
        if (a) vuln.approver = toUserBrief(a)
      }
      Object.assign(vuln, body)
      return ok(vuln)
    },
  },

  // ── Approvals ──
  {
    method: 'get', pattern: '/approvals',
    handler: async (config) => {
      const params = getQueryParams(config)
      let filtered = [...mockApprovalRequests]
      if (params.approver_id) filtered = filtered.filter((a) => a.approver_id === parseInt(params.approver_id))
      if (params.status) filtered = filtered.filter((a) => a.status === params.status)
      return ok(filtered)
    },
  },
  {
    method: 'post', pattern: '/approvals/:id/approve',
    handler: async (_c, p) => {
      const req = mockApprovalRequests.find((a) => a.id === parseInt(p.id))
      if (!req) fail('결재 요청을 찾을 수 없습니다.', 404)
      req.status = 'approved'
      req.approved_at = new Date().toISOString()
      const vuln = mockVulnerabilities.find((v) => v.id === req.vulnerability_id)
      if (vuln) vuln.status = 'in_progress'
      return ok(req)
    },
  },
  {
    method: 'post', pattern: '/approvals/:id/reject',
    handler: async (config, p) => {
      const req = mockApprovalRequests.find((a) => a.id === parseInt(p.id))
      if (!req) fail('결재 요청을 찾을 수 없습니다.', 404)
      const body = getBody(config)
      req.status = 'rejected'
      req.reject_reason = body?.reason || '반려'
      const vuln = mockVulnerabilities.find((v) => v.id === req.vulnerability_id)
      if (vuln) {
        vuln.status = 'unassigned'
        vuln.assignee_id = undefined
        vuln.assignee = undefined
        vuln.approver_id = undefined
        vuln.approver = undefined
        vuln.due_date = undefined
      }
      return ok(req)
    },
  },

  // ── Dashboard ──
  {
    method: 'get', pattern: '/dashboard/stats',
    handler: async () => {
      const total = mockVulnerabilities.length
      const done = mockVulnerabilities.filter((v) => v.status === 'done').length
      const inProgress = mockVulnerabilities.filter((v) => v.status === 'in_progress').length
      return ok({
        evidence: { total: 247, completed: 198, uncollected: 42, failed: 7, progressPercent: 80 },
        vulnerability: { total, done, inProgress, pending: total - done - inProgress, progressPercent: Math.round((done / total) * 100) },
      })
    },
  },
]

// ========================================
// 라우트 매칭
// ========================================
function findRoute(method: string, path: string) {
  for (const route of routes) {
    if (route.method !== method.toLowerCase()) continue
    if (route.pattern === path) return { route, params: {} as Record<string, string> }
    const params = matchPath(path, route.pattern)
    if (params) return { route, params }
  }
  return null
}

// ========================================
// 설치
// ========================================
export function installMockAdapter(apiInstance: AxiosInstance): void {
  // ★ 핵심: axios의 기본 adapter를 통째로 교체
  //   → HTTP 요청 자체가 발생하지 않음
  //   → Vite proxy와 무관하게 동작
  apiInstance.defaults.adapter = async (config: AxiosRequestConfig): Promise<AxiosResponse> => {
    const method = config.method || 'get'
    const rawUrl = config.url || ''

    // baseURL이 이미 합쳐진 형태일 수 있으므로 정리
    const path = rawUrl
      .replace(/^https?:\/\/[^/]+/, '')
      .replace(/^\/api\/v1/, '')
      .split('?')[0] || '/'

    await delay()

    const match = findRoute(method, path)
    if (match) {
      return match.route.handler(config, match.params)
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
