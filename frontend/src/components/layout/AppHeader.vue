<script setup lang="ts">
/**
 * 상단 고정 헤더 (v11 Phase 5-10 재작성).
 *
 * prototype v4 기준으로 **breadcrumb** 역할을 한다. 단순 route.meta.title
 * 표시가 아니라 "통제 항목 / ISMS-P 2026" 처럼 계층을 표현하며,
 * 각 단계는 클릭 가능한 네비게이션이 된다.
 *
 * 동작 규칙:
 *  - 일반 라우트: route.meta.title 한 단계만 표시 (클릭 불가)
 *  - framework-detail 라우트: "통제 항목" + "/" + Framework 이름 두 단계
 *    - "통제 항목" 클릭 → framework-list 로 이동 (prototype v4 goTo('fw-list') 대응)
 *    - Framework 이름은 현재 페이지이므로 비활성
 *  - 우측: 알림 벨 (prototype v4 와 동일. v11 기획서 §4.4 "인앱 알림 벨 없음"
 *    원칙과 충돌하므로 추후 기획서 정합성 결정 필요)
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { frameworksApi } from '@/services/evidenceApi'
import type { Framework } from '@/types/evidence'

const route = useRoute()
const router = useRouter()

// 현재 Framework 이름 (framework-detail 라우트일 때만 조회)
const currentFramework = ref<Framework | null>(null)

const frameworkId = computed<number | null>(() => {
  const raw = route.params.frameworkId
  if (raw == null) return null
  const n = Number(raw)
  return Number.isFinite(n) ? n : null
})

const isFrameworkDetail = computed(() => route.name === 'framework-detail')

/**
 * root breadcrumb 텍스트.
 * framework-list / framework-detail 는 "통제 항목" 으로 고정하여
 * prototype v4 의 crumb-root 와 동일하게 동작한다.
 */
const rootCrumbText = computed<string>(() => {
  if (route.name === 'framework-list' || route.name === 'framework-detail') {
    return '통제 항목'
  }
  return (route.meta.title as string) || ''
})

/**
 * framework-detail 에서는 root crumb 이 "통제 항목 목록" 으로 이동하는
 * 클릭 가능한 버튼이 된다. 그 외 라우트에서는 정적 텍스트처럼 동작.
 */
function onRootClick() {
  if (route.name === 'framework-detail') {
    router.push({ name: 'framework-list' })
  }
}

// Framework 이름 조회 — framework-detail 진입 또는 frameworkId 변경 시
watch(
  frameworkId,
  async (id) => {
    if (!id) {
      currentFramework.value = null
      return
    }
    try {
      const { data } = await frameworksApi.get(id)
      if (data.success) {
        currentFramework.value = data.data
      } else {
        currentFramework.value = null
      }
    } catch (e) {
      console.error('Framework 조회 실패:', e)
      currentFramework.value = null
    }
  },
  { immediate: true },
)
</script>

<template>
  <header
    class="bg-white border-b border-gray-200 h-14 flex items-center justify-between px-6 sticky top-0 z-20">
    <!-- Breadcrumb / page title -->
    <div class="flex items-center gap-2 text-sm">
      <button
        v-if="rootCrumbText"
        type="button"
        @click="onRootClick"
        :disabled="!isFrameworkDetail"
        :class="[
          'font-semibold text-gray-900 transition-colors',
          isFrameworkDetail
            ? 'hover:text-blue-600 cursor-pointer'
            : 'cursor-default',
        ]">
        {{ rootCrumbText }}
      </button>

      <template v-if="isFrameworkDetail && currentFramework">
        <span class="text-gray-300">/</span>
        <span class="text-gray-600">{{ currentFramework.name }}</span>
      </template>
    </div>

    <!-- 우측 액션 -->
    <div class="flex items-center gap-3">
      <button
        type="button"
        class="relative w-8 h-8 flex items-center justify-center rounded-md hover:bg-gray-100 text-gray-500"
        title="알림">
        <i class="pi pi-bell text-base"></i>
        <span class="absolute top-1.5 right-1.5 w-2 h-2 bg-red-500 rounded-full"></span>
      </button>
    </div>
  </header>
</template>