<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

function navigate(routeName: string) {
  router.push({ name: routeName })
}

function isActive(routeName: string) {
  return route.name === routeName
}

const menuItems = [
  { routeName: 'dev-dashboard', icon: 'pi-home', label: '전체 현황' },
  { routeName: 'dev-my-vulns', icon: 'pi-user', label: '나의 현황' },
  { routeName: 'dev-vulns', icon: 'pi-exclamation-circle', label: '취약점 목록' },
  { routeName: 'dev-approvals', icon: 'pi-check-square', label: '결재 관리', roles: ['approver'] },
  { routeName: 'dev-history', icon: 'pi-clock', label: '조치 이력' },
]
</script>

<template>
  <div class="w-60 bg-white border-r border-gray-200 h-screen flex flex-col fixed left-0 top-0 z-20">
    <!-- 로고 -->
    <div class="h-16 flex items-center px-5 border-b border-gray-200">
      <div class="flex items-center gap-2">
        <div class="w-8 h-8 bg-emerald-500 rounded-lg flex items-center justify-center">
          <i class="pi pi-exclamation-circle text-white text-sm"></i>
        </div>
        <span class="font-bold text-gray-900">SecuHub</span>
        <span class="ml-1 px-1.5 py-0.5 bg-emerald-100 text-emerald-700 text-xs rounded font-medium">Dev</span>
      </div>
    </div>

    <!-- 메뉴 -->
    <nav class="flex-1 p-3">
      <div class="space-y-1">
        <template v-for="item in menuItems" :key="item.routeName">
          <button
            v-if="!item.roles || item.roles.includes(authStore.user?.role || '')"
            @click="navigate(item.routeName)"
            :class="[
              'w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors',
              isActive(item.routeName)
                ? 'bg-emerald-50 text-emerald-600'
                : 'text-gray-600 hover:bg-gray-100',
            ]"
          >
            <i :class="['pi', item.icon, 'text-base']"></i>
            {{ item.label }}
          </button>
        </template>
      </div>
    </nav>

    <!-- 사용자 정보 -->
    <div class="p-4 border-t border-gray-200">
      <div class="flex items-center gap-3 mb-3">
        <div class="w-8 h-8 bg-emerald-100 rounded-full flex items-center justify-center text-emerald-600 font-medium text-sm">
          {{ authStore.user?.name?.[0] || '?' }}
        </div>
        <div class="flex-1 min-w-0">
          <p class="text-sm font-medium text-gray-900 truncate">{{ authStore.user?.name }}</p>
          <p class="text-xs text-gray-500 truncate">{{ authStore.user?.team }}</p>
        </div>
      </div>
      <button
        @click="authStore.logout(); router.push('/login')"
        class="w-full flex items-center gap-2 px-3 py-2 text-xs text-gray-500 hover:text-red-600 hover:bg-red-50 rounded-lg transition-colors"
      >
        <i class="pi pi-sign-out text-xs"></i>
        로그아웃
      </button>
    </div>
  </div>
</template>
