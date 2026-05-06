<script setup lang="ts">
/**
 * 승인 대기 목록 위젯 — top 10 (v16.4b 신규).
 *
 * spec §3.8.1 + §3.8.2 정합. 표시:
 *   각 항목 = 제출자명 / 팀 / 시각 / Framework + controlPath / 바로가기
 *
 * Props:
 *   items: DashboardPendingApproval[] — BE 가 이미 top 10 + submittedAt DESC 로 정렬
 *
 * 클릭 동작:
 *   행 전체 클릭 → router.push(deepLinkUrl) — BE 가 직접 조립한 URL 그대로 사용.
 *
 * 빈 상태:
 *   "승인 필요한 증빙이 없습니다 ✨" (Q3=B, KpiCard 와 동일 표현으로 일관)
 *
 * 디자인 패턴: ControlsView 의 leaf 펼침 카드 + FrameworkListView 의 행 hover 정합.
 */
import { useRouter } from 'vue-router'
import type { DashboardPendingApproval } from '@/types/evidence'

defineProps<{
  items: DashboardPendingApproval[]
}>()

const router = useRouter()

function onItemClick(item: DashboardPendingApproval) {
  // BE 가 조립한 deepLinkUrl 그대로 router.push.
  // 형식: /controls/{frameworkId}/{controlNodeId}/evidence-types/{evidenceTypeId}
  router.push(item.deepLinkUrl).catch(() => { /* NavigationDuplicated 무시 */ })
}

/** ISO 8601 → 'M월 D일 HH:mm' (오늘 기준 상대 표시는 더 큰 phase 에서). */
function formatTime(iso: string | null): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return '-'
  const m = d.getMonth() + 1
  const day = d.getDate()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${m}월 ${day}일 ${hh}:${mm}`
}

/** 업로더 표시: "이름 (팀)" / 팀 null 시 "이름" / 이름 null 시 "(시스템)". */
function formatUploader(item: DashboardPendingApproval): string {
  if (!item.uploaderName) return '(시스템)'
  if (!item.uploaderTeam) return item.uploaderName
  return `${item.uploaderName} (${item.uploaderTeam})`
}
</script>

<template>
  <div class="bg-white rounded-xl border border-gray-200">
    <!-- 헤더 -->
    <div class="p-4 border-b border-gray-200 flex items-center gap-2">
      <i class="pi pi-inbox text-blue-500"></i>
      <h3 class="font-bold text-gray-900">승인 대기 목록</h3>
      <span v-if="items.length > 0" class="ml-auto text-xs text-gray-500">
        최근 {{ items.length }}건
      </span>
    </div>

    <!-- 빈 상태 -->
    <div v-if="items.length === 0" class="p-8 text-center text-sm text-gray-400">
      <i class="pi pi-check-circle text-2xl text-gray-300 mb-2"></i>
      <p>승인 필요한 증빙이 없습니다 ✨</p>
    </div>

    <!-- 목록 -->
    <div v-else class="divide-y divide-gray-100">
      <button
        v-for="item in items"
        :key="item.fileId"
        type="button"
        @click="onItemClick(item)"
        class="w-full p-3 hover:bg-gray-50 cursor-pointer text-left transition-colors block">

        <!-- 1행: framework + 시간 -->
        <div class="flex items-center justify-between mb-1">
          <span class="text-xs font-medium text-blue-700">
            {{ item.frameworkName }}
          </span>
          <span class="text-xs text-gray-500">
            {{ formatTime(item.submittedAt) }}
          </span>
        </div>

        <!-- 2행: 증빙 유형명 (강조) -->
        <p class="text-sm font-medium text-gray-900 mb-1 line-clamp-1">
          {{ item.evidenceTypeName }}
        </p>

        <!-- 3행: controlPath + 업로더 -->
        <div class="flex items-center justify-between text-xs text-gray-500">
          <span class="font-mono truncate">{{ item.controlPath }}</span>
          <span class="ml-2 flex-shrink-0">{{ formatUploader(item) }}</span>
        </div>
      </button>
    </div>
  </div>
</template>

<style scoped>
.line-clamp-1 {
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>