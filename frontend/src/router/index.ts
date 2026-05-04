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
  //                          props mapping 도 controlId → nodeId 로 동기 변경.
  //                          ControlsView.goToEvidenceTypeDetail 의 router.push params,
  //                          EvidenceTypeDetailView 의 props 정의도 함께 변경.
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
    // v11 Phase 5-12: 증빙 유형 상세 페이지
    // v15.7: param :controlId → :nodeId (Q3=B 정합)
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
  // Phase 3 cleanup (2026-05-04): /vulns 라우트 제거 — 취약점 관리 프로젝트 외 결정
  {
    path: '/accounts',
    name: 'accounts',
    component: () => import('@/views/admin/AccountsView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '계정 관리' },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/admin/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '설정' },
  },

  // ========================================
  // 개발자 / 결재자
  //
  // Phase 3 cleanup (2026-05-04): 다음 라우트 제거 — 취약점 관리 의존:
  // - /dev/my-vulns (나의 현황)
  // - /dev/vulns (취약점 목록)
  // - /dev/approvals (결재 관리)
  // - /dev/history (조치 이력)
  //
  // 보존: /dev/dashboard (전체 현황) — Phase 4 (시스템 관리) 또는 증빙 운영용으로
  // 잠재 활용. 현재 PlaceholderView, 향후 단계 결정.
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
router.beforeEach((to, _from, next) => {
  const authStore = useAuthStore()

  if (to.meta.requiresAuth === false) {
    if (authStore.isAuthenticated && to.name === 'login') {
      next(authStore.isAdmin ? '/dashboard' : '/dev/dashboard')
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
    next(authStore.isAdmin ? '/dashboard' : '/dev/dashboard')
    return
  }

  // v11 Phase 5-5: permission_evidence 요구하는 라우트는 플래그도 추가로 체크
  const needsEvidencePerm = to.meta.requirePermissionEvidence as boolean | undefined
  if (needsEvidencePerm && !authStore.isAdmin && !authStore.hasEvidenceAccess) {
    next(authStore.isAdmin ? '/dashboard' : '/dev/dashboard')
    return
  }

  next()
})

export default router