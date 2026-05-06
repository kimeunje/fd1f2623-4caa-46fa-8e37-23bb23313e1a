<script setup lang="ts">
/**
 * 관리자 대시보드 (v16.4b — spec §3.8 정합 통째 교체).
 *
 * 이전 버전 (Phase 3 cleanup 후 mock 잔존): 4 mock 위젯 (취약점 수집 / 조치 /
 * 긴급 / 예정) 모두 spec §3.8 외. v16.4b 에서 통째 교체.
 *
 * 새 구성:
 *   1. 환영 메시지 ("안녕하세요, {name}님 👋") — Q4=A 보존
 *   2. 상단: KPI 카드 — "내 승인 대기 N건" (spec §3.8.1)
 *   3. 중간: 승인 대기 목록 (top 10) + Framework 진척 (2-column)
 *
 * BE: GET /api/v1/dashboard/admin-summary (v16.4a, AdminDashboardSummaryDto).
 *
 * 갱신: 진입 시 1회 + 새로고침 버튼. 자동 polling 미적용 (운영 피드백 후 별도 phase).
 */
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { dashboardApi } from '@/services/dashboardApi'
import type { AdminDashboardSummary } from '@/types/evidence'
import KpiCard from '@/components/dashboard/KpiCard.vue'
import PendingApprovalsList from '@/components/dashboard/PendingApprovalsList.vue'
import FrameworkProgressCard from '@/components/dashboard/FrameworkProgressCard.vue'

const authStore = useAuthStore()

// ========================================
// 상태
// ========================================
const summary = ref<AdminDashboardSummary | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

// 토스트
const toast = ref({
  show: false,
  message: '',
  type: 'success' as 'success' | 'error',
})
function showToast(message: string, type: 'success' | 'error' = 'success') {
  toast.value = { show: true, message, type }
  setTimeout(() => { toast.value.show = false }, 3000)
}

// ========================================
// 데이터 로드
// ========================================
async function load() {
  loading.value = true
  error.value = null
  try {
    const { data } = await dashboardApi.getAdminSummary()
    if (data.success) {
      summary.value = data.data
    } else {
      error.value = '대시보드 데이터를 불러오지 못했습니다.'
    }
  } catch (e: unknown) {
    console.error(e)
    // 401 은 api.ts 인터셉터가 자동 /login 리다이렉트 → 본 catch 도달 안 함
    // 403 은 router meta.roles=['admin'] 차단으로 호출 자체 발생 안 함
    // 그 외 (500 등) 만 본 catch 진입
    error.value = '대시보드 데이터를 불러오지 못했습니다.'
    showToast('대시보드 데이터를 불러오지 못했습니다.', 'error')
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ========================================
// 승인 대기 목록 섹션 스크롤 (KPI 클릭 시)
// ========================================
function scrollToPending() {
  const el = document.getElementById('pending-approvals-section')
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}
</script>

<template>
  <div class="p-6 space-y-6 max-w-6xl mx-auto">
    <!-- 토스트 -->
    <Transition name="toast">
      <div v-if="toast.show"
        class="fixed top-6 right-6 z-[60] px-4 py-3 rounded-lg shadow-lg text-sm font-medium flex items-center gap-2"
        :class="toast.type === 'success' ? 'bg-green-600 text-white' : 'bg-red-600 text-white'">
        <i :class="['pi text-sm', toast.type === 'success' ? 'pi-check-circle' : 'pi-times-circle']"></i>
        {{ toast.message }}
      </div>
    </Transition>

    <!-- ========================================
         환영 메시지 (Q4=A 보존)
         ======================================== -->
    <div class="bg-white rounded-xl border border-gray-200 p-6">
      <div class="flex items-start justify-between">
        <div>
          <h2 class="text-xl font-bold text-gray-900 mb-1">
            안녕하세요, {{ authStore.user?.name }}님 👋
          </h2>
          <p class="text-sm text-gray-500">
            SecuHub 관리자 대시보드입니다. 오늘 처리할 일을 확인하세요.
          </p>
        </div>
        <button
          @click="load"
          :disabled="loading"
          class="h-9 px-3 text-xs border border-gray-200 bg-white rounded-lg hover:bg-gray-50 disabled:opacity-50 inline-flex items-center gap-1.5">
          <i :class="['pi text-xs', loading ? 'pi-spin pi-spinner' : 'pi-refresh']"></i>
          새로고침
        </button>
      </div>
    </div>

    <!-- ========================================
         로딩
         ======================================== -->
    <div v-if="loading && !summary" class="py-16 text-center text-gray-400 text-sm">
      <i class="pi pi-spin pi-spinner text-2xl mb-2"></i>
      <p>대시보드 데이터를 불러오는 중...</p>
    </div>

    <!-- ========================================
         에러
         ======================================== -->
    <div v-else-if="error && !summary"
      class="py-12 text-center bg-red-50 border border-red-200 rounded-xl">
      <i class="pi pi-exclamation-triangle text-red-500 text-2xl mb-2"></i>
      <p class="text-sm text-red-700">{{ error }}</p>
      <button @click="load" class="mt-3 px-3 py-1.5 text-xs bg-white border border-red-200 text-red-700 rounded-lg hover:bg-red-50">
        다시 시도
      </button>
    </div>

    <!-- ========================================
         데이터 있음
         ======================================== -->
    <template v-else-if="summary">
      <!-- KPI 카드 (1열 또는 2열, 향후 KPI 증설 대비 grid) -->
      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <KpiCard
          :count="summary.kpi.pendingApprovalCount"
          @click="scrollToPending" />
        <!-- 향후 KPI 추가 시 여기에 카드 추가 -->
      </div>

      <!-- 하단 2 위젯 -->
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <!-- 승인 대기 목록 -->
        <div id="pending-approvals-section" class="scroll-mt-6">
          <PendingApprovalsList :items="summary.pendingApprovals" />
        </div>

        <!-- Framework 진척 -->
        <div>
          <div class="bg-white rounded-xl border border-gray-200">
            <!-- 헤더 -->
            <div class="p-4 border-b border-gray-200 flex items-center gap-2">
              <i class="pi pi-chart-bar text-blue-500"></i>
              <h3 class="font-bold text-gray-900">Framework 진척</h3>
              <span v-if="summary.frameworkProgresses.length > 0" class="ml-auto text-xs text-gray-500">
                활성 {{ summary.frameworkProgresses.length }}개
              </span>
            </div>

            <!-- 빈 상태 (Q3=B) -->
            <div v-if="summary.frameworkProgresses.length === 0"
              class="p-8 text-center text-sm text-gray-400">
              <i class="pi pi-folder-open text-2xl text-gray-300 mb-2"></i>
              <p class="mb-3">아직 활성 Framework 가 없습니다.</p>
              <button
                @click="$router.push({ name: 'framework-create-wizard' })"
                class="px-3 py-1.5 text-xs bg-blue-50 text-blue-700 border border-blue-200 rounded-lg hover:bg-blue-100">
                새 Framework 생성
              </button>
            </div>

            <!-- 진척 카드 목록 -->
            <div v-else class="p-4 space-y-3">
              <FrameworkProgressCard
                v-for="fw in summary.frameworkProgresses"
                :key="fw.frameworkId"
                :item="fw" />
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}

/* KPI 클릭 시 부드러운 스크롤 + scroll-mt 안전망 */
.scroll-mt-6 {
  scroll-margin-top: 1.5rem;
}
</style>