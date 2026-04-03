import type {
  User,
  UserBrief,
  Framework,
  Control,
  Assessment,
  Vulnerability,
} from '@/types'

// ========================================
// 사용자
// ========================================
export const mockUsers: User[] = [
  {
    id: 1,
    email: 'admin@company.com',
    name: '관리자',
    team: '보안팀',
    role: 'admin',
    permission_evidence: true,
    permission_vuln: true,
    status: 'active',
    last_login_at: '2025-03-28T09:30:00',
    created_at: '2025-01-01T00:00:00',
    updated_at: '2025-03-28T09:30:00',
  },
  {
    id: 2,
    email: 'park_tl@company.com',
    name: '박팀장',
    team: '백엔드팀',
    role: 'approver',
    permission_evidence: false,
    permission_vuln: true,
    status: 'active',
    last_login_at: '2025-03-27T14:00:00',
    created_at: '2025-01-01T00:00:00',
    updated_at: '2025-03-27T14:00:00',
  },
  {
    id: 3,
    email: 'kim@company.com',
    name: '김개발',
    team: '백엔드팀',
    role: 'developer',
    permission_evidence: false,
    permission_vuln: true,
    status: 'active',
    last_login_at: '2025-03-28T10:15:00',
    created_at: '2025-01-15T00:00:00',
    updated_at: '2025-03-28T10:15:00',
  },
  {
    id: 4,
    email: 'lee@company.com',
    name: '이보안',
    team: '프론트엔드팀',
    role: 'developer',
    permission_evidence: false,
    permission_vuln: true,
    status: 'active',
    last_login_at: '2025-03-26T16:45:00',
    created_at: '2025-02-01T00:00:00',
    updated_at: '2025-03-26T16:45:00',
  },
  {
    id: 5,
    email: 'choi@company.com',
    name: '박백엔드',
    team: '백엔드팀',
    role: 'developer',
    permission_evidence: false,
    permission_vuln: true,
    status: 'active',
    last_login_at: '2025-03-25T11:00:00',
    created_at: '2025-02-15T00:00:00',
    updated_at: '2025-03-25T11:00:00',
  },
  {
    id: 6,
    email: 'jung@company.com',
    name: '정인프라',
    team: '인프라팀',
    role: 'developer',
    permission_evidence: true,
    permission_vuln: true,
    status: 'active',
    last_login_at: '2025-03-24T09:00:00',
    created_at: '2025-01-20T00:00:00',
    updated_at: '2025-03-24T09:00:00',
  },
  {
    id: 7,
    email: 'hwang_tl@company.com',
    name: '황팀장',
    team: '프론트엔드팀',
    role: 'approver',
    permission_evidence: false,
    permission_vuln: true,
    status: 'active',
    last_login_at: '2025-03-20T13:30:00',
    created_at: '2025-01-05T00:00:00',
    updated_at: '2025-03-20T13:30:00',
  },
  {
    id: 8,
    email: 'old@company.com',
    name: '퇴사자',
    team: '백엔드팀',
    role: 'developer',
    permission_evidence: false,
    permission_vuln: false,
    status: 'inactive',
    created_at: '2024-06-01T00:00:00',
    updated_at: '2025-02-01T00:00:00',
  },
]

// 비밀번호 매핑 (평문 — mock 전용)
export const mockPasswords: Record<string, string> = {
  'admin@company.com': 'admin1234',
  'park_tl@company.com': 'park1234',
  'kim@company.com': 'dev1234',
  'lee@company.com': 'dev1234',
  'choi@company.com': 'dev1234',
  'jung@company.com': 'dev1234',
  'hwang_tl@company.com': 'hwang1234',
}

export function toUserBrief(u: User): UserBrief {
  return { id: u.id, name: u.name, email: u.email, team: u.team, role: u.role }
}

// ========================================
// 프레임워크 / 통제 항목
// ========================================
export const mockFrameworks: Framework[] = [
  { id: 1, name: 'ISMS-P', description: '정보보호 및 개인정보보호 관리체계 인증', created_at: '2025-01-01T00:00:00' },
  { id: 2, name: 'ISO 27001', description: '국제 정보보안 관리체계 표준', created_at: '2025-01-01T00:00:00' },
]

export const mockControls: Control[] = [
  { id: 1, framework_id: 1, code: '1.1.1', domain: '관리체계 수립', name: '정보보호 정책 수립', evidence_collected: 2, evidence_total: 3, created_at: '2025-01-01T00:00:00' },
  { id: 2, framework_id: 1, code: '1.1.2', domain: '관리체계 수립', name: '최고책임자의 지정', evidence_collected: 1, evidence_total: 1, created_at: '2025-01-01T00:00:00' },
  { id: 3, framework_id: 1, code: '1.2.1', domain: '위험 관리', name: '정보자산 식별', evidence_collected: 0, evidence_total: 2, created_at: '2025-01-01T00:00:00' },
  { id: 4, framework_id: 1, code: '1.2.2', domain: '위험 관리', name: '현황 및 흐름 분석', evidence_collected: 3, evidence_total: 3, created_at: '2025-01-01T00:00:00' },
  { id: 5, framework_id: 1, code: '2.1.1', domain: '접근 통제', name: '접근권한 관리', evidence_collected: 1, evidence_total: 2, created_at: '2025-01-01T00:00:00' },
  { id: 6, framework_id: 1, code: '2.1.2', domain: '접근 통제', name: '접근권한 검토', evidence_collected: 0, evidence_total: 1, created_at: '2025-01-01T00:00:00' },
  { id: 7, framework_id: 1, code: '2.2.1', domain: '암호화', name: '암호 정책 수립', evidence_collected: 2, evidence_total: 2, created_at: '2025-01-01T00:00:00' },
  { id: 8, framework_id: 1, code: '2.3.1', domain: '네트워크 보안', name: '네트워크 접근 통제', evidence_collected: 1, evidence_total: 3, created_at: '2025-01-01T00:00:00' },
]

// ========================================
// 점검 / 취약점
// ========================================
export const mockAssessments: Assessment[] = [
  {
    id: 1,
    name: '2025년 1분기 웹 취약점 점검',
    assessor: '한국정보보호센터',
    assessed_at: '2025-01-15',
    description: '주요 웹 애플리케이션 대상 모의해킹 점검',
    status: 'in_progress',
    total_count: 12,
    done_count: 5,
    in_progress_count: 4,
    pending_count: 3,
    progress_percent: 42,
    created_at: '2025-01-10T00:00:00',
  },
  {
    id: 2,
    name: '2025년 1분기 인프라 취약점 점검',
    assessor: '보안컨설팅(주)',
    assessed_at: '2025-02-01',
    description: '서버/네트워크 장비 대상 취약점 진단',
    status: 'in_progress',
    total_count: 8,
    done_count: 3,
    in_progress_count: 2,
    pending_count: 3,
    progress_percent: 38,
    created_at: '2025-01-25T00:00:00',
  },
  {
    id: 3,
    name: '2024년 4분기 종합 보안 점검',
    assessor: '내부 보안팀',
    assessed_at: '2024-12-01',
    status: 'completed',
    total_count: 15,
    done_count: 15,
    in_progress_count: 0,
    pending_count: 0,
    progress_percent: 100,
    created_at: '2024-11-15T00:00:00',
  },
]

