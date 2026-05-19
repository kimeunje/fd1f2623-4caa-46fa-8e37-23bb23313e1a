<!--
  v18.7 — 조치 가이드 strip (mockup 화면 3 정합)

  3 단계 chevron 흐름. 비기술 사용자도 따라할 수 있게 구성.
-->
<script setup lang="ts">
import { computed } from 'vue'

export interface ActionGuideStep {
  title: string
  description: string
}

const props = defineProps<{
  steps?: ActionGuideStep[]
}>()

const DEFAULT_STEPS: ActionGuideStep[] = [
  { title: '스크린샷 확인', description: '실패 시점의 페이지가 예상과 다른가? 로그인 후 페이지로 갔는가?' },
  { title: 'selector 변경 확인', description: '사이트를 직접 열어 개발자 도구로 selector 가 바뀌었는지 확인' },
  { title: '수정 + 재업로드', description: '관리자가 Python 스크립트의 selector 수정 → 재업로드' },
]

const displaySteps = computed<ActionGuideStep[]>(() => props.steps ?? DEFAULT_STEPS)
</script>

<template>
  <div class="bg-white border border-stone-300 rounded-lg px-4 py-3.5">
    <div class="flex items-stretch gap-2 flex-wrap">
      <template v-for="(step, idx) in displaySteps" :key="idx">
        <div class="flex-1 min-w-[180px] bg-stone-100 p-2.5 rounded">
          <div class="flex items-center gap-1.5 mb-1">
            <div class="w-[22px] h-[22px] rounded-full bg-white flex items-center justify-center text-[11px] font-medium">
              {{ idx + 1 }}
            </div>
            <span class="text-[11px] font-medium">{{ step.title }}</span>
          </div>
          <div class="text-[10px] text-stone-600 leading-snug">{{ step.description }}</div>
        </div>
        <div v-if="idx < displaySteps.length - 1" class="flex items-center">
          <i class="pi pi-angle-right text-base text-stone-400"></i>
        </div>
      </template>
    </div>
  </div>
</template>