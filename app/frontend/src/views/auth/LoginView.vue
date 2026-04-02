<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const email = ref('')
const password = ref('')
const loading = ref(false)
const errorMsg = ref('')

async function handleLogin() {
  if (!email.value || !password.value) {
    errorMsg.value = '이메일과 비밀번호를 입력해주세요.'
    return
  }

  loading.value = true
  errorMsg.value = ''

  try {
    const user = await authStore.login({ email: email.value, password: password.value })
    if (user.role === 'admin') {
      router.push('/dashboard')
    } else {
      router.push('/dev/dashboard')
    }
  } catch (err: any) {
    errorMsg.value = err.response?.data?.detail || '로그인에 실패했습니다.'
  } finally {
    loading.value = false
  }
}

// 데모 로그인
function demoLogin(demoEmail: string, demoPassword: string) {
  email.value = demoEmail
  password.value = demoPassword
  handleLogin()
}
</script>

<template>
  <div class="min-h-screen bg-gray-100 flex items-center justify-center">
    <div class="bg-white rounded-2xl shadow-xl p-8 w-full max-w-md">
      <!-- 로고 -->
      <div class="text-center mb-8">
        <div class="w-16 h-16 bg-blue-500 rounded-2xl flex items-center justify-center mx-auto mb-4">
          <i class="pi pi-shield text-white text-2xl"></i>
        </div>
        <h1 class="text-2xl font-bold text-gray-900">SecuHub</h1>
        <p class="text-gray-500 mt-1">보안 운영 통합 관리 플랫폼</p>
      </div>

      <!-- 로그인 폼 -->
      <form @submit.prevent="handleLogin" class="space-y-4 mb-6">
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">이메일</label>
          <input
            v-model="email"
            type="email"
            placeholder="이메일을 입력하세요"
            class="w-full px-4 py-3 border border-gray-300 rounded-xl text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
          />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">비밀번호</label>
          <input
            v-model="password"
            type="password"
            placeholder="비밀번호를 입력하세요"
            class="w-full px-4 py-3 border border-gray-300 rounded-xl text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
          />
        </div>

        <!-- 에러 메시지 -->
        <div v-if="errorMsg" class="p-3 bg-red-50 border border-red-200 rounded-lg">
          <p class="text-sm text-red-600">{{ errorMsg }}</p>
        </div>

        <button
          type="submit"
          :disabled="loading"
          class="w-full py-3 bg-blue-500 text-white rounded-xl font-medium hover:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {{ loading ? '로그인 중...' : '로그인' }}
        </button>
      </form>

      <!-- 구분선 -->
      <div class="relative mb-6">
        <div class="absolute inset-0 flex items-center">
          <div class="w-full border-t border-gray-200"></div>
        </div>
        <div class="relative flex justify-center">
          <span class="bg-white px-4 text-sm text-gray-400">데모 계정</span>
        </div>
      </div>

      <!-- 데모 로그인 버튼 -->
      <div class="space-y-3">
        <button
          @click="demoLogin('admin@company.com', 'admin1234')"
          class="w-full p-4 border-2 border-gray-200 rounded-xl hover:border-blue-500 hover:bg-blue-50 transition-colors text-left"
        >
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 bg-blue-100 rounded-full flex items-center justify-center text-blue-600 font-bold text-sm">관</div>
            <div>
              <p class="font-medium text-gray-900">보안팀 관리자</p>
              <p class="text-sm text-gray-500">전체 기능 접근 가능</p>
            </div>
          </div>
        </button>

        <button
          @click="demoLogin('kim@company.com', 'dev1234')"
          class="w-full p-4 border-2 border-gray-200 rounded-xl hover:border-emerald-500 hover:bg-emerald-50 transition-colors text-left"
        >
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 bg-emerald-100 rounded-full flex items-center justify-center text-emerald-600 font-bold text-sm">김</div>
            <div>
              <p class="font-medium text-gray-900">김개발 (백엔드팀)</p>
              <p class="text-sm text-gray-500">취약점 조치 및 담당자 지정</p>
            </div>
          </div>
        </button>

        <button
          @click="demoLogin('park_tl@company.com', 'park1234')"
          class="w-full p-4 border-2 border-gray-200 rounded-xl hover:border-amber-500 hover:bg-amber-50 transition-colors text-left"
        >
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 bg-amber-100 rounded-full flex items-center justify-center text-amber-600 font-bold text-sm">박</div>
            <div>
              <p class="font-medium text-gray-900">박팀장 (백엔드팀)</p>
              <p class="text-sm text-gray-500">일정 결재 승인/반려</p>
            </div>
          </div>
        </button>
      </div>
    </div>
  </div>
</template>