export const mockVulnerabilities: Vulnerability[] = [
  {
    id: 1, assessment_id: 1, category: '웹 취약점', asset: '결제 API',
    item: 'SQL Injection', content: '사용자 입력값 파라미터 바인딩 미적용',
    issue: 'DB 데이터 유출 및 변조 가능',
    assignee_id: 3, approver_id: 2, due_date: '2025-03-30', status: 'in_progress',
    action_plan: '전체 쿼리 파라미터 바인딩 적용', note: '긴급 조치 필요',
    created_at: '2025-01-15T00:00:00',
    assignee: toUserBrief(mockUsers[2]),
    approver: toUserBrief(mockUsers[1]),
  },
  {
    id: 2, assessment_id: 1, category: '웹 취약점', asset: '회원 서비스',
    item: 'XSS (Cross-Site Scripting)', content: '게시판 입력 필드에서 스크립트 실행 가능',
    issue: '세션 탈취 및 피싱 공격 가능',
    assignee_id: 4, approver_id: 7, due_date: '2025-04-02', status: 'in_progress',
    action_plan: '입출력 값 이스케이프 처리', note: '',
    created_at: '2025-01-15T00:00:00',
    assignee: toUserBrief(mockUsers[3]),
    approver: toUserBrief(mockUsers[6]),
  },
  {
    id: 3, assessment_id: 1, category: '웹 취약점', asset: '결제 API',
    item: 'CSRF (Cross-Site Request Forgery)', content: 'CSRF 토큰 미적용',
    issue: '사용자 의도와 무관한 요청 실행 가능',
    assignee_id: 3, approver_id: 2, due_date: '2025-04-05', status: 'pending_approval',
    action_plan: 'CSRF 토큰 적용 예정',
    created_at: '2025-01-15T00:00:00',
    assignee: toUserBrief(mockUsers[2]),
    approver: toUserBrief(mockUsers[1]),
  },
  {
    id: 4, assessment_id: 1, category: '인증/인가', asset: '관리자 페이지',
    item: '인증 우회', content: '관리자 API 엔드포인트 접근 제어 미흡',
    issue: '비인가 사용자의 관리자 기능 접근 가능',
    assignee_id: 5, approver_id: 2, due_date: '2025-04-10', status: 'pending_approval',
    action_plan: 'Spring Security 기반 인가 처리 강화',
    created_at: '2025-01-15T00:00:00',
    assignee: toUserBrief(mockUsers[4]),
    approver: toUserBrief(mockUsers[1]),
  },
  {
    id: 5, assessment_id: 1, category: '데이터 보안', asset: '회원 DB',
    item: '개인정보 평문 저장', content: '주민등록번호 암호화 미적용',
    issue: 'DB 유출 시 개인정보 노출',
    status: 'unassigned',
    created_at: '2025-01-15T00:00:00',
  },
  {
    id: 6, assessment_id: 1, category: '웹 취약점', asset: '결제 API',
    item: '파일 업로드 취약점', content: '업로드 파일 확장자 검증 미흡',
    issue: '웹쉘 업로드를 통한 서버 탈취 가능',
    status: 'unassigned',
    created_at: '2025-01-15T00:00:00',
  },
  {
    id: 7, assessment_id: 1, category: '세션 관리', asset: '회원 서비스',
    item: '세션 타임아웃 미설정', content: '장기간 미사용 세션 유지',
    issue: '세션 하이재킹 위험 증가',
    assignee_id: 3, approver_id: 2, due_date: '2025-03-01', status: 'done',
    action_plan: '세션 타임아웃 30분 설정', action_result: '전체 서비스 세션 타임아웃 30분 적용 완료',
    created_at: '2025-01-15T00:00:00',
    assignee: toUserBrief(mockUsers[2]),
    approver: toUserBrief(mockUsers[1]),
  },
  {
    id: 8, assessment_id: 1, category: '웹 취약점', asset: '웹서버',
    item: '디렉토리 리스팅', content: '웹 서버 디렉토리 목록 노출',
    issue: '서버 내부 파일 구조 노출',
    assignee_id: 6, approver_id: 2, due_date: '2025-02-20', status: 'done',
    action_plan: '디렉토리 리스팅 비활성화', action_result: 'Nginx 설정에서 autoindex off 적용',
    created_at: '2025-01-15T00:00:00',
    assignee: toUserBrief(mockUsers[5]),
    approver: toUserBrief(mockUsers[1]),
  },
  // 인프라 점검 취약점
  {
    id: 9, assessment_id: 2, category: '인프라', asset: '웹서버',
    item: '불필요 포트 오픈', content: 'TCP 8080, 8443 포트 외부 노출',
    issue: '공격 표면 확대',
    assignee_id: 6, approver_id: 2, due_date: '2025-04-15', status: 'in_progress',
    action_plan: '방화벽 정책 수정으로 불필요 포트 차단',
    created_at: '2025-02-01T00:00:00',
    assignee: toUserBrief(mockUsers[5]),
    approver: toUserBrief(mockUsers[1]),
  },
  {
    id: 10, assessment_id: 2, category: '인프라', asset: 'DB서버',
    item: '기본 계정 미변경', content: 'MariaDB root 계정 기본 비밀번호 사용',
    issue: '무단 DB 접근 가능',
    status: 'unassigned',
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
    assignee: toUserBrief(mockUsers[5]),
    approver: toUserBrief(mockUsers[1]),
  },
]

// ========================================
// 결재 요청
// ========================================
export interface MockApprovalRequest {
  id: number
  vulnerability_id: number
  requester_id: number
  approver_id: number
  due_date?: string
  action_plan?: string
  status: 'pending' | 'approved' | 'rejected'
  approved_at?: string
  reject_reason?: string
  created_at: string
  vulnerability?: Vulnerability
  requester?: UserBrief
  approver?: UserBrief
}

export const mockApprovalRequests: MockApprovalRequest[] = [
  {
    id: 1,
    vulnerability_id: 3,
    requester_id: 3,
    approver_id: 2,
    due_date: '2025-04-05',
    action_plan: 'CSRF 토큰 적용 예정',
    status: 'pending',
    created_at: '2025-03-20T10:00:00',
    vulnerability: mockVulnerabilities[2],
    requester: toUserBrief(mockUsers[2]),
    approver: toUserBrief(mockUsers[1]),
  },
  {
    id: 2,
    vulnerability_id: 4,
    requester_id: 5,
    approver_id: 2,
    due_date: '2025-04-10',
    action_plan: 'Spring Security 기반 인가 처리 강화',
    status: 'pending',
    created_at: '2025-03-22T14:30:00',
    vulnerability: mockVulnerabilities[3],
    requester: toUserBrief(mockUsers[4]),
    approver: toUserBrief(mockUsers[1]),
  },
  {
    id: 3,
    vulnerability_id: 1,
    requester_id: 3,
    approver_id: 2,
    due_date: '2025-03-30',
    action_plan: '전체 쿼리 파라미터 바인딩 적용',
    status: 'approved',
    approved_at: '2025-03-18T09:00:00',
    created_at: '2025-03-15T11:00:00',
    vulnerability: mockVulnerabilities[0],
    requester: toUserBrief(mockUsers[2]),
    approver: toUserBrief(mockUsers[1]),
  },
]

// ========================================
// 대시보드 통계
// ========================================
export const mockDashboardStats = {
  evidence: {
    total: 247,
    completed: 198,
    uncollected: 42,
    failed: 7,
    progressPercent: 80,
  },
  vulnerability: {
    total: 20,
    done: 8,
    inProgress: 6,
    pending: 6,
    progressPercent: 63,
  },
}
