<script setup lang="ts">
/**
 * 대시보드 KPI 카드 — "내 승인 대기 N건" (v16.4b 신규).
 *
 * spec §3.8.1 + §3.8.2 정합. spec 표현:
 *   "파란 테두리 강조 — 클릭 시 같은 페이지의 '승인 대기 목록' 섹션으로 스크롤"
 *
 * Props:
 *   count: number — KPI 숫자
 *
 * Emits:
 *   click — 부모가 anchor scroll 처리 (DOM 스크롤은 부모 view 의 책임)
 *
 * 빈 상태 (count=0):
 *   "승인 필요한 증빙이 없습니다 ✨" (Q3=B)
 *
 * 디자인 패턴: FrameworkListView 의 다크 카드 + ControlsView 의 토스트 색조 정합.
 * 파란 테두리 (border-blue-500) + 배경 강조 (bg-blue-50) — spec 표현 직역.
 */
defineProps<{
  count: number
}>()

const emit = defineEmits<{
  (e: 'click'): void
}>()

function onClick() {
  emit('click')
}
</script>

<template>
  <button
    type="button"
    @click="onClick"
    :disabled="count === 0"
    class="w-full text-left rounded-xl border-2 transition-all"
    :class="count > 0
      ? 'border-blue-500 bg-blue-50 hover:bg-blue-100 cursor-pointer hover:shadow-sm'
      : 'border-gray-200 bg-gray-50 cursor-default'">

    <div class="p-5">
      <!-- 라벨 -->
      <div class="flex items-center gap-2 mb-2">
        <i class="pi pi-clock text-blue-600 text-sm"></i>
        <span class="text-xs font-medium text-blue-700 uppercase tracking-wide">내 승인 대기</span>
      </div>

      <!-- 카운트 -->
      <div v-if="count > 0" class="flex items-baseline gap-2">
        <span class="text-4xl font-bold text-blue-700">{{ count }}</span>
        <span class="text-sm text-blue-600">건</span>
      </div>

      <!-- 빈 상태 -->
      <div v-else class="text-sm text-gray-600 py-2">
        승인 필요한 증빙이 없습니다 ✨
      </div>

      <!-- 안내 문구 (count > 0 일 때만) -->
      <div v-if="count > 0" class="mt-3 flex items-center gap-1.5 text-xs text-blue-600">
        <span>아래 목록에서 처리</span>
        <i class="pi pi-arrow-down text-[10px]"></i>
      </div>
    </div>
  </button>
</template>