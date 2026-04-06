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
    // 백엔드 ApiResponse 에러: { success: false, message: "..." }
    errorMsg.value =
      err.response?.data?.message ||
      err.message ||
      '로그인에 실패했습니다.'
  } finally {
    loading.value = false
  }
}

// 데모 계정 바로 로그인
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

      <!-- 데모 계정 -->
      <div class="border-t border-gray-200 pt-4">
        <p class="text-xs text-gray-400 mb-3 text-center">데모 계정으로 바로 로그인</p>
        <div class="grid grid-cols-2 gap-2">
          <button
            @click="demoLogin('admin@company.com', 'admin1234')"
            class="py-2 px-3 bg-blue-50 text-blue-600 rounded-lg text-xs font-medium hover:bg-blue-100 transition-colors"
          >
            관리자 (보안팀)
          </button>
          <button
            @click="demoLogin('park_tl@company.com', 'park1234')"
            class="py-2 px-3 bg-emerald-50 text-emerald-600 rounded-lg text-xs font-medium hover:bg-emerald-100 transition-colors"
          >
            결재자 (박팀장)
          </button>
          <button
            @click="demoLogin('kim@company.com', 'dev1234')"
            class="py-2 px-3 bg-gray-50 text-gray-600 rounded-lg text-xs font-medium hover:bg-gray-100 transition-colors"
          >
            개발자 (김개발)
          </button>
          <button
            @click="demoLogin('lee@company.com', 'dev1234')"
            class="py-2 px-3 bg-gray-50 text-gray-600 rounded-lg text-xs font-medium hover:bg-gray-100 transition-colors"
          >
            개발자 (이보안)
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
