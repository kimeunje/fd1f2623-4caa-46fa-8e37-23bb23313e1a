<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { frameworksApi } from '@/services/evidenceApi'
import type { Framework } from '@/types/evidence'

const router = useRouter()

const frameworks = ref<Framework[]>([])
const loading = ref(false)

// 신규 Framework 다이얼로그
const showCreateDialog = ref(false)
const newFramework = ref({ name: '', description: '' })
const createLoading = ref(false)

// 토스트
const toast = ref({ show: false, message: '', type: 'success' as 'success' | 'error' })
function showToast(message: string, type: 'success' | 'error' = 'success') {
  toast.value = { show: true, message, type }
  setTimeout(() => { toast.value.show = false }, 3000)
}

// ========================================
// 데이터 로드
// ========================================
async function loadFrameworks() {
  loading.value = true
  try {
    const { data } = await frameworksApi.list()
    if (data.success) {
      frameworks.value = data.data
    }
  } catch (e) {
    console.error(e)
    showToast('Framework 목록을 불러오지 못했습니다.', 'error')
  } finally {
    loading.value = false
  }
}

onMounted(loadFrameworks)

// ========================================
// Computed
// ========================================

// 최근 작업 중 — active 상태의 최신 생성 Framework
const recentFramework = computed<Framework | null>(() => {
  const actives = frameworks.value.filter(f => f.status !== 'archived')
  if (actives.length === 0) return null
  // createdAt 내림차순 정렬 후 첫 번째
  const sorted = [...actives].sort((a, b) => {
    return (b.createdAt || '').localeCompare(a.createdAt || '')
  })
  return sorted[0]
})

// 대시보드 카드 집계 — 최근 작업 중 Framework 의 수집 진행률
const recentProgress = computed(() => {
  const fw = recentFramework.value
  if (!fw || !fw.evidenceTypeCount) return 0
  // 간이 계산: 수집 완료는 알 수 없으므로 controlCount 기반 추정은 보류.
  // Phase 5-8 에서 backend 응답에 진행률 직접 포함되면 그때 정확히 표시.
  // 지금은 배지 중심으로 표시하고 진행률은 "—" 로 표기.
  return null
})

// ========================================
// 액션
// ========================================

function openFramework(fw: Framework) {
  router.push({ name: 'framework-detail', params: { frameworkId: fw.id } })
}

async function handleCreate() {
  if (!newFramework.value.name.trim()) {
    showToast('Framework 이름을 입력하세요.', 'error')
    return
  }
  createLoading.value = true
  try {
    const { data } = await frameworksApi.create({
      name: newFramework.value.name.trim(),
      description: newFramework.value.description.trim() || undefined,
    })
    if (data.success) {
      showToast('Framework 가 생성되었습니다.', 'success')
      showCreateDialog.value = false
      newFramework.value = { name: '', description: '' }
      await loadFrameworks()
      // 신규 Framework 상세 페이지로 바로 이동
      router.push({ name: 'framework-detail', params: { frameworkId: data.data.id } })
    }
  } catch (e: any) {
    console.error(e)
    showToast(e.response?.data?.message || 'Framework 생성에 실패했습니다.', 'error')
  } finally {
    createLoading.value = false
  }
}

// ========================================
// 포매팅 헬퍼
// ========================================

function formatDate(iso?: string): string {
  if (!iso) return '-'
  return iso.substring(0, 10)
}

function statusBadge(fw: Framework) {
  if (fw.status === 'archived') {
    return { text: '아카이브', cls: 'bg-gray-100 text-gray-500' }
  }
  // active 중 파일 비율 등으로 "진행중 / 완료" 를 나누려면 추가 정보 필요.
  // 지금은 pendingReviewCount 존재 여부를 기준으로 간단 표기.
  return { text: '진행중', cls: 'bg-blue-50 text-blue-700' }
}
</script>

