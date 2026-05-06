<script setup lang="ts">
/**
 * Framework 진척 카드 (단일 framework, v16.4b 신규).
 *
 * spec §3.8.1 + §3.8.2 정합. 표시:
 *   - framework 이름 (헤더)
 *   - 진척률 바 (collected/total)
 *   - 검토 대기 건수
 *   - 카운트 텍스트 ("N/M 수집")
 *
 * Props:
 *   item: DashboardFrameworkProgress
 *
 * 클릭 동작:
 *   카드 전체 클릭 → router.push(/controls/{frameworkId}) — Framework 상세 페이지로.
 *
 * 디자인 패턴: FrameworkListView 의 다크 카드 + ControlsView 의 진행바 정합.
 * 색상: progressRatio >= 1 → 녹색 / 0 < ratio < 1 → 파란 / 0 → 회색.
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import type { DashboardFrameworkProgress } from '@/types/evidence'

const props = defineProps<{
  item: DashboardFrameworkProgress
}>()

const router = useRouter()

function onClick() {
  router.push({
    name: 'framework-detail',
    params: { frameworkId: props.item.frameworkId },
  }).catch(() => { /* NavigationDuplicated 무시 */ })
}

/** 진척률 % 표시 (정수 반올림). total=0 시 0% 표기. */
const progressPercent = computed<number>(() => {
  return Math.round(props.item.progressRatio * 100)
})

/** 진행바 색상. */
const barColor = computed<string>(() => {
  const r = props.item.progressRatio
  if (r >= 1) return 'bg-green-500'
  if (r > 0) return 'bg-blue-500'
  return 'bg-gray-300'
})

/** 텍스트 색상 (헤더 배지). */
const statusBadge = computed<{ text: string; cls: string }>(() => {
  const r = props.item.progressRatio
  if (r >= 1) return { text: '완료', cls: 'bg-green-100 text-green-700' }
  if (r > 0) return { text: '진행중', cls: 'bg-blue-100 text-blue-700' }
  return { text: '미수집', cls: 'bg-gray-100 text-gray-600' }
})
</script>

<template>
  <button
    type="button"
    @click="onClick"
    class="w-full text-left bg-white rounded-xl border border-gray-200 p-4 hover:shadow-sm hover:border-gray-300 transition-all">

    <!-- 헤더: framework 이름 + 상태 배지 -->
    <div class="flex items-center justify-between mb-3">
      <h4 class="text-sm font-bold text-gray-900 truncate">{{ item.frameworkName }}</h4>
      <span
        class="ml-2 inline-block px-2 py-0.5 text-[10px] font-medium rounded flex-shrink-0"
        :class="statusBadge.cls">
        {{ statusBadge.text }}
      </span>
    </div>

    <!-- 진행바 -->
    <div class="h-2 bg-gray-100 rounded-full overflow-hidden mb-2">
      <div
        class="h-full rounded-full transition-all"
        :class="barColor"
        :style="{ width: `${progressPercent}%` }">
      </div>
    </div>

    <!-- 카운트 텍스트 -->
    <div class="flex items-center justify-between text-xs text-gray-600">
      <span>
        <span class="font-bold text-gray-900">{{ item.collectedCount }}</span>
        <span class="text-gray-400"> / </span>
        <span>{{ item.totalEvidenceTypes }}</span>
        <span class="ml-1">수집</span>
      </span>
      <span v-if="item.pendingReviewCount > 0" class="text-blue-600 font-medium">
        검토 대기 {{ item.pendingReviewCount }}건
      </span>
      <span v-else class="text-gray-400">검토 대기 0</span>
    </div>
  </button>
</template>