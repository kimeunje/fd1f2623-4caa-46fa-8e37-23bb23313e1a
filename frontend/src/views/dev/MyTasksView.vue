<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { myTasksApi } from '@/services/evidenceApi'
import type { MyTasksResponse, MyTaskItem, MyTaskSectionKey } from '@/types/evidence'

const router = useRouter()

const loading = ref(true)
const error = ref<string | null>(null)
const data = ref<MyTasksResponse | null>(null)
const completedExpanded = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await myTasksApi.list()
    if (res.data.success) {
      data.value = res.data.data
    } else {
      error.value = res.data.message ?? '조회에 실패했습니다.'
    }
  } catch (e: any) {
    if (e?.response?.status === 403) {
      error.value = '증빙 수집 권한이 없습니다. 관리자에게 문의해주세요.'
    } else {
      error.value = e?.response?.data?.message ?? '내 할 일 조회에 실패했습니다.'
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)

function goToDetail(item: MyTaskItem) {
  router.push({ name: 'my-task-detail', params: { evidenceTypeId: item.evidenceTypeId } })
}

// ========================================
// 표시 헬퍼
// ========================================

function formatDate(s?: string) {
  if (!s) return '-'
  const d = new Date(s)
  if (isNaN(d.getTime())) return s.substring(0, 10)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function formatDateTime(s?: string) {
  if (!s) return '-'
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${day} ${hh}:${mm}`
}

/** D-day 라벨. 음수 = 지남, 0 = 오늘, 양수 = 남음. */
function dDayLabel(days?: number): string | null {
  if (days == null) return null
  if (days < 0) return `${-days}일 지남`
  if (days === 0) return 'D-DAY'
  return `D-${days}`
}

function dDayClass(days?: number): string {
  if (days == null) return 'bg-gray-100 text-gray-600'
  if (days < 0) return 'bg-red-100 text-red-700'
  if (days <= 3) return 'bg-orange-100 text-orange-700'
  return 'bg-amber-100 text-amber-700'
}

// ========================================
// KPI 카드 정의
// ========================================

interface KpiSpec {
  key: MyTaskSectionKey
  label: string
  icon: string
  iconColor: string
  bgColor: string
  borderColor: string
  hintColor: string
}

const kpis: KpiSpec[] = [
  {
    key: 'rejected',
    label: '반려됨',
    icon: 'pi-times-circle',
    iconColor: 'text-red-600',
    bgColor: 'bg-red-50',
    borderColor: 'border-red-200',
    hintColor: 'text-red-700',
  },
  {
    key: 'dueSoon',
    label: '마감 임박',
    icon: 'pi-clock',
    iconColor: 'text-orange-600',
    bgColor: 'bg-orange-50',
    borderColor: 'border-orange-200',
    hintColor: 'text-orange-700',
  },
  {
    key: 'notSubmitted',
    label: '미제출',
    icon: 'pi-file',
    iconColor: 'text-gray-500',
    bgColor: 'bg-gray-50',
    borderColor: 'border-gray-200',
    hintColor: 'text-gray-700',
  },
  {
    key: 'inReview',
    label: '검토 중',
    icon: 'pi-search',
    iconColor: 'text-blue-600',
    bgColor: 'bg-blue-50',
    borderColor: 'border-blue-200',
    hintColor: 'text-blue-700',
  },
  {
    key: 'completed',
    label: '완료',
    icon: 'pi-check-circle',
    iconColor: 'text-green-600',
    bgColor: 'bg-green-50',
    borderColor: 'border-green-200',
    hintColor: 'text-green-700',
  },
]

const countOf = (key: MyTaskSectionKey): number => {
  if (!data.value) return 0
  return data.value.counts[key] ?? 0
}

const totalTasks = computed(() => {
  if (!data.value) return 0
  const c = data.value.counts
  return c.rejected + c.dueSoon + c.notSubmitted + c.inReview
})

function scrollToSection(key: MyTaskSectionKey) {
  const el = document.getElementById(`section-${key}`)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}
</script>

<template>
  <div class="p-6 space-y-6 max-w-6xl mx-auto">
    <!-- 헤더 -->
    <div class="flex items-start justify-between">
      <div>
        <h1 class="text-xl font-bold text-gray-900">내 할 일</h1>
        <p class="text-sm text-gray-500 mt-1">
          내가 담당한 증빙을 상태별로 모아 봅니다.
          <span v-if="data && !loading" class="ml-1 text-gray-400">
            · 처리 필요 {{ totalTasks }}건
          </span>
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

    <!-- 로딩 -->
    <div v-if="loading" class="py-16 text-center text-gray-400 text-sm">
      <i class="pi pi-spin pi-spinner text-2xl mb-2"></i>
      <p>불러오는 중...</p>
    </div>

    <!-- 에러 -->
    <div v-else-if="error"
      class="py-12 text-center bg-red-50 border border-red-200 rounded-xl">
      <i class="pi pi-exclamation-triangle text-red-500 text-2xl mb-2"></i>
      <p class="text-sm text-red-700">{{ error }}</p>
      <button @click="load" class="mt-3 px-3 py-1.5 text-xs bg-white border border-red-200 text-red-700 rounded-lg hover:bg-red-50">
        다시 시도
      </button>
    </div>

    <!-- 데이터 있음 -->
    <template v-else-if="data">
      <!-- 상단 5칸 KPI 카드 -->
      <div class="grid grid-cols-2 md:grid-cols-5 gap-3">
        <button
          v-for="kpi in kpis"
          :key="kpi.key"
          @click="scrollToSection(kpi.key)"
          :class="[
            'text-left p-3 rounded-xl border transition-colors hover:shadow-sm',
            kpi.bgColor, kpi.borderColor,
          ]">
          <div class="flex items-center justify-between">
            <i :class="['pi text-lg', kpi.icon, kpi.iconColor]"></i>
            <span :class="['text-2xl font-bold', kpi.hintColor]">{{ countOf(kpi.key) }}</span>
          </div>
          <div :class="['text-xs mt-2 font-medium', kpi.hintColor]">{{ kpi.label }}</div>
        </button>
      </div>

      <!-- ========================================
           섹션 1: 반려됨
           ======================================== -->
      <section id="section-rejected" v-if="data.rejected.length > 0" class="space-y-2">
        <h2 class="flex items-center gap-2 text-sm font-bold text-gray-900">
          <span class="w-2 h-2 bg-red-500 rounded-full"></span>
          반려됨 · 즉시 재제출 필요
          <span class="text-red-600 text-xs">({{ data.counts.rejected }})</span>
        </h2>
        <div class="space-y-2">
          <button
            v-for="item in data.rejected"
            :key="item.evidenceTypeId"
            @click="goToDetail(item)"
            class="w-full text-left bg-white rounded-xl border border-red-200 hover:shadow-md transition-shadow p-4 block">
            <div class="flex items-start justify-between gap-3">
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2 flex-wrap">
                  <span class="px-1.5 py-0.5 bg-red-100 text-red-700 text-[10px] rounded font-medium">반려됨</span>
                  <span class="text-sm font-semibold text-gray-900">{{ item.evidenceTypeName }}</span>
                </div>
                <div class="text-xs text-gray-500 mt-0.5">
                  {{ item.frameworkName }} · {{ item.controlCode }} {{ item.controlName }}
                </div>
                <!-- 반려 사유 (빨간 박스) -->
                <div class="mt-2 p-2.5 bg-red-50 border border-red-100 rounded-md">
                  <div class="text-[10px] font-medium text-red-600 uppercase mb-1">
                    반려 사유
                    <span v-if="item.rejectedByName" class="normal-case text-red-500 font-normal ml-1">
                      · {{ item.rejectedByName }}
                      <span v-if="item.reviewedAt" class="text-red-400"> · {{ formatDateTime(item.reviewedAt) }}</span>
                    </span>
                  </div>
                  <p class="text-[13px] text-red-900 whitespace-pre-wrap">{{ item.rejectReason || '사유 미기재' }}</p>
                </div>
              </div>
              <div class="shrink-0 inline-flex items-center gap-1 text-xs text-red-600 font-medium">
                재제출하기
                <i class="pi pi-arrow-right text-[10px]"></i>
              </div>
            </div>
          </button>
        </div>
      </section>

      <!-- ========================================
           섹션 2: 마감 임박
           ======================================== -->
      <section id="section-dueSoon" v-if="data.dueSoon.length > 0" class="space-y-2">
        <h2 class="flex items-center gap-2 text-sm font-bold text-gray-900">
          <span class="w-2 h-2 bg-orange-500 rounded-full"></span>
          마감 임박 (7일 이내)
          <span class="text-orange-600 text-xs">({{ data.counts.dueSoon }})</span>
        </h2>
        <div class="space-y-2">
          <button
            v-for="item in data.dueSoon"
            :key="item.evidenceTypeId"
            @click="goToDetail(item)"
            class="w-full text-left bg-white rounded-xl border border-orange-200 hover:shadow-md transition-shadow p-3 flex items-center gap-3">
            <span :class="['px-2 py-1 text-[11px] rounded font-medium shrink-0', dDayClass(item.daysUntilDue)]">
              {{ dDayLabel(item.daysUntilDue) ?? '-' }}
            </span>
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium text-gray-900">{{ item.evidenceTypeName }}</div>
              <div class="text-xs text-gray-500 mt-0.5">
                {{ item.frameworkName }} · {{ item.controlCode }} {{ item.controlName }}
                · 마감 {{ formatDate(item.dueDate) }}
              </div>
            </div>
            <i class="pi pi-arrow-right text-gray-400 text-xs shrink-0"></i>
          </button>
        </div>
      </section>

      <!-- ========================================
           섹션 3: 미제출
           ======================================== -->
      <section id="section-notSubmitted" v-if="data.notSubmitted.length > 0" class="space-y-2">
        <h2 class="flex items-center gap-2 text-sm font-bold text-gray-900">
          <span class="w-2 h-2 bg-gray-400 rounded-full"></span>
          미제출
          <span class="text-gray-600 text-xs">({{ data.counts.notSubmitted }})</span>
        </h2>
        <div class="space-y-2">
          <button
            v-for="item in data.notSubmitted"
            :key="item.evidenceTypeId"
            @click="goToDetail(item)"
            class="w-full text-left bg-white rounded-xl border border-gray-200 hover:shadow-md transition-shadow p-3 flex items-center gap-3">
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium text-gray-900">{{ item.evidenceTypeName }}</div>
              <div class="text-xs text-gray-500 mt-0.5">
                {{ item.frameworkName }} · {{ item.controlCode }} {{ item.controlName }}
                <template v-if="item.dueDate">
                  · 마감 {{ formatDate(item.dueDate) }}
                </template>
              </div>
            </div>
            <i class="pi pi-arrow-right text-gray-400 text-xs shrink-0"></i>
          </button>
        </div>
      </section>

      <!-- ========================================
           섹션 4: 검토 중
           ======================================== -->
      <section id="section-inReview" v-if="data.inReview.length > 0" class="space-y-2">
        <h2 class="flex items-center gap-2 text-sm font-bold text-gray-900">
          <span class="w-2 h-2 bg-blue-500 rounded-full"></span>
          검토 중 · 관리자 승인 대기
          <span class="text-blue-600 text-xs">({{ data.counts.inReview }})</span>
        </h2>
        <div class="space-y-2">
          <button
            v-for="item in data.inReview"
            :key="item.evidenceTypeId"
            @click="goToDetail(item)"
            class="w-full text-left bg-white rounded-xl border border-blue-200 hover:shadow-md transition-shadow p-3 flex items-center gap-3">
            <span class="px-1.5 py-0.5 bg-blue-100 text-blue-700 text-[10px] rounded font-medium shrink-0">
              ● 검토 대기
            </span>
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium text-gray-900">
                {{ item.evidenceTypeName }}
                <span v-if="item.latestVersion" class="text-xs text-gray-400 font-mono">v{{ item.latestVersion }}</span>
              </div>
              <div class="text-xs text-gray-500 mt-0.5">
                {{ item.frameworkName }} · {{ item.controlCode }} {{ item.controlName }}
                · {{ formatDateTime(item.submittedAt) }} 제출
              </div>
            </div>
            <i class="pi pi-arrow-right text-gray-400 text-xs shrink-0"></i>
          </button>
        </div>
      </section>

      <!-- ========================================
           섹션 5: 완료 (접힘 기본, 펼치기 가능)
           ======================================== -->
      <section id="section-completed" v-if="data.completed.length > 0" class="space-y-2">
        <button
          @click="completedExpanded = !completedExpanded"
          class="w-full flex items-center justify-between text-sm font-bold text-gray-900 hover:text-gray-700">
          <span class="flex items-center gap-2">
            <span class="w-2 h-2 bg-green-500 rounded-full"></span>
            완료
            <span class="text-green-600 text-xs">({{ data.counts.completed }})</span>
            <span v-if="data.counts.completed > data.completed.length"
              class="text-[10px] text-gray-400 font-normal ml-1">
              최근 {{ data.completed.length }}건 표시
            </span>
          </span>
          <i :class="['pi text-xs transition-transform',
            completedExpanded ? 'pi-chevron-up' : 'pi-chevron-down']"></i>
        </button>
        <div v-if="completedExpanded" class="space-y-2">
          <div
            v-for="item in data.completed"
            :key="item.evidenceTypeId"
            class="bg-white rounded-xl border border-green-100 p-3 flex items-center gap-3">
            <span class="px-1.5 py-0.5 bg-green-100 text-green-700 text-[10px] rounded font-medium shrink-0">
              승인 완료
            </span>
            <div class="flex-1 min-w-0">
              <div class="text-sm text-gray-700">
                {{ item.evidenceTypeName }}
                <span v-if="item.latestVersion" class="text-xs text-gray-400 font-mono">v{{ item.latestVersion }}</span>
              </div>
              <div class="text-xs text-gray-500 mt-0.5">
                {{ item.frameworkName }} · {{ item.controlCode }}
                <template v-if="item.approvedByName">· {{ item.approvedByName }} 승인</template>
                <template v-if="item.reviewedAt">· {{ formatDateTime(item.reviewedAt) }}</template>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- ========================================
           완전 빈 상태
           ======================================== -->
      <div v-if="totalTasks === 0 && data.completed.length === 0"
        class="py-20 text-center bg-white rounded-xl border border-gray-200">
        <i class="pi pi-inbox text-gray-300 text-4xl mb-3"></i>
        <p class="text-sm text-gray-500">담당 증빙이 아직 없습니다.</p>
        <p class="text-xs text-gray-400 mt-1">관리자가 증빙을 배정하면 여기에 표시됩니다.</p>
      </div>

      <!-- 처리할 일 0 + 완료만 있는 경우 -->
      <div v-else-if="totalTasks === 0 && data.completed.length > 0 && !completedExpanded"
        class="py-12 text-center bg-green-50 border border-green-100 rounded-xl">
        <i class="pi pi-check-circle text-green-500 text-3xl mb-2"></i>
        <p class="text-sm text-green-700 font-medium">처리할 일이 없어요 🎉</p>
        <p class="text-xs text-green-600 mt-1">담당한 증빙이 모두 완료되었습니다.</p>
      </div>
    </template>
  </div>
</template>