<template>
  <div class="p-6 space-y-6">
    <!-- Toast -->
    <Transition enter-active-class="transition-all duration-300" enter-from-class="translate-y-2 opacity-0"
      leave-active-class="transition-all duration-200" leave-to-class="translate-y-2 opacity-0">
      <div v-if="toast.show"
        class="fixed top-4 right-4 z-[60] px-4 py-2 rounded-lg shadow-lg flex items-center gap-2 text-sm"
        :class="toast.type === 'success' ? 'bg-green-600 text-white' : 'bg-red-600 text-white'">
        <i :class="['pi text-sm', toast.type === 'success' ? 'pi-check-circle' : 'pi-times-circle']"></i>
        {{ toast.message }}
      </div>
    </Transition>

    <!-- ========================================
         헤더
         ======================================== -->
    <div>
      <h1 class="text-xl font-bold text-gray-900">통제 항목</h1>
      <p class="text-sm text-gray-500 mt-1">프레임워크를 선택하세요. 통제 항목과 증빙 수집 현황을 관리할 수 있습니다.</p>
    </div>

    <!-- ========================================
         최근 작업 중 카드 (진입 포인트)
         ======================================== -->
    <button
      v-if="recentFramework"
      @click="openFramework(recentFramework)"
      class="w-full text-left group block">
      <div class="bg-gradient-to-br from-gray-900 to-gray-800 text-white rounded-xl p-5 hover:shadow-lg transition-shadow">
        <div class="flex items-center justify-between">
          <div>
            <div class="text-[11px] text-gray-400 uppercase tracking-wide mb-1">최근 작업 중</div>
            <div class="text-xl font-medium mb-1 flex items-center gap-2">
              {{ recentFramework.name }}
              <span
                v-if="recentFramework.pendingReviewCount && recentFramework.pendingReviewCount > 0"
                class="inline-block px-2 py-0.5 text-[10px] font-medium rounded bg-blue-600 text-white">
                검토 대기 {{ recentFramework.pendingReviewCount }}건
              </span>
            </div>
            <div class="text-xs text-gray-300">
              통제 {{ recentFramework.controlCount }}개 ·
              증빙 {{ recentFramework.evidenceTypeCount ?? 0 }}개 ·
              수집 작업 {{ recentFramework.jobCount ?? 0 }}개
            </div>
          </div>
          <div class="flex items-center gap-3">
            <i class="pi pi-chevron-right text-gray-400 group-hover:text-white transition-colors"></i>
          </div>
        </div>
      </div>
    </button>

    <!-- 빈 상태 (최근 작업 Framework 없음) -->
    <div v-else-if="!loading" class="bg-white border border-dashed border-gray-300 rounded-xl p-8 text-center">
      <i class="pi pi-folder-open text-3xl text-gray-300 mb-2"></i>
      <p class="text-sm text-gray-500">아직 Framework 가 없습니다. 아래에서 신규 Framework 를 생성해보세요.</p>
    </div>

    <!-- ========================================
         전체 Framework 목록
         ======================================== -->
    <div>
      <div class="flex items-center justify-between mb-3">
        <h3 class="text-sm font-medium text-gray-600">전체 Framework</h3>
        <div class="flex gap-2">
          <!-- 상속 버튼 — Phase 5-6 에서 활성화 -->
          <button
            disabled
            title="Phase 5-6 에서 구현 예정"
            class="h-8 px-3 text-xs border border-gray-200 bg-white rounded-md text-gray-400 cursor-not-allowed inline-flex items-center gap-1.5">
            <i class="pi pi-sitemap text-xs"></i>
            상속하여 생성
          </button>
          <button
            @click="showCreateDialog = true"
            class="h-8 px-3 text-xs bg-blue-600 text-white rounded-md hover:bg-blue-700 inline-flex items-center gap-1.5">
            <i class="pi pi-plus text-xs"></i>
            새 Framework
          </button>
        </div>
      </div>

      <div class="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <!-- Header -->
        <div class="grid gap-4 px-5 py-3 text-[11px] font-semibold text-gray-500 border-b border-gray-100"
          style="grid-template-columns: 1fr 90px 90px 80px 110px">
          <div>Framework</div>
          <div>통제</div>
          <div>증빙</div>
          <div>작업</div>
          <div>상태</div>
        </div>

        <!-- Loading -->
        <div v-if="loading" class="px-5 py-8 text-center text-sm text-gray-400">
          <i class="pi pi-spin pi-spinner mr-1"></i> 불러오는 중…
        </div>

        <!-- Rows -->
        <button
          v-for="fw in frameworks"
          :key="fw.id"
          @click="openFramework(fw)"
          class="w-full text-left grid gap-4 px-5 py-4 text-sm items-center border-b border-gray-100 last:border-b-0 hover:bg-gray-50"
          style="grid-template-columns: 1fr 90px 90px 80px 110px">
          <div>
            <div class="flex items-center gap-2">
              <span class="font-medium" :class="fw.status === 'archived' ? 'text-gray-600' : 'text-gray-900'">
                {{ fw.name }}
              </span>
              <span
                v-if="fw.pendingReviewCount && fw.pendingReviewCount > 0"
                class="inline-block px-1.5 py-0.5 text-[10px] font-medium rounded bg-blue-50 text-blue-700">
                ● 검토 {{ fw.pendingReviewCount }}
              </span>
            </div>
            <div class="text-[11px] text-gray-400 mt-0.5">
              <span v-if="fw.parentFrameworkName">{{ fw.parentFrameworkName }} 상속</span>
              <span v-else>신규 생성</span>
              · {{ formatDate(fw.createdAt) }}
            </div>
          </div>
          <div :class="fw.status === 'archived' ? 'text-gray-500' : 'text-gray-600'">{{ fw.controlCount ?? 0 }}</div>
          <div :class="fw.status === 'archived' ? 'text-gray-500' : 'text-gray-600'">{{ fw.evidenceTypeCount ?? 0 }}</div>
          <div :class="fw.status === 'archived' ? 'text-gray-500' : 'text-gray-600'">{{ fw.jobCount ?? 0 }}</div>
          <div>
            <span
              class="inline-block px-2 py-0.5 text-[11px] font-medium rounded-md"
              :class="statusBadge(fw).cls">
              {{ statusBadge(fw).text }}
            </span>
          </div>
        </button>

        <!-- Empty -->
        <div v-if="!loading && frameworks.length === 0" class="px-5 py-12 text-center text-sm text-gray-400">
          등록된 Framework 가 없습니다.
        </div>
      </div>

      <!-- 흐름 안내 -->
      <div class="mt-6 text-[11px] text-gray-400 leading-relaxed bg-white border border-gray-100 rounded-lg p-4">
        <p class="font-medium text-gray-600 mb-1">흐름 안내</p>
        <p>① 최근 작업 중 카드 또는 Framework 행 클릭 → Framework 상세 진입<br>
        ② Framework 상세에서 상단 메뉴 클릭 → 다른 Framework 로 전환<br>
        ③ 사이드바 "통제 항목" 재클릭 → 이 목록으로 복귀</p>
      </div>
    </div>

    <!-- ========================================
         다이얼로그: 신규 Framework 생성
         ======================================== -->
    <div v-if="showCreateDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50"
      @click.self="showCreateDialog = false">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-gray-900">새 Framework 생성</h3>
          <button @click="showCreateDialog = false" class="p-1 text-gray-400 hover:text-gray-600">
            <i class="pi pi-times"></i>
          </button>
        </div>
        <div class="space-y-3">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">이름 *</label>
            <input
              v-model="newFramework.name"
              class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              placeholder="ISMS-P 2026" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">설명</label>
            <textarea
              v-model="newFramework.description"
              rows="3"
              class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none resize-none"
              placeholder="간단한 설명 (선택)"></textarea>
          </div>
        </div>
        <div class="flex justify-end gap-2 mt-5">
          <button
            @click="showCreateDialog = false"
            class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-lg">
            취소
          </button>
          <button
            @click="handleCreate"
            :disabled="createLoading || !newFramework.name.trim()"
            class="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50">
            {{ createLoading ? '생성 중…' : '생성' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>