<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { usersApi } from '@/services/api'
import type { User } from '@/types'

const users = ref<User[]>([])
const total = ref(0)
const loading = ref(false)
const search = ref('')

const roleLabels: Record<string, { label: string; bg: string; text: string }> = {
  admin: { label: '관리자', bg: 'bg-blue-100', text: 'text-blue-700' },
  approver: { label: '결재자', bg: 'bg-amber-100', text: 'text-amber-700' },
  developer: { label: '개발자', bg: 'bg-green-100', text: 'text-green-700' },
}

async function loadUsers() {
  loading.value = true
  try {
    const res = await usersApi.list({ search: search.value || undefined })
    users.value = res.data.items
    total.value = res.data.total
  } catch (err) {
    console.error('사용자 목록 로드 실패:', err)
  } finally {
    loading.value = false
  }
}

onMounted(loadUsers)
</script>

<template>
  <div class="p-6 space-y-4">
    <!-- 상단 필터/액션 -->
    <div class="flex items-center justify-between">
      <div class="relative">
        <input
          v-model="search"
          @input="loadUsers"
          type="text"
          placeholder="이름 또는 이메일 검색..."
          class="pl-9 pr-4 py-2 border border-gray-300 rounded-lg text-sm w-64"
        />
        <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-sm"></i>
      </div>
      <button class="flex items-center gap-2 px-4 py-2 bg-blue-500 text-white rounded-lg text-sm font-medium hover:bg-blue-600">
        <i class="pi pi-plus text-sm"></i>
        계정 추가
      </button>
    </div>

    <!-- 테이블 -->
    <div class="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <div v-if="loading" class="p-8 text-center text-gray-500">
        <i class="pi pi-spin pi-spinner text-xl mb-2"></i>
        <p class="text-sm">로딩 중...</p>
      </div>

      <table v-else class="w-full">
        <thead class="bg-gray-50 border-b border-gray-200">
          <tr>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-600">사용자</th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-600">소속</th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-600">역할</th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-600">접근 권한</th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-600">마지막 로그인</th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-600">상태</th>
            <th class="px-4 py-3 w-20"></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-for="user in users" :key="user.id" class="hover:bg-gray-50">
            <td class="px-4 py-3">
              <div class="flex items-center gap-3">
                <div class="w-8 h-8 bg-gray-100 rounded-full flex items-center justify-center text-gray-600 font-medium text-sm">
                  {{ user.name[0] }}
                </div>
                <div>
                  <p class="text-sm font-medium text-gray-900">{{ user.name }}</p>
                  <p class="text-xs text-gray-500">{{ user.email }}</p>
                </div>
              </div>
            </td>
            <td class="px-4 py-3 text-sm text-gray-600">{{ user.team || '-' }}</td>
            <td class="px-4 py-3">
              <span
                :class="[
                  'px-2 py-1 rounded text-xs font-medium',
                  roleLabels[user.role]?.bg,
                  roleLabels[user.role]?.text,
                ]"
              >
                {{ roleLabels[user.role]?.label }}
              </span>
            </td>
            <td class="px-4 py-3">
              <div class="flex gap-1">
                <span v-if="user.permission_evidence" class="px-1.5 py-0.5 bg-purple-50 text-purple-600 text-xs rounded">증빙</span>
                <span v-if="user.permission_vuln" class="px-1.5 py-0.5 bg-orange-50 text-orange-600 text-xs rounded">취약점</span>
              </div>
            </td>
            <td class="px-4 py-3 text-sm text-gray-500">
              {{ user.last_login_at ? new Date(user.last_login_at).toLocaleString('ko') : '-' }}
            </td>
            <td class="px-4 py-3">
              <span
                :class="[
                  'px-2 py-1 rounded text-xs font-medium',
                  user.status === 'active' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500',
                ]"
              >
                {{ user.status === 'active' ? '활성' : '비활성' }}
              </span>
            </td>
            <td class="px-4 py-3">
              <button class="p-1.5 text-gray-400 hover:text-blue-500">
                <i class="pi pi-pencil text-sm"></i>
              </button>
            </td>
          </tr>

          <tr v-if="users.length === 0 && !loading">
            <td colspan="7" class="px-4 py-8 text-center text-gray-500 text-sm">
              등록된 사용자가 없습니다.
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 페이지 정보 -->
      <div v-if="total > 0" class="px-4 py-3 border-t border-gray-200 text-sm text-gray-500">
        총 {{ total }}명
      </div>
    </div>
  </div>
</template>
