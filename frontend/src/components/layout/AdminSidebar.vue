<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const expandedMenus = ref<string[]>(['evidence', 'vuln'])

function toggleMenu(menuId: string) {
  const idx = expandedMenus.value.indexOf(menuId)
  if (idx >= 0) {
    expandedMenus.value.splice(idx, 1)
  } else {
    expandedMenus.value.push(menuId)
  }
}

function navigate(routeName: string) {
  router.push({ name: routeName })
}

/**
 * 메뉴 활성 여부 판정.
 *
 * v11 Phase 5-3:
 * - "통제 항목" 메뉴는 /controls (framework-list) 와 /controls/:id (framework-detail)
 *   둘 다 활성으로 표시하기 위해 route.path 기반으로 판정.
 */
function isActive(routeName: string) {
  if (routeName === 'framework-list') {
    return route.path === '/controls' || route.path.startsWith('/controls/')
  }
  return route.name === routeName
}

const menuGroups = [
  {
    id: 'main',
    items: [
      { routeName: 'admin-dashboard', icon: 'pi-home', label: '대시보드' },
    ],
  },
  {
    id: 'evidence',
    label: '증빙 수집',
    items: [
      // v11 Phase 5-3: 'controls' → 'framework-list' (진입 페이지)
      { routeName: 'framework-list', icon: 'pi-list', label: '통제 항목' },
      { routeName: 'jobs', icon: 'pi-play', label: '수집 작업' },
      { routeName: 'files', icon: 'pi-folder', label: '증빙 파일' },
    ],
  },
  {
    id: 'vuln',
    label: '취약점 관리',
    items: [
      { routeName: 'assessments', icon: 'pi-list-check', label: '점검 관리' },
    ],
  },
  {
    id: 'system',
    label: '시스템',
    items: [
      { routeName: 'accounts', icon: 'pi-users', label: '계정 관리' },
      { routeName: 'settings', icon: 'pi-cog', label: '설정' },
    ],
  },
]
</script>

<template>
  <div class="w-60 bg-white border-r border-gray-200 h-screen flex flex-col fixed left-0 top-0 z-20">
    <!-- 로고 -->
    <div class="h-16 flex items-center px-5 border-b border-gray-200">
      <div class="flex items-center gap-2">
        <div class="w-8 h-8 bg-blue-500 rounded-lg flex items-center justify-center">
          <i class="pi pi-shield text-white text-sm"></i>
        </div>
        <span class="font-bold text-gray-900">SecuHub</span>
        <span class="ml-1 px-1.5 py-0.5 bg-blue-100 text-blue-700 text-xs rounded font-medium">Admin</span>
      </div>
    </div>

    <!-- 메뉴 -->
    <nav class="flex-1 p-3 overflow-y-auto">
      <div v-for="(group, gIdx) in menuGroups" :key="group.id" :class="gIdx > 0 ? 'mt-4' : ''">
        <!-- 그룹 라벨 -->
        <button
          v-if="group.label"
          @click="toggleMenu(group.id)"
          class="w-full flex items-center justify-between px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wider hover:text-gray-600"
        >
          {{ group.label }}
          <i :class="expandedMenus.includes(group.id) ? 'pi pi-chevron-down' : 'pi pi-chevron-right'" class="text-xs"></i>
        </button>

        <!-- 메뉴 항목 -->
        <div v-show="!group.label || expandedMenus.includes(group.id)" class="space-y-0.5">
          <button
            v-for="item in group.items"
            :key="item.routeName"
            @click="navigate(item.routeName)"
            :class="[
              'w-full flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors',
              isActive(item.routeName)
                ? 'bg-blue-50 text-blue-700 font-medium'
                : 'text-gray-700 hover:bg-gray-50'
            ]"
          >
            <i :class="['pi', item.icon, 'text-sm']"></i>
            <span>{{ item.label }}</span>
          </button>
        </div>
      </div>
    </nav>

    <!-- 사용자 영역 -->
    <div class="p-3 border-t border-gray-200">
      <div class="flex items-center gap-3 px-3 py-2">
        <div class="w-8 h-8 bg-gray-200 rounded-full flex items-center justify-center text-xs font-medium text-gray-600">
          {{ authStore.user?.name?.charAt(0) || '?' }}
        </div>
        <div class="flex-1 min-w-0">
          <div class="text-sm font-medium text-gray-900 truncate">{{ authStore.user?.name || '-' }}</div>
          <div class="text-xs text-gray-500 truncate">{{ authStore.user?.team || authStore.user?.email || '-' }}</div>
        </div>
        <button @click="authStore.logout(); router.push('/login')" class="p-1.5 text-gray-400 hover:text-gray-600" title="로그아웃">
          <i class="pi pi-sign-out text-sm"></i>
        </button>
      </div>
    </div>
  </div>
</template>