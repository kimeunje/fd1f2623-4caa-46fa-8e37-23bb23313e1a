<script setup lang="ts">
/**
 * 상단 고정 헤더 — 루트 기반 계층 브레드크럼 (v13 Phase 5-13).
 *
 * 프로토타입 secuhub_unified_prototype.html §top-chrome 정합.
 * 루트(SecuHub)부터 현재 페이지까지 전체 경로를 표시하며, 각 단계는
 * 해당 레벨로 이동 가능한 링크 또는 드롭다운이 된다.
 *
 * 계층 시각 체계:
 *   - 상위 컨텍스트 (SecuHub, 증빙 수집, 시스템 …)  : text-xs text-gray-400
 *   - 중간 링크 (통제 항목, 통제 코드+이름 …)       : text-sm text-gray-600 hover:text-gray-900
 *   - 드롭다운 (Framework 전환)                    : text-sm + chevron
 *   - 현재 페이지                                  : text-sm text-gray-900 font-medium
 *
 * 라우트별 계층:
 *   - dashboard              : SecuHub · 대시보드
 *   - framework-list         : SecuHub · 증빙 수집 · 통제 항목
 *   - framework-create-wizard: SecuHub · 증빙 수집 · 통제 항목 · 새 Framework
 *   - framework-detail       : SecuHub · 증빙 수집 · 통제 항목 · [FW ▾]
 *   - evidence-type-detail   : SecuHub · 증빙 수집 · 통제 항목 · [FW ▾] · 통제 · 증빙 유형
 *   - jobs                   : SecuHub · 증빙 수집 · 수집 작업
 *   - files                  : SecuHub · 증빙 수집 · 증빙 파일
 *   - accounts               : SecuHub · 시스템 · 계정 관리
 *   - my-tasks               : SecuHub · 내 할 일
 *   - my-task-detail         : SecuHub · 내 할 일 · {증빙 유형명}
 *   - 그 외                  : SecuHub · {route.meta.title}
 *
 * 데이터 로드:
 *   - frameworkId 변경       → frameworksApi.get(id)  (현재 FW 이름)
 *   - framework-detail /
 *     evidence-type-detail   → frameworksApi.list()  (Switcher 목록, 1회 캐시)
 *   - evidence-type-detail   → controlsApi.getDetail(ctrlId)  (통제 코드·이름 + ET 이름)
 */

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { controlsApi, frameworksApi } from '@/services/evidenceApi'
import type { Framework } from '@/types/evidence'

// ControlDetail 응답 shape — 전역 타입 의존성을 피하기 위해 필요한 최소 필드만 로컬로 선언
interface ControlDetailMini {
  id: number
  code: string
  name: string
  evidenceTypes?: Array<{ id: number; name: string }>
}

interface Crumb {
  key: string
  text: string
  onClick?: () => void
  muted?: boolean
  current?: boolean
  kind?: 'text' | 'framework-switcher'
}

const route = useRoute()
const router = useRouter()

// ============================================================
// Route 파라미터 (숫자 변환)
// ============================================================

const frameworkId = computed<number | null>(() => toNum(route.params.frameworkId))
const controlId = computed<number | null>(() => toNum(route.params.controlId))
const evidenceTypeId = computed<number | null>(() => toNum(route.params.evidenceTypeId))

function toNum(raw: unknown): number | null {
  if (raw == null || Array.isArray(raw)) return null
  const n = Number(raw)
  return Number.isFinite(n) ? n : null
}

// ============================================================
// 상태
// ============================================================

const currentFramework = ref<Framework | null>(null)
const frameworkList = ref<Framework[]>([])
const currentControl = ref<ControlDetailMini | null>(null)
const currentEvidenceTypeName = ref<string>('')
const switcherOpen = ref<boolean>(false)

// ============================================================
// 데이터 로드
// ============================================================

// 1) 현재 Framework 단일 조회 — frameworkId 변경 시
watch(
  frameworkId,
  async (id) => {
    if (!id) {
      currentFramework.value = null
      return
    }
    try {
      const { data } = await frameworksApi.get(id)
      if (data.success) currentFramework.value = data.data
      else currentFramework.value = null
    } catch (e) {
      console.error('Framework 조회 실패:', e)
      currentFramework.value = null
    }
  },
  { immediate: true },
)

// 2) Framework 목록 조회 — Switcher 사용 라우트 진입 시 1회 캐시
watch(
  () => route.name,
  async (name) => {
    const needsList = name === 'framework-detail' || name === 'evidence-type-detail'
    if (!needsList || frameworkList.value.length > 0) return
    try {
      const { data } = await frameworksApi.list()
      if (data.success) frameworkList.value = data.data
    } catch (e) {
      console.error('Framework 목록 조회 실패:', e)
    }
  },
  { immediate: true },
)

