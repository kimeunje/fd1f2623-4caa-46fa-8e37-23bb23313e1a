import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  // ========================================
  // 인증
  // ========================================
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { requiresAuth: false, layout: 'blank' },
  },

  // ========================================
  // 관리자 (보안팀)
  // ========================================
  {
    path: '/',
    redirect: '/dashboard',
  },
  {
    path: '/dashboard',
    name: 'admin-dashboard',
    component: () => import('@/views/admin/DashboardView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin' },
  },

  // ========================================
  // v11 Phase 5-3 / 5-11 / 5-12 — 증빙 수집 허브 (계층 구조)
  //
  //   /controls                                                           → framework-list (Framework 목록)
  //   /controls/new                                                       → framework-create-wizard
  //   /controls/:frameworkId                                              → framework-detail (통제 항목 목록)
  //   /controls/:frameworkId/:nodeId/evidence-types/:evidenceTypeId       → evidence-type-detail (증빙 유형 상세)
  //
  // 라우트 정의 순서 주의: /controls/new 는 /controls/:frameworkId 보다 먼저.
  // :frameworkId, :nodeId, :evidenceTypeId 는 정규식 (\\d+) 으로 숫자만 매칭하도록 강제.
  //
  // v15 Phase 5-15c (v15.7): route param `:controlId` → `:nodeId` (Q3=B 정합).
  // ========================================
  {
    path: '/controls',
    name: 'framework-list',
    component: () => import('@/views/admin/FrameworkListView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '통제 항목' },
  },
  {
    path: '/controls/new',
    name: 'framework-create-wizard',
    component: () => import('@/views/admin/FrameworkCreateWizardView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '새 Framework 생성' },
  },
  {
    path: '/controls/:frameworkId(\\d+)',
    name: 'framework-detail',
    component: () => import('@/views/admin/ControlsView.vue'),
    props: (route) => ({ frameworkId: Number(route.params.frameworkId) }),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: 'Framework 상세' },
  },
  {
    path: '/controls/:frameworkId(\\d+)/:nodeId(\\d+)/evidence-types/:evidenceTypeId(\\d+)',
    name: 'evidence-type-detail',
    component: () => import('@/views/admin/EvidenceTypeDetailView.vue'),
    props: (route) => ({
      frameworkId: Number(route.params.frameworkId),
      nodeId: Number(route.params.nodeId),
      evidenceTypeId: Number(route.params.evidenceTypeId),
    }),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '증빙 유형 상세' },
  },

  {
    path: '/jobs',
    name: 'jobs',
    component: () => import('@/views/admin/JobsView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '수집 작업' },
  },
  {
    path: '/files',
    name: 'files',
    component: () => import('@/views/admin/FilesView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '증빙 파일' },
  },
  // Phase 3 cleanup (2026-05-04): /vulns 라우트 제거
  {
    path: '/accounts',
    name: 'accounts',
    component: () => import('@/views/admin/AccountsView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '계정 관리' },
  },
  // v19.14 (AUDIT-3) — 감사 로그 뷰어. SecurityConfig /audit/** 는 v18.9.5 기보유.
  {
    path: '/audit',
    name: 'audit-logs',
    component: () => import('@/views/admin/AuditLogView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '감사 로그' },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/admin/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '설정' },
  },

  // ========================================
  // v19.24 — 심사원(reviewer) 읽기 전용 뷰 / v19.25 — 본체 구현
  //
  //   /review → 심사원 랜딩. 관리 항목 + 항목별 최신 승인 파일(다운로드)만.
  //   이력·스크립트·승인·인수인계 노트 UI 없음.
  //
  // v19.25: PlaceholderView → ReviewView 교체. reviewer 전용 레이아웃을 별도로 두지 않고
  // layout:'blank' 유지 — ReviewView 가 자체 헤더(프레임워크 선택 + 로그아웃)를 갖는 자립형.
  // ========================================
  {
    path: '/review',
    name: 'review-home',
    component: () => import('@/views/reviewer/ReviewView.vue'),
    meta: { requiresAuth: true, roles: ['reviewer'], layout: 'blank', title: '증빙 심사' },
  },

  // ========================================
  // 개발자 / 결재자
  //
  // Phase 3 cleanup (2026-05-04): vuln 관련 4 라우트 제거
  // (/dev/my-vulns / /dev/vulns / /dev/approvals / /dev/history).
  // /dev/dashboard 보존 (PlaceholderView, 향후 활용).
  // ========================================
  {
    path: '/dev/dashboard',
    name: 'dev-dashboard',
    component: () => import('@/views/dev/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['developer', 'approver'], layout: 'dev', title: '전체 현황' },
  },

  // ========================================
  // v11 Phase 5-5 — 담당자 "내 할 일"
  // ========================================
  {
    path: '/my-tasks',
    name: 'my-tasks',
    component: () => import('@/views/dev/MyTasksView.vue'),
    meta: {
      requiresAuth: true,
      roles: ['admin', 'developer', 'approver'],
      requirePermissionEvidence: true,
      layout: 'dev',
      title: '내 할 일',
    },
  },
  {
    path: '/my-tasks/:evidenceTypeId',
    name: 'my-task-detail',
    component: () => import('@/views/dev/MyEvidenceDetailView.vue'),
    props: (route) => ({ evidenceTypeId: Number(route.params.evidenceTypeId) }),
    meta: {
      requiresAuth: true,
      roles: ['admin', 'developer', 'approver'],
      requirePermissionEvidence: true,
      layout: 'dev',
      title: '증빙 재제출',
    },
  },

  // 404
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { requiresAuth: false, layout: 'blank' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// ========================================
// 네비게이션 가드
// ========================================

/**
 * v19.24 — 역할별 홈 경로.
 *   admin    → /dashboard
 *   reviewer → /review        (심사원)
 *   그 외    → /dev/dashboard  (레거시 담당자)
 * 인증 후 접근 거부/로그인 리다이렉트 목적지를 한 곳에서 결정한다.
 */
function landingPath(authStore: ReturnType<typeof useAuthStore>): string {
  if (authStore.isAdmin) return '/dashboard'
  if (authStore.isReviewer) return '/review'
  return '/dev/dashboard'
}

router.beforeEach((to, _from, next) => {
  const authStore = useAuthStore()

  if (to.meta.requiresAuth === false) {
    if (authStore.isAuthenticated && to.name === 'login') {
      next(landingPath(authStore))
      return
    }
    next()
    return
  }

  if (!authStore.isAuthenticated) {
    next('/login')
    return
  }

  const allowedRoles = to.meta.roles as string[] | undefined
  if (allowedRoles && !allowedRoles.includes(authStore.user?.role || '')) {
    next(landingPath(authStore))
    return
  }

  // v11 Phase 5-5: permission_evidence 요구하는 라우트는 플래그도 추가로 체크
  const needsEvidencePerm = to.meta.requirePermissionEvidence as boolean | undefined
  if (needsEvidencePerm && !authStore.isAdmin && !authStore.hasEvidenceAccess) {
    next(landingPath(authStore))
    return
  }

  next()
})

export default router