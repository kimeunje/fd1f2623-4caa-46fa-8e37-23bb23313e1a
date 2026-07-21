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
    routeAfterLogin(user.role)
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

/**
 * v19.29 — 단말 IP 자동 로그인. 등록된 단말이면 계정 입력 없이 로그인.
 * 실패(매핑 없음/모호/미허용 역할)면 안내만 표시하고 계정 폼은 그대로 남겨 폴백.
 */
async function handleLoginByIp() {
  loading.value = true
  errorMsg.value = ''
  try {
    const user = await authStore.loginByIp()
    routeAfterLogin(user.role)
  } catch (err: any) {
    errorMsg.value =
      err.response?.data?.message ||
      err.message ||
      '이 단말로 자동 로그인할 수 없습니다. 계정으로 로그인하세요.'
  } finally {
    loading.value = false
  }
}

function routeAfterLogin(role: string) {
  if (role === 'admin') router.push('/dashboard')
  else if (role === 'reviewer') router.push('/review')
  else router.push('/dev/dashboard')
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

      <!-- v19.29 — 단말 IP 자동 로그인 -->
      <div class="relative my-5">
        <div class="absolute inset-0 flex items-center"><div class="w-full border-t border-gray-200"></div></div>
        <div class="relative flex justify-center"><span class="bg-white px-2 text-xs text-gray-400">또는</span></div>
      </div>

      <button
        type="button"
        :disabled="loading"
        @click="handleLoginByIp"
        class="w-full py-3 border border-blue-500 text-blue-600 rounded-xl font-medium hover:bg-blue-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-2"
      >
        <i class="pi pi-desktop"></i>
        이 단말로 로그인
      </button>
      <p class="mt-2 text-center text-xs text-gray-400">등록된 단말이면 계정 입력 없이 로그인됩니다.</p>
    </div>
  </div>
</template>