// 3) 통제 상세 + ET 이름 조회 — evidence-type-detail 한정
watch(
  [controlId, evidenceTypeId, () => route.name],
  async ([cId, etId, name]) => {
    if (name !== 'evidence-type-detail' || !cId || !etId) {
      currentControl.value = null
      currentEvidenceTypeName.value = ''
      return
    }
    // 통제 상세: 같은 controlId 로 이미 로드되어 있으면 재사용
    if (!currentControl.value || currentControl.value.id !== cId) {
      try {
        const { data } = await controlsApi.getDetail(cId)
        if (data.success) currentControl.value = data.data
        else currentControl.value = null
      } catch (e) {
        console.error('통제 상세 조회 실패:', e)
        currentControl.value = null
      }
    }
    // ET 이름은 통제 상세의 evidenceTypes 에서 찾기
    const et = currentControl.value?.evidenceTypes?.find((e) => e.id === etId)
    currentEvidenceTypeName.value = et?.name ?? ''
  },
  { immediate: true },
)

// ============================================================
// 브레드크럼 계산
// ============================================================

const crumbs = computed<Crumb[]>(() => {
  const name = route.name as string
  const out: Crumb[] = []

  // 루트는 항상 SecuHub (muted)
  out.push({ key: 'root', text: 'SecuHub', muted: true })

  switch (name) {
    case 'dashboard':
      out.push({ key: 'current', text: '대시보드', current: true })
      break

    case 'framework-list':
      out.push({ key: 'section', text: '증빙 수집', muted: true })
      out.push({ key: 'current', text: '통제 항목', current: true })
      break

    case 'framework-create-wizard':
      out.push({ key: 'section', text: '증빙 수집', muted: true })
      out.push({
        key: 'controls',
        text: '통제 항목',
        onClick: () => router.push({ name: 'framework-list' }),
      })
      out.push({ key: 'current', text: '새 Framework', current: true })
      break

    case 'framework-detail':
      out.push({ key: 'section', text: '증빙 수집', muted: true })
      out.push({
        key: 'controls',
        text: '통제 항목',
        onClick: () => router.push({ name: 'framework-list' }),
      })
      out.push({
        key: 'framework',
        text: currentFramework.value?.name ?? '…',
        kind: 'framework-switcher',
        current: true,
      })
      break

    case 'evidence-type-detail':
      out.push({ key: 'section', text: '증빙 수집', muted: true })
      out.push({
        key: 'controls',
        text: '통제 항목',
        onClick: () => router.push({ name: 'framework-list' }),
      })
      out.push({
        key: 'framework',
        text: currentFramework.value?.name ?? '…',
        kind: 'framework-switcher',
      })
      if (currentControl.value) {
        out.push({
          key: 'control',
          text: `${currentControl.value.code} ${currentControl.value.name}`,
          onClick: () =>
            router.push({
              name: 'framework-detail',
              params: { frameworkId: frameworkId.value as number },
            }),
        })
      }
      out.push({
        key: 'current',
        text: currentEvidenceTypeName.value || '…',
        current: true,
      })
      break

    case 'jobs':
      out.push({ key: 'section', text: '증빙 수집', muted: true })
      out.push({ key: 'current', text: '수집 작업', current: true })
      break

    case 'files':
      out.push({ key: 'section', text: '증빙 수집', muted: true })
      out.push({ key: 'current', text: '증빙 파일', current: true })
      break

    case 'accounts':
      out.push({ key: 'section', text: '시스템', muted: true })
      out.push({ key: 'current', text: '계정 관리', current: true })
      break

    case 'my-tasks':
      out.push({ key: 'current', text: '내 할 일', current: true })
      break

    case 'my-task-detail':
      out.push({
        key: 'my-tasks',
        text: '내 할 일',
        onClick: () => router.push({ name: 'my-tasks' }),
      })
      out.push({
        key: 'current',
        text: currentEvidenceTypeName.value || (route.meta.title as string) || '상세',
        current: true,
      })
      break

    default:
      // fallback: route.meta.title 을 현재 페이지로 표시
      if (route.meta?.title) {
        out.push({ key: 'current', text: route.meta.title as string, current: true })
      }
  }

  return out
})

// ============================================================
// Framework Switcher
// ============================================================

function onFrameworkSelect(id: number) {
  switcherOpen.value = false
  if (id === frameworkId.value) return
  router.push({ name: 'framework-detail', params: { frameworkId: id } })
}

function onCreateFramework() {
  switcherOpen.value = false
  router.push({ name: 'framework-create-wizard' })
}

function onGoFrameworkList() {
  switcherOpen.value = false
  router.push({ name: 'framework-list' })
}

// Switcher 바깥 클릭 시 닫기
function onDocumentClick(e: MouseEvent) {
  if (!switcherOpen.value) return
  const target = e.target as HTMLElement | null
  if (!target) return
  if (!target.closest('[data-switcher-root]')) {
    switcherOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
})
</script>

<template>
  <header
    class="bg-white border-b border-gray-200 h-14 flex items-center justify-between px-6 sticky top-0 z-20"
  >
    <!-- Breadcrumb -->
    <nav class="flex items-center gap-2 min-w-0" aria-label="Breadcrumb">
      <template v-for="(c, i) in crumbs" :key="c.key">
        <!-- 구분자 -->
        <span v-if="i > 0" class="text-gray-300 text-xs shrink-0 select-none">/</span>

        <!-- Framework switcher (드롭다운) -->
        <div
          v-if="c.kind === 'framework-switcher'"
          class="relative shrink-0"
          data-switcher-root
        >
          <button
            type="button"
            @click="switcherOpen = !switcherOpen"
            :class="[
              'inline-flex items-center gap-1 px-2 py-1 -mx-2 rounded-md transition-colors hover:bg-gray-50',
              c.current
                ? 'text-sm text-gray-900 font-medium'
                : 'text-sm text-gray-600 hover:text-gray-900',
            ]"
          >
            <span>{{ c.text }}</span>
            <i
              class="pi pi-chevron-down text-[10px] text-gray-400 transition-transform"
              :class="switcherOpen ? 'rotate-180' : ''"
            ></i>
          </button>

          <div
            v-if="switcherOpen"
            class="absolute top-full left-0 mt-1 w-72 bg-white border border-gray-200 rounded-lg shadow-lg z-40 py-1"
          >
            <div
              class="px-3 py-1.5 text-[10px] text-gray-400 font-semibold uppercase tracking-wider border-b border-gray-100"
            >
              Framework 전환
            </div>

            <button
              v-for="fw in frameworkList"
              :key="fw.id"
              type="button"
              @click="onFrameworkSelect(fw.id)"
              class="w-full px-3 py-2 text-left text-sm hover:bg-gray-50 flex items-start gap-2"
            >
              <i
                class="pi text-xs mt-0.5 shrink-0"
                :class="
                  fw.id === frameworkId ? 'pi-check text-gray-900' : 'text-transparent'
                "
              ></i>
              <div class="flex-1 min-w-0">
                <div class="font-medium text-gray-900 truncate">{{ fw.name }}</div>
                <div class="text-[11px] text-gray-400">
                  {{ fw.status === 'active' ? '진행중' : '아카이브' }}
                </div>
              </div>
            </button>

            <div class="border-t border-gray-100 mt-1 pt-1">
              <button
                type="button"
                @click="onCreateFramework"
                class="w-full px-3 py-2 text-left text-sm text-gray-600 hover:bg-gray-50 flex items-center gap-2"
              >
                <i class="pi pi-plus text-xs"></i>
                새 Framework 만들기
              </button>
              <button
                type="button"
                @click="onGoFrameworkList"
                class="w-full px-3 py-2 text-left text-sm text-gray-600 hover:bg-gray-50 flex items-center gap-2"
              >
                <i class="pi pi-list text-xs"></i>
                전체 Framework 목록
              </button>
            </div>
          </div>
        </div>

        <!-- 클릭 가능 링크 -->
        <button
          v-else-if="c.onClick"
          type="button"
          @click="c.onClick"
          :class="[
            'shrink-0 transition-colors',
            c.muted
              ? 'text-xs text-gray-400 hover:text-gray-600'
              : 'text-sm text-gray-600 hover:text-gray-900',
          ]"
        >
          {{ c.text }}
        </button>

        <!-- 정적 텍스트 (muted 컨텍스트 또는 현재 페이지) -->
        <span
          v-else
          :class="[
            'min-w-0 truncate',
            c.muted
              ? 'text-xs text-gray-400'
              : c.current
                ? 'text-sm text-gray-900 font-medium'
                : 'text-sm text-gray-600',
          ]"
        >
          {{ c.text }}
        </span>
      </template>
    </nav>

    <!-- 우측 액션 -->
    <div class="flex items-center gap-3 shrink-0">
      <button
        type="button"
        class="relative w-8 h-8 flex items-center justify-center rounded-md hover:bg-gray-100 text-gray-500"
        title="알림"
      >
        <i class="pi pi-bell text-base"></i>
        <span class="absolute top-1.5 right-1.5 w-2 h-2 bg-red-500 rounded-full"></span>
      </button>
    </div>
  </header>
</template>