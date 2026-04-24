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

  // v11 Phase 5-3 / 5-11: /controls 는 Framework 목록 페이지,
  //   /controls/new 는 wizard (반드시 :frameworkId 보다 먼저 정의),
  //   /controls/:frameworkId(숫자) 는 Framework 상세.
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
    // v11 Phase 5-11: (\\d+) 로 숫자만 매칭. 'new' 같은 문자열이 여기로 빠지지 않도록.
    path: '/controls/:frameworkId(\\d+)',
    name: 'framework-detail',
    component: () => import('@/views/admin/ControlsView.vue'),
    // props 로 frameworkId 를 숫자로 전달 (param → number 변환)
    props: (route) => ({ frameworkId: Number(route.params.frameworkId) }),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: 'Framework 상세' },
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
  {
    path: '/vulns',
    name: 'vulns',
    component: () => import('@/views/admin/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '취약점 목록' },
  },
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
  // ========================================
  {
    path: '/dev/dashboard',
    name: 'dev-dashboard',
    component: () => import('@/views/dev/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['developer', 'approver'], layout: 'dev', title: '전체 현황' },
  },
  {
    path: '/dev/my-vulns',
    name: 'dev-my-vulns',
    component: () => import('@/views/dev/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['developer', 'approver'], layout: 'dev', title: '나의 현황' },
  },
  {
    path: '/dev/vulns',
    name: 'dev-vulns',
    component: () => import('@/views/dev/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['developer', 'approver'], layout: 'dev', title: '취약점 목록' },
  },
  {
    path: '/dev/approvals',
    name: 'dev-approvals',
    component: () => import('@/views/dev/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['approver'], layout: 'dev', title: '결재 관리' },
  },
  {
    path: '/dev/history',
    name: 'dev-history',
    component: () => import('@/views/dev/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['developer', 'approver'], layout: 'dev', title: '조치 이력' },
  },

  // ========================================
  // v11 Phase 5-5 — 담당자 "내 할 일"
  // ========================================
  // layout: 'dev' — 담당자 전용 워크스페이스이므로 DevLayout 사용.
  // roles 는 모두 허용하되, 추가로 permission_evidence=true 를 가드에서 체크.
  // API 레벨에서도 MyTasksService 가 한 번 더 검증한다.
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