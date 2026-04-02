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
  {
    path: '/controls',
    name: 'controls',
    component: () => import('@/views/admin/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '통제 항목' },
  },
  {
    path: '/jobs',
    name: 'jobs',
    component: () => import('@/views/admin/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '수집 작업' },
  },
  {
    path: '/files',
    name: 'files',
    component: () => import('@/views/admin/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '증빙 파일' },
  },
  {
    path: '/assessments',
    name: 'assessments',
    component: () => import('@/views/admin/PlaceholderView.vue'),
    meta: { requiresAuth: true, roles: ['admin'], layout: 'admin', title: '점검 관리' },
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

// 네비게이션 가드
router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()

  // 인증 불필요 페이지
  if (to.meta.requiresAuth === false) {
    if (authStore.isAuthenticated && to.name === 'login') {
      // 이미 로그인 → 역할별 기본 페이지로
      next(authStore.isAdmin ? '/dashboard' : '/dev/dashboard')
      return
    }
    next()
    return
  }

  // 인증 필요 페이지
  if (!authStore.isAuthenticated) {
    next('/login')
    return
  }

  // 역할 체크
  const allowedRoles = to.meta.roles as string[] | undefined
  if (allowedRoles && !allowedRoles.includes(authStore.user?.role || '')) {
    // 역할 불일치 → 본인 기본 페이지로
    next(authStore.isAdmin ? '/dashboard' : '/dev/dashboard')
    return
  }

  next()
})

export default router
