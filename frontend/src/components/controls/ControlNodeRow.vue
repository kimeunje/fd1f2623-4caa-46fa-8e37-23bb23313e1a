<script setup lang="ts">
/**
 * Phase 5-14g + 5-14h — ControlNodeRow.vue (재귀 트리 행)
 *
 * 두 모드 지원:
 *   - mode='view'   : 5-14g 동작 보존 — props 통과, leaf 펼침 시 evidence 카드 표시,
 *                     5-14g emits (toggle-expand / go-evidence-type /
 *                     zip-download / delete-evidence-type) 모두 그대로
 *   - mode='dialog' : 5-14h 신규 — 인라인 input (코드/이름) + 카테고리/leaf hover 액션 +
 *                     키보드 단축키. tree composable 은 inject 로 받음
 *
 * provide/inject (dialog 모드 only): UnifiedControlsDialog 가
 * provide(CONTROL_TREE_INJECTION_KEY, tree) → 본 컴포넌트가 inject. view 모드는
 * tree 의존성 없음 (5-14g 인터페이스 보존).
 */

import { computed, inject, ref, nextTick, watch, onBeforeUnmount } from 'vue'
import {
  CONTROL_TREE_INJECTION_KEY,
  type TreeRootNode,
  type ControlTreeApi,
  type UnifiedNode,
} from '@/composables/useControlTree'
import type { ControlDetail, EvidenceTypeResponse } from '@/types/evidence'

defineOptions({ name: 'ControlNodeRow' })

// ============================================================================
// props
// ============================================================================

/**
 * 본 컴포넌트는 view (TreeRootNode) / dialog (UnifiedNode) 두 형태 모두 받는다.
 * 두 타입은 공통 필드를 충분히 공유 (id/parentId/code/name/depth/...) 하므로
 * union 으로 단일 prop 처리.
 */
type RowNode = TreeRootNode | UnifiedNode

interface Props {
  node: RowNode
  mode: 'view' | 'dialog'

  // ─── view 모드 props (5-14g 보존) ───
  expandedIds?: Set<number>
  expandedLeafId?: number | null
  controlDetail?: ControlDetail | null
  detailLoading?: boolean
  dimmed?: boolean
  isRoot?: boolean

  // ─── view 모드 props (Phase 5-14i 추가, P12/P14) ───
  /** P14 (5-14i) — 텍스트 → match-highlight 감싼 HTML 변환. 미전달 시 plain text. */
  highlightFn?: (text: string) => string
  /** P12 (5-14i) — categoryId → 자손 매치 카운트. 검색 활성 + count > 0 일 때 amber 핍 노출. */
  matchCountByCategoryId?: Map<number, number>
  /** 검색 또는 필터 활성 여부 — 매치 카운트 핍 노출 가드. */
  searchActive?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  expandedIds: () => new Set<number>(),
  expandedLeafId: null,
  controlDetail: null,
  detailLoading: false,
  dimmed: false,
  isRoot: false,
  highlightFn: undefined,
  matchCountByCategoryId: () => new Map<number, number>(),
  searchActive: false,
})

const emit = defineEmits<{
  // ─── view 모드 emits (5-14g 보존, v15.6 명명 정리) ───
  // v15.6: param 명 controlId → nodeId 정리. emit 호출은 positional argument 라
  // template 측 / 호출자 (ControlsView 의 onZipDownload / goToEvidenceTypeDetail) 의
  // 변경은 시그니처 param 명만 (실 동작 동일).
  'toggle-expand': [nodeId: number, nodeType: 'category' | 'control']
  'go-evidence-type': [evidenceTypeId: number, nodeId: number]
  'zip-download': [nodeId: number, controlCode: string]
  'delete-evidence-type': [evidenceTypeId: number, evidenceTypeName: string]
  'create-evidence-type': [nodeId: number, name: string]

  // ─── dialog 모드 emits (5-14h 신규) ───
  'leaf-code-blur': [
    payload: { node: UnifiedNode; oldCode: string; newCode: string }
  ]
  'request-move': [node: UnifiedNode]
  'request-delete': [node: UnifiedNode]
}>()

// dialog 모드에서만 tree 사용
const tree = inject<ControlTreeApi | null>(CONTROL_TREE_INJECTION_KEY, null)

// ============================================================================
// 공통 — depth 별 indent + font weight (Phase 5-14i 폴리싱)
// ============================================================================
/**
 * P1 (5-14i v2) — indent scale 16 + 20*(d-1) → 14 + 18*(d-1) px.
 * depth 5/6 에서 우측 컬럼 압박 완화. v6 mockup 의 .node.cat-N / .leaf.lv-N padding-left
 * 와 정합 (cat-1=14, cat-2=32, cat-3=50, cat-4=68, cat-5=86, cat-6=104).
 */
const indentPx = computed<number>(() => 14 + 18 * (props.node.depth - 1))

/**
 * P5 (5-14i v2) — depth typography. weight + color 동시 변화로 트리 깊이 시각화.
 * 카테고리 (node-cat-name) 만 적용, leaf 는 leaf-name 으로 단일 톤.
 *
 *   depth=1 → 14px / 700 / #111827
 *   depth=2 → 13px / 600 / #111827
 *   depth=3 → 12.5px / 500 / #374151
 *   depth=4 → 12px / 500 / #4b5563
 *   depth=5 → 11.5px / 500 / #6b7280
 *   depth=6 → 11px / 500 / #6b7280
 */
const nameWeightClass = computed<string>(() => {
  switch (props.node.depth) {
    case 1:
    case 2: return 'text-[14px] font-bold text-gray-900'
    case 3: return 'text-[12.5px] font-medium text-gray-700'
    case 4: return 'text-[12px] font-medium text-gray-600'
    case 5: return 'text-[11.5px] font-medium text-gray-500'
    default: return 'text-[11px] font-medium text-gray-500'
  }
})

// ============================================================================
// view 모드 — 5-14g 동작
// ============================================================================
const viewExpanded = computed<boolean>(() => {
  return props.expandedIds.has(props.node.id)
})

const isLeafExpanded = computed<boolean>(() =>
  props.node.nodeType === 'control' && props.expandedLeafId === props.node.id
)

function viewHandleToggle(): void {
  emit('toggle-expand', props.node.id, props.node.nodeType)
}

function viewLeafStatus(): '완료' | '진행중' | '미수집' {
  if (props.node.nodeType !== 'control') return '미수집'
  const m = props.node.evidenceTypeCount ?? 0
  const n = props.node.collectedCount ?? 0
  if (m === 0) return '미수집'
  if (n >= m) return '완료'
  if (n > 0) return '진행중'
  return '미수집'
}

const viewStatusBadgeClass = computed<string>(() => {
  // v17: scoped CSS badge 클래스 (Tailwind inline 대체)
  if (viewHasPendingReview.value) return 'badge-pending'
  switch (viewLeafStatus()) {
    case '완료': return 'badge-done'
    case '진행중': return 'badge-prog'
    default: return 'badge-miss'
  }
})

const viewProgressPercent = computed<number>(() => {
  if (props.node.nodeType !== 'control') return 0
  const m = props.node.evidenceTypeCount ?? 0
  const n = props.node.collectedCount ?? 0
  if (m === 0) return 0
  return Math.min(100, Math.round((n / m) * 100))
})

const viewHasPendingReview = computed<boolean>(() =>
  props.node.nodeType === 'control' && (props.node.pendingReviewCount ?? 0) > 0
)

// ────────────────────────────────────────────────────────────────────────────
// v15 Phase 5-15a — Hybrid 모델 (spec §3.3.1.9)
// ────────────────────────────────────────────────────────────────────────────
//
// mutex 폐기 — 모든 노드가 자식 + 증빙 동시 보유 가능. leaf 노드 (`nodeType === 'control'`)
// 도 자식이 있을 수 있음. P20.v6 mockup 시각 정합:
//   - leaf.has-children: leaf 행 그대로 + 좌측 chev 12px 추가 (색·라벨 추가 0)
//   - 행 클릭 = 증빙 펼침 (regular leaf-expand, isLeafExpanded 토글)
//   - 좌측 chev 클릭 = 자식 펼침/접힘 (expandedIds 토글, 'category' 시그널 트릭)
//
// 부모 ControlsView 변경 0 — 기존 'toggle-expand' 시그널의 두 번째 인자가
// 'category' 면 expandedIds 토글, 'control' 이면 expandedLeafId 토글. hybrid leaf 의
// chev 는 'category' 시그널 보내서 자연스럽게 expandedIds 토글되어 자식 영역
// 펼쳐짐. 인터페이스 backward-compatible.

const hasChildren = computed<boolean>(() => {
  // view 모드 leaf 만 대상. dialog 모드 hybrid 변환은 후속 phase.
  if (props.mode !== 'view') return false
  if (props.node.nodeType !== 'control') return false
  // TreeRootNode.children 은 useControlTree 의 buildRootNodes 가 모든 노드에 빌드.
  return (props.node.children?.length ?? 0) > 0
})

/** v18: depth+자식 기반 렌더링 — nodeType 무관하게 자식 유무 판별 */
const viewHasAnyChildren = computed<boolean>(() => {
  if (props.mode !== 'view') return false
  return (props.node.children?.length ?? 0) > 0
})

/** hybrid leaf 의 자식 펼침 상태 (expandedIds 의 별칭, semantic 명료성). */
const viewChildrenExpanded = computed<boolean>(() => hasChildren.value && viewExpanded.value)

/**
 * P20.v6 (5-14i 결정, v15 5-15a 구현) — hybrid leaf 의 좌측 chev 클릭.
 *
 * 행 클릭 (`viewHandleToggle`) 은 그대로 'control' 시그널 → 부모가 expandedLeafId
 * 토글 (증빙 펼침). 본 chev 클릭은 'category' 시그널 → 부모가 expandedIds
 * 토글 (자식 펼침). 두 동작 독립이라 hybrid 노드는 증빙 + 자식 동시 펼침 가능.
 */
function viewHandleToggleChildren(e: Event): void {
  e.stopPropagation()
  emit('toggle-expand', props.node.id, 'category')
}

// ─── Phase 5-14i (P12, P14) — 검색 매치 표시 ───
//
// P14: highlightFn 이 전달되면 substring → amber 하이라이트 HTML 변환.
//      미전달 시 (또는 검색어 없을 때) escape 만 적용된 plain text 반환.
// P12: 카테고리 노드는 자손 매치 카운트가 1+ 이면 amber 핍 (`매치 N`) 노출.
//      leaf 는 핍 없음 (자기 자신이 매치 표시).
function escapeHtml(s: string): string {
  return s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c] ?? c))
}

const renderCode = computed<string>(() => {
  if (props.highlightFn) return props.highlightFn(props.node.code)
  return escapeHtml(props.node.code)
})

const renderName = computed<string>(() => {
  if (props.highlightFn) return props.highlightFn(props.node.name)
  return escapeHtml(props.node.name)
})

const catMatchCount = computed<number | null>(() => {
  if (!props.searchActive) return null
  if (props.node.nodeType !== 'category') return null
  const c = props.matchCountByCategoryId.get(props.node.id) ?? 0
  return c > 0 ? c : null
})

function viewOnGoEt(et: EvidenceTypeResponse): void {
  emit('go-evidence-type', et.id, props.node.id)
}

function viewOnZipDownload(): void {
  emit('zip-download', props.node.id, props.node.code)
}

// v18: 인라인 증빙 추가
const eviAddOpen = ref(false)
const eviAddName = ref('')
const eviAddInputRef = ref<HTMLInputElement | null>(null)

// 열릴 때 자동 포커스 + 외부 클릭 시 닫기
let eviAddClickOutside: ((e: MouseEvent) => void) | null = null

watch(eviAddOpen, (open) => {
  if (open) {
    nextTick(() => eviAddInputRef.value?.focus())
    setTimeout(() => {
      eviAddClickOutside = (e: MouseEvent) => {
        const form = document.querySelector('.et-add-form')
        if (form && !form.contains(e.target as Node)) {
          eviAddOpen.value = false
          eviAddName.value = ''
        }
      }
      document.addEventListener('click', eviAddClickOutside, { capture: true })
    }, 0)
  } else {
    if (eviAddClickOutside) {
      document.removeEventListener('click', eviAddClickOutside, { capture: true })
      eviAddClickOutside = null
    }
  }
})

onBeforeUnmount(() => {
  if (eviAddClickOutside) {
    document.removeEventListener('click', eviAddClickOutside, { capture: true })
  }
})

function viewOnCreateEt(): void {
  if (!eviAddName.value.trim()) return
  emit('create-evidence-type', props.node.id, eviAddName.value.trim())
  eviAddName.value = ''
  eviAddOpen.value = false
}

/**
 * v17 카드형 증빙 패널 — 증빙 유형 메타데이터 서브텍스트 생성.
 * ownerUserName / files[].collectionMethod / files[].version / files[].reviewStatus 조합.
 */
function eviMetaParts(et: EvidenceTypeResponse): string[] {
  const parts: string[] = []
  parts.push(et.ownerUserName ? `담당자: ${et.ownerUserName}` : '담당자 미배정')
  if (et.files && et.files.length > 0) {
    const autoCount = et.files.filter(f => f.collectionMethod === 'auto').length
    if (autoCount > 0) {
      parts.push(`자동 수집 ${autoCount}건`)
    } else {
      parts.push(`수동 업로드 ${et.files.length}건`)
    }
    const latest = et.files.reduce((a, b) => (a.version > b.version ? a : b))
    let versionPart = `최신 버전 v${latest.version}`
    const statusMap: Record<string, string> = {
      auto_approved: '자동 승인', approved: '승인됨',
      pending: '검토 대기', rejected: '반려',
    }
    if (latest.reviewStatus && statusMap[latest.reviewStatus]) {
      versionPart += ` · ${statusMap[latest.reviewStatus]}`
    }
    parts.push(versionPart)
  }
  return parts
}

function viewOnDeleteEt(et: EvidenceTypeResponse): void {
  emit('delete-evidence-type', et.id, et.name)
}

// view 모드 emits 재귀 forwarding
function forwardToggle(id: number, nt: 'category' | 'control'): void {
  emit('toggle-expand', id, nt)
}
function forwardGoEt(etId: number, ctrlId: number): void {
  emit('go-evidence-type', etId, ctrlId)
}
function forwardZip(ctrlId: number, ctrlCode: string): void {
  emit('zip-download', ctrlId, ctrlCode)
}
function forwardDeleteEt(etId: number, etName: string): void {
  emit('delete-evidence-type', etId, etName)
}
function forwardCreateEt(nodeId: number, name: string): void {
  emit('create-evidence-type', nodeId, name)
}

// ============================================================================
// dialog 모드 — 5-14h 편집
// ============================================================================
const isDraft = computed<boolean>(() => {
  return props.mode === 'dialog' && (props.node as UnifiedNode)._kind === 'draft'
})

const dialogNode = computed<UnifiedNode | null>(() => {
  return props.mode === 'dialog' ? (props.node as UnifiedNode) : null
})

const dialogExpanded = computed<boolean>(() => {
  if (!tree || !dialogNode.value) return false
  return tree.dialogIsExpanded(dialogNode.value)
})

function dialogHandleToggle(): void {
  if (!tree || !dialogNode.value) return
  tree.dialogToggleExpand(dialogNode.value)
}

const codeInputRef = ref<HTMLInputElement | null>(null)
const nameInputRef = ref<HTMLInputElement | null>(null)

defineExpose({
  focusName: () => nameInputRef.value?.focus(),
  focusCode: () => codeInputRef.value?.focus(),
})

function dialogHandleCodeInput(e: Event): void {
  if (!tree || !dialogNode.value) return
  tree.setCode(dialogNode.value, (e.target as HTMLInputElement).value)
}

function dialogHandleNameInput(e: Event): void {
  if (!tree || !dialogNode.value) return
  tree.setName(dialogNode.value, (e.target as HTMLInputElement).value)
}

const lastSeenCode = ref<string>('')
function dialogHandleCodeFocus(): void {
  if (!dialogNode.value) return
  lastSeenCode.value = dialogNode.value.code
}

function dialogHandleCodeBlur(): void {
  if (!dialogNode.value) return
  if (dialogNode.value._kind !== 'existing') return
  if (dialogNode.value.nodeType !== 'control') return
  if (lastSeenCode.value === dialogNode.value.code) return
  emit('leaf-code-blur', {
    node: dialogNode.value,
    oldCode: lastSeenCode.value,
    newCode: dialogNode.value.code,
  })
}

function focusDraftByTempId(tempId: string): void {
  nextTick(() => {
    const el = document.querySelector<HTMLInputElement>(
      `[data-temp-id="${tempId}"] input.row-name-input`
    )
    el?.focus()
  })
}

// ─── hover 액션 ───
async function dialogHandleAddChildControl(): Promise<void> {
  if (!tree || !dialogNode.value) return
  const draft = tree.createChildControl(dialogNode.value)
  await nextTick()
  focusDraftByTempId(draft.tempId)
}

async function dialogHandleAddChildCategory(): Promise<void> {
  if (!tree || !dialogNode.value) return
  try {
    const draft = tree.createChildCategory(dialogNode.value)
    await nextTick()
    focusDraftByTempId(draft.tempId)
  } catch (err) {
    console.warn('[ControlNodeRow] createChildCategory', err)
  }
}

async function dialogHandleAddSiblingControl(): Promise<void> {
  if (!tree || !dialogNode.value) return
  const draft = tree.createSiblingControl(dialogNode.value)
  if (draft) {
    await nextTick()
    focusDraftByTempId(draft.tempId)
  }
}

function dialogHandleRequestDelete(): void {
  if (!dialogNode.value) return
  emit('request-delete', dialogNode.value)
}

function dialogHandleRequestMove(): void {
  if (!dialogNode.value) return
  emit('request-move', dialogNode.value)
}

const dialogHasValidationErrors = computed<boolean>(() => {
  return (dialogNode.value?._validationErrors?.length ?? 0) > 0
})

const dialogValidationErrorTitle = computed<string>(() => {
  const errs = dialogNode.value?._validationErrors ?? []
  return errs.map((d) => d.message).join('\n')
})

const dialogShowDirtyDot = computed<boolean>(() => {
  if (props.mode !== 'dialog') return false
  const d = (props.node as UnifiedNode)._dirty
  return d !== null && d !== undefined && d !== 'deleted'
})

// dialog 재귀 forwarding
function forwardLeafCodeBlur(p: { node: UnifiedNode; oldCode: string; newCode: string }): void {
  emit('leaf-code-blur', p)
}
function forwardRequestMove(n: UnifiedNode): void {
  emit('request-move', n)
}
function forwardRequestDelete(n: UnifiedNode): void {
  emit('request-delete', n)
}
</script>

<template>
  <!-- ════════════════════════════════════════════════════════════════════════ -->
  <!-- 5-14g view 모드                                                          -->
  <!-- ════════════════════════════════════════════════════════════════════════ -->
  <div v-if="mode === 'view'" :class="['control-node-row-view', { dimmed }]">
    <!--
      카테고리 행 — Phase 5-14i 폴리싱 (P5/P12/P14):
        P5: depth 별 font weight + color 동시 변화 (nameWeightClass).
        P14: highlightFn 으로 검색 매치 substring amber 하이라이트 (HTML 렌더).
        P12: searchActive + matchCountByCategoryId.get(node.id) > 0 → amber 핍 노출.
    -->
    <!-- depth 1-2 → 항상 카테고리 스타일 (쉐브론) -->
    <div
      v-if="node.depth <= 2"
      class="row-view category-row group cursor-pointer hover:bg-gray-50"
      :style="{ paddingLeft: `${indentPx}px` }"
      @click="emit('toggle-expand', node.id, 'category')">
      <i :class="['pi text-[11px] text-gray-400 transition-transform duration-150 mr-1.5 shrink-0',
        viewExpanded ? 'pi-chevron-down' : 'pi-chevron-right']"></i>
      <span
        class="cat-code-view font-mono text-[11px] text-gray-400 tabular-nums mr-2 shrink-0"
        v-html="renderCode"
      ></span>
      <span
        :class="['cat-name-view flex-1 truncate', nameWeightClass]"
        v-html="renderName"
      ></span>
      <span
        v-if="catMatchCount !== null"
        class="match-count-pip"
        aria-label="매치 카운트">
        매치 {{ catMatchCount }}
      </span>
      <span class="text-[11px] text-gray-400 tabular-nums shrink-0 ml-2">
        항목
        <span v-if="'descendantLeafCount' in node">{{ node.descendantLeafCount }}</span>
      </span>
    </div>

    <!-- ═══ leaf 행 (depth 3+ 전체) — 증빙 패널 + 하위 칩 통합 ═══ -->
    <div
      v-else
      :class="[
        'leaf-row group cursor-pointer',
        viewHasPendingReview ? 'has-pending' : '',
        isLeafExpanded ? 'expanded' : '',
      ]"
      :style="{ paddingLeft: `${indentPx + 16}px` }"
      @click="emit('toggle-expand', node.id, 'control')">
      <span class="leaf-code" v-html="renderCode"></span>
      <span class="leaf-name">
        <span v-html="renderName"></span>
        <!-- 하위 칩 — 자식 있으면 항상 표시 (nodeType 무관) -->
        <button
          v-if="viewHasAnyChildren"
          :class="['sub-chip', { collapsed: !viewExpanded }]"
          :title="viewExpanded ? '하위 항목 접기' : '하위 항목 펼치기'"
          @click.stop="emit('toggle-expand', node.id, 'category')">
          <svg class="sub-chip-chev" viewBox="0 0 16 16" fill="none" stroke="currentColor"
               stroke-width="2" width="10" height="10"><path d="M4 6l4 4 4-4"/></svg>
          하위 {{ node.children?.length ?? 0 }}
        </button>
      </span>
      <span class="leaf-evi">증빙 {{ node.evidenceTypeCount ?? 0 }}</span>
      <span class="leaf-progress">
        <span>{{ node.collectedCount ?? 0 }}/{{ node.evidenceTypeCount ?? 0 }}</span>
        <span class="progress-bar">
          <div
            :class="viewLeafStatus() === '완료' ? 'complete' : ''"
            :style="{ width: `${viewProgressPercent}%` }">
          </div>
        </span>
      </span>
      <span class="leaf-status">
        <span :class="['badge', viewStatusBadgeClass]">
          <span v-if="viewHasPendingReview" class="pending-badge-dot"></span>
          {{ viewHasPendingReview ? `검토 대기 ${node.pendingReviewCount ?? 0}` : viewLeafStatus() }}
        </span>
      </span>
      <i :class="['leaf-chev pi',
        isLeafExpanded ? 'pi-chevron-down' : 'pi-chevron-right']"></i>
    </div>

    <!-- 증빙 패널 (레퍼런스 HTML 정합 — CSS Grid height 애니메이션) -->
    <div
      v-if="controlDetail"
      :class="['evi-panel', { collapsed: !isLeafExpanded }]">
      <div class="evi-panel-inner">
        <div class="evi-panel-content" :style="{ paddingLeft: `${indentPx + 32}px` }">
          <div v-if="detailLoading" class="evi-loading">
            <i class="pi pi-spin pi-spinner"></i> 로딩 중...
          </div>
          <div v-else>
            <div class="evi-panel-head">
              <span class="evi-label">필요 증빙 ({{ controlDetail.evidenceTypes.length }})</span>
              <div class="evi-head-actions">
                <button type="button" class="evi-action-btn" @click.stop="viewOnZipDownload">
                  전체 다운로드 (ZIP)
                </button>
              </div>
            </div>
            <!-- 증빙 카드 (클릭→상세, 삭제 아이콘) -->
            <div
              v-for="et in controlDetail.evidenceTypes"
              :key="et.id"
              class="et-card group/et"
              @click.stop="viewOnGoEt(et)">
              <div class="et-card-top">
                <div class="et-card-name">{{ et.name }}</div>
                <button
                  type="button"
                  class="et-card-del"
                  title="증빙 유형 삭제"
                  @click.stop="viewOnDeleteEt(et)">
                  <i class="pi pi-trash"></i>
                </button>
              </div>
              <div class="et-card-meta">
                <span v-for="(part, i) in eviMetaParts(et)" :key="i">{{ part }}</span>
              </div>
            </div>
            <!-- 인라인 추가 폼 -->
            <div class="et-add-row" v-if="!eviAddOpen" @click.stop="eviAddOpen = true">
              <i class="pi pi-plus"></i>
              <span>증빙 유형 추가</span>
            </div>
            <div class="et-add-form" v-else @click.stop>
              <input
                ref="eviAddInputRef"
                v-model="eviAddName"
                class="et-add-input"
                placeholder="증빙 유형 이름"
                @keydown.enter.prevent="viewOnCreateEt"
                @keydown.esc.prevent="eviAddOpen = false; eviAddName = ''" />
              <button
                type="button"
                class="et-add-btn"
                :disabled="!eviAddName.trim()"
                @click.stop="viewOnCreateEt">
                추가
              </button>
              <button
                type="button"
                class="et-add-cancel"
                @click.stop="eviAddOpen = false; eviAddName = ''">
                취소
              </button>
            </div>
            <div v-if="controlDetail.evidenceTypes.length === 0 && !eviAddOpen" class="evi-empty">
              이 항목에 등록된 증빙 유형이 없습니다.
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- v18: 통합 자식 재귀 (depth 1-2 항상 / depth 3+ 자식 있을 때) -->
    <div v-if="(node.depth <= 2 || viewHasAnyChildren) && viewExpanded">
      <ControlNodeRow
        v-for="child in (node.children as TreeRootNode[])"
        :key="child.id"
        :node="child"
        mode="view"
        :expanded-ids="expandedIds"
        :expanded-leaf-id="expandedLeafId"
        :control-detail="controlDetail"
        :detail-loading="detailLoading"
        :dimmed="dimmed"
        :highlight-fn="highlightFn"
        :match-count-by-category-id="matchCountByCategoryId"
        :search-active="searchActive"
        @toggle-expand="forwardToggle"
        @go-evidence-type="forwardGoEt"
        @zip-download="forwardZip"
        @delete-evidence-type="forwardDeleteEt"
        @create-evidence-type="forwardCreateEt"
      />
      <!-- depth 1-2 empty CTA -->
      <div
        v-if="(node.children as TreeRootNode[]).length === 0 && node.depth <= 2"
        class="empty-cta-row"
        :style="{ paddingLeft: `${indentPx + 16}px` }">
        <i class="pi pi-info-circle text-[10px] text-gray-300 mr-1.5"></i>
        <span>이 분류에 관리 항목이 없습니다 ·</span>
        <strong class="text-blue-500 ml-1">[관리 항목]</strong>
        <span class="ml-1">에서 추가</span>
      </div>
    </div>
  </div>

  <!-- ════════════════════════════════════════════════════════════════════════ -->
  <!-- 5-14h dialog 모드                                                        -->
  <!-- ════════════════════════════════════════════════════════════════════════ -->
  <div
    v-else
    class="control-node-row-dialog"
    :class="{ 'is-draft': isDraft, 'has-error': dialogHasValidationErrors }"
    :data-temp-id="(node as UnifiedNode).tempId"
    :data-node-id="(node as UnifiedNode)._kind === 'existing' ? node.id : null">
    <!-- 카테고리 행 -->
    <div
      v-if="node.nodeType === 'category'"
      class="row-dialog category-row-dialog group"
      :class="{ 'is-draft-row': isDraft, 'sub-category': node.depth >= 3 }"
      :style="{ paddingLeft: `${indentPx}px` }">
      <!-- depth 1-2: 쉐브론, depth 3+: 서브 마커 -->
      <button
        v-if="node.depth < 3"
        type="button"
        class="chevron-btn"
        :aria-label="dialogExpanded ? '접기' : '펼치기'"
        @click="dialogHandleToggle">
        <i :class="['pi text-xs text-gray-400 transition-transform duration-150',
          dialogExpanded ? 'pi-chevron-down' : 'pi-chevron-right']"></i>
      </button>
      <span v-else class="sub-marker" @click="dialogHandleToggle">
        <i :class="['pi', dialogExpanded ? 'pi-minus' : 'pi-plus']"></i>
      </span>
      <input
        ref="codeInputRef"
        class="row-code-input cat-code-input"
        :value="node.code"
        placeholder="1"
        spellcheck="false"
        @input="dialogHandleCodeInput"
        @focus="dialogHandleCodeFocus"
        @blur="dialogHandleCodeBlur"
      />
      <input
        ref="nameInputRef"
        class="row-name-input cat-name-input"
        :value="node.name"
        placeholder="분류 이름"
        spellcheck="false"
        @input="dialogHandleNameInput"
      />
      <span class="cat-meta-dialog">
        항목
        <span v-if="dialogNode">{{ dialogNode.descendantLeafCount }}</span>
      </span>
      <span v-if="dialogShowDirtyDot" class="dirty-dot" title="미저장 변경"></span>
      <span
        v-if="dialogHasValidationErrors"
        class="error-dot"
        :title="dialogValidationErrorTitle"></span>
      <div class="row-actions">
        <button
          v-if="dialogNode?._kind === 'existing'"
          type="button"
          class="row-iconbtn"
          title="다른 분류로 이동"
          @click="dialogHandleRequestMove">
          <i class="pi pi-arrows-alt"></i>
        </button>
        <button type="button" class="row-iconbtn row-iconbtn-danger" title="삭제"
          @click="dialogHandleRequestDelete">
          <i class="pi pi-trash"></i>
        </button>
      </div>
    </div>

    <!-- leaf 행 (v18 — 모든 항목 쉐브론 + 펼침 가능) -->
    <div
      v-else
      class="row-dialog control-row-dialog group"
      :class="{ 'is-draft-row': isDraft }"
      :style="{ paddingLeft: `${indentPx}px` }">
      <button
        type="button"
        class="chevron-btn"
        :aria-label="dialogExpanded ? '접기' : '펼치기'"
        @click="dialogHandleToggle">
        <i :class="['pi text-xs text-gray-400 transition-transform duration-150',
          dialogExpanded ? 'pi-chevron-down' : 'pi-chevron-right']"></i>
      </button>
      <input
        ref="codeInputRef"
        class="row-code-input ctrl-code-input"
        :value="node.code"
        placeholder="1.1.1"
        spellcheck="false"
        @input="dialogHandleCodeInput"
        @focus="dialogHandleCodeFocus"
        @blur="dialogHandleCodeBlur"
      />
      <input
        ref="nameInputRef"
        class="row-name-input ctrl-name-input"
        :value="node.name"
        placeholder="관리 항목 이름"
        spellcheck="false"
        @input="dialogHandleNameInput"
      />
      <span class="ctrl-evidence-dialog">· 증빙 {{ node.evidenceTypeCount ?? 0 }}</span>
      <span v-if="dialogShowDirtyDot" class="dirty-dot" title="미저장 변경"></span>
      <span
        v-if="dialogHasValidationErrors"
        class="error-dot"
        :title="dialogValidationErrorTitle"></span>
      <div class="row-actions">
        <button
          v-if="dialogNode?._kind === 'existing'"
          type="button"
          class="row-iconbtn"
          title="다른 위치로 이동"
          @click="dialogHandleRequestMove">
          <i class="pi pi-arrows-alt"></i>
        </button>
        <button type="button" class="row-iconbtn row-iconbtn-danger" title="삭제"
          @click="dialogHandleRequestDelete">
          <i class="pi pi-trash"></i>
        </button>
      </div>
    </div>

    <!--
      자식 재귀 (dialog) — v18: 모든 항목 펼침 가능.
      펼침 시 자식 목록 + "항목 추가" 버튼 노출.
      자식 0 이어도 "항목 추가"만 표시되어 하위 항목 생성 가능.
    -->
    <div v-if="dialogExpanded">
      <ControlNodeRow
        v-for="child in (node.children as UnifiedNode[])"
        :key="(child as UnifiedNode)._key"
        :node="child"
        mode="dialog"
        @leaf-code-blur="forwardLeafCodeBlur"
        @request-move="forwardRequestMove"
        @request-delete="forwardRequestDelete"
      />
      <!-- v18: 항목 추가 (드롭다운 없이 바로 추가) -->
      <div
        class="add-row"
        :style="{ paddingLeft: `${indentPx + 20}px` }"
        @click.stop="dialogHandleAddChildControl">
        <i class="pi pi-plus"></i>
        <span>항목 추가</span>
        <span v-if="tree && dialogNode" class="add-row-hint">
          {{ tree.nextSiblingCode(dialogNode) }}
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ═══ VIEW 모드 (v17 재설계) ═══ */
.control-node-row-view { position: relative; }
.control-node-row-view.dimmed { opacity: 0.35; transition: opacity 0.15s; }
.control-node-row-view.dimmed:hover { opacity: 0.6; }

/* 카테고리 행 */
.row-view {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 16px 8px 0; cursor: pointer;
  user-select: none; position: relative;
  transition: background-color 0.12s;
}
.row-view.category-row:hover { background-color: #f9fafb; }

/* leaf 행 (grid 6열) */
.leaf-row {
  display: grid;
  grid-template-columns: 100px 1fr 60px 90px 100px 24px;
  gap: 12px; align-items: center;
  padding: 8px 16px 8px 0; cursor: pointer;
  border-bottom: 1px solid #eef0f3;
  transition: background 0.12s; position: relative;
}
.leaf-row:nth-child(even) { background: #fafbfc; }
.leaf-row:hover { background: #f0f2f5; }
.leaf-row.expanded { background: #eff6ff; }
.leaf-row.expanded::before {
  content: ""; position: absolute; left: 0; top: 0; bottom: 0;
  width: 3px; background: #3b82f6;
}
.leaf-row.has-pending { background: #fffbeb; }
.leaf-row.has-pending::before {
  content: ""; position: absolute; left: 0; top: 0; bottom: 0;
  width: 3px; background: #fbbf24;
}
.leaf-row.has-pending:hover { background: #fef6e0; }

.leaf-code {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 12px; color: #3b82f6; font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.leaf-name {
  font-size: 13px; color: #111827;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.leaf-evi {
  font-size: 12px; color: #6b7280; text-align: right;
  font-variant-numeric: tabular-nums;
}
.leaf-progress {
  display: flex; align-items: center; gap: 6px;
  font-size: 11px; color: #6b7280; font-variant-numeric: tabular-nums;
}
.progress-bar {
  width: 40px; height: 3px; background: #e5e7eb;
  border-radius: 2px; overflow: hidden;
}
.progress-bar > div { height: 100%; background: #3b82f6; }
.progress-bar > div.complete { background: #10b981; }

.leaf-status { display: flex; align-items: flex-start; }

/* badge (v17 — scoped CSS) */
.badge {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 8px; font-size: 11px; font-weight: 500;
  border-radius: 4px; line-height: 1.3;
}
.badge.badge-done { background: #dcfce7; color: #166534; }
.badge.badge-prog { background: #dbeafe; color: #1e40af; }
.badge.badge-miss { background: #f3f4f6; color: #6b7280; }
.badge.badge-pending { background: #fef3c7; color: #b45309; font-weight: 600; }
.pending-badge-dot {
  width: 5px; height: 5px; border-radius: 50%;
  background: #f59e0b; flex-shrink: 0;
}

.leaf-chev {
  color: #d1d5db; font-size: 12px;
  transition: transform 0.15s, color 0.15s;
}
.leaf-row:hover .leaf-chev { color: #9ca3af; }
.leaf-row.expanded .leaf-chev { transform: rotate(90deg); color: #3b82f6; }

/* hybrid 통합 칩 */
.sub-chip {
  display: inline-flex; align-items: center; gap: 3px;
  font-size: 10px; color: #3b82f6; font-weight: 600; font-family: inherit;
  background: #eff6ff; border: 1px solid #dbeafe;
  padding: 2px 8px 2px 5px; border-radius: 10px;
  margin-left: 8px; vertical-align: middle;
  cursor: pointer; transition: all 0.15s; line-height: 1;
}
.sub-chip:hover { background: #dbeafe; border-color: #93c5fd; color: #2563eb; }
.sub-chip-chev { transition: transform 0.2s; flex-shrink: 0; }
.sub-chip.collapsed .sub-chip-chev { transform: rotate(-90deg); }
.sub-chip.collapsed { color: #6b7280; background: #f3f4f6; border-color: #e5e7eb; }
.sub-chip.collapsed:hover { color: #3b82f6; background: #eff6ff; border-color: #dbeafe; }

/* 증빙 패널 (CSS Grid height 애니메이션)
 *
 * 열기/닫기: .collapsed 토글 → grid-template-rows 1fr ↔ 0fr.
 * 최초 렌더: @starting-style 로 0fr 에서 시작 → 1fr 트랜지션 (v-if 신규 삽입 시).
 */
.evi-panel {
  display: grid;
  grid-template-rows: 1fr;
  transition: grid-template-rows 0.25s ease-out;
}
.evi-panel.collapsed {
  grid-template-rows: 0fr;
}
/* 최초 DOM 삽입 시 0fr 에서 시작하여 1fr 로 애니메이션 */
@starting-style {
  .evi-panel:not(.collapsed) {
    grid-template-rows: 0fr;
  }
}
.evi-panel-inner {
  overflow: hidden;
}
.evi-panel-content {
  background: #eff6ff;
  border-bottom: 1px solid #dbeafe;
  padding: 12px 16px 16px;
  opacity: 1;
  transition: opacity 0.18s ease-out;
}
@starting-style {
  .evi-panel:not(.collapsed) .evi-panel-content {
    opacity: 0;
  }
}
.evi-panel.collapsed .evi-panel-content {
  opacity: 0;
  border-bottom-color: transparent;
}
.evi-panel-head {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 10px;
}
.evi-label { font-size: 11px; color: #6b7280; }
.evi-head-actions { display: flex; gap: 6px; }
.evi-action-btn {
  height: 26px; padding: 0 10px;
  font-size: 11px; font-family: inherit;
  background: white; color: #4b5563;
  border: 1px solid #e5e7eb; border-radius: 5px;
  cursor: pointer; transition: background 0.1s;
  display: inline-flex; align-items: center; gap: 4px;
}
.evi-action-btn .pi { font-size: 10px; }
.evi-action-btn:hover { background: #f9fafb; }

/* 증빙 유형 카드 (레퍼런스 et-card 정합) */
.et-card {
  background: white; border: 1px solid #e5e7eb; border-radius: 8px;
  padding: 12px 14px; margin-bottom: 6px;
  cursor: pointer; transition: border-color 0.1s;
}
.et-card:hover { border-color: #94a3b8; }
.et-card-name { font-size: 13px; font-weight: 500; color: #111827; }
.et-card-top {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
}
.et-card-del {
  opacity: 0; border: none; background: transparent;
  color: #9ca3af; cursor: pointer; padding: 2px;
  border-radius: 4px; font-size: 11px;
  transition: opacity 0.1s, color 0.1s;
}
.et-card.group\/et:hover .et-card-del { opacity: 1; }
.et-card-del:hover { color: #ef4444; background: #fef2f2; }
.et-card-meta {
  font-size: 11px; color: #9ca3af; margin-top: 4px;
  display: flex; gap: 12px; flex-wrap: wrap;
}
/* 인라인 추가 */
.et-add-row {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 14px; margin-top: 6px;
  border: 1px dashed #d1d5db; border-radius: 6px;
  color: #9ca3af; font-size: 12px;
  cursor: pointer; transition: all 0.1s;
}
.et-add-row:hover { color: #3b82f6; border-color: #93c5fd; background: white; }
.et-add-row .pi { font-size: 10px; }
.et-add-form {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 0; margin-top: 6px;
}
.et-add-input {
  flex: 1; height: 30px; padding: 0 10px;
  border: 1px solid #d1d5db; border-radius: 6px;
  font-size: 12px; outline: none; font-family: inherit;
}
.et-add-input:focus { border-color: #3b82f6; }
.et-add-btn {
  height: 30px; padding: 0 12px;
  background: #111827; color: white;
  border: none; border-radius: 6px;
  font-size: 11px; font-weight: 500;
  cursor: pointer; font-family: inherit;
}
.et-add-btn:hover { background: #1f2937; }
.et-add-btn:disabled { background: #d1d5db; cursor: not-allowed; }
.et-add-cancel {
  height: 30px; padding: 0 10px;
  background: transparent; color: #6b7280;
  border: 1px solid #e5e7eb; border-radius: 6px;
  font-size: 11px; cursor: pointer; font-family: inherit;
}
.et-add-cancel:hover { background: #f9fafb; }
.evi-loading { padding: 16px; text-align: center; color: #9ca3af; font-size: 13px; }
.evi-empty { padding: 16px; font-size: 12px; color: #9ca3af; text-align: center; }

.hybrid-children { border-left: 1px solid #f3f4f6; }

/* 매치 하이라이트 (Phase 5-14i P14 보존) */
:deep(.match-highlight) {
  background: #fef3c7; color: #92400e;
  padding: 1px 2px; border-radius: 2px; font-weight: 600;
}
.match-count-pip {
  display: inline-flex; padding: 1px 6px; border-radius: 99px;
  background: #fef3c7; color: #b45309; font-size: 10px; font-weight: 600;
}

/* 포커스 flash (Phase 5-14i P19 보존) */
@keyframes focusFlash {
  0%   { box-shadow: inset 0 0 0 2px rgba(251, 191, 36, 0); }
  20%  { box-shadow: inset 0 0 0 2px #fbbf24; background-color: #fffbeb; }
  100% { box-shadow: inset 0 0 0 2px rgba(251, 191, 36, 0); }
}
.leaf-row.focus-flash, .row-view.focus-flash { animation: focusFlash 1.4s ease-out; }

/* P6 (5-14i) — empty CTA (분류 안 통제 0) — 보존 */
.empty-cta-row {
  display: flex; align-items: center;
  padding: 8px 16px;
  border-bottom: 1px dashed #e5e7eb;
  font-size: 12px; color: #6b7280;
  cursor: default; background-color: #fafbfc;
}

/* ─────────────────────── dialog 모드 ─────────────────────── */
.control-node-row-dialog {
  position: relative;
}

.control-node-row-dialog.has-error > .row-dialog {
  outline: 1px solid rgb(252 165 165);
  outline-offset: -1px;
}

.row-dialog {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 16px 7px 0;
  font-size: 13px;
  user-select: none;
  border-bottom: 1px solid rgb(243 244 246);
  transition: background 0.08s;
}

.row-dialog.category-row-dialog:hover,
.row-dialog.control-row-dialog:hover {
  background-color: rgb(240 242 245);
}

/* v18: 통제 행 줄무늬 */
.row-dialog.control-row-dialog:nth-child(even) {
  background: rgb(250 251 252);
}

.row-dialog.is-draft-row {
  background-color: rgb(239 246 255) !important;
}

.chevron-btn {
  background: transparent;
  border: none;
  width: 14px;
  height: 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  cursor: pointer;
  padding: 0;
}

/* v18: depth 3+ 서브 분류 — 대분류와 시각 구분 */
.sub-marker {
  width: 18px; height: 18px;
  display: inline-flex; align-items: center; justify-content: center;
  flex-shrink: 0; cursor: pointer;
  font-size: 8px; color: #9ca3af;
  background: #f3f4f6; border: 1px solid #e5e7eb;
  border-radius: 4px;
  transition: all 0.12s;
}
.sub-marker:hover { background: #e5e7eb; color: #6b7280; }
.sub-marker .pi { font-size: 8px; }

.row-dialog.sub-category {
  background: rgb(249 250 251);
  border-left: 2px solid #d1d5db;
}
.row-dialog.sub-category .cat-code-input {
  font-size: 10px; width: 100px; color: #9ca3af;
}
.row-dialog.sub-category .cat-name-input {
  font-size: 12px !important; font-weight: 500 !important; color: #6b7280 !important;
}

.cat-meta-dialog {
  font-size: 11px;
  color: rgb(156 163 175);
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.ctrl-evidence-dialog {
  font-size: 11px;
  color: rgb(156 163 175);
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

/* v18: input border 상시 표시 (가독성 개선) */
.row-code-input,
.row-name-input {
  border: 1px solid rgb(229 231 235);
  border-radius: 4px;
  padding: 0 6px;
  height: 26px;
  background: rgb(250 251 252);
  outline: none;
  transition: border-color 0.1s, background-color 0.1s;
  font-family: inherit;
}

.row-code-input:focus,
.row-name-input:focus {
  border-color: rgb(59 130 246);
  background: white;
}

.is-draft-row .row-code-input,
.is-draft-row .row-name-input {
  border-color: rgb(147 197 253);
  background: white;
}

.cat-code-input {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
  color: rgb(107 114 128);
  width: 100px;
  flex-shrink: 0;
}

.ctrl-code-input {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11.5px;
  color: rgb(37 99 235);
  width: 100px;
  flex-shrink: 0;
}

.cat-name-input {
  flex: 1;
  min-width: 0;
  color: rgb(17 24 39);
  font-size: 13px;
  font-weight: 500;
}
.ctrl-name-input {
  flex: 1;
  min-width: 0;
  color: rgb(17 24 39);
  font-size: 13px;
}

.dirty-dot {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: rgb(37 99 235);
  flex-shrink: 0;
}

.error-dot {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: rgb(239 68 68);
  flex-shrink: 0;
}

.row-actions {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.1s;
  flex-shrink: 0;
}

.row-dialog.group:hover .row-actions,
.row-dialog.is-draft-row .row-actions {
  opacity: 1;
}

.row-iconbtn {
  background: transparent;
  border: none;
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: rgb(156 163 175);
  cursor: pointer;
  border-radius: 4px;
  font-size: 11px;
  padding: 0;
}

.row-iconbtn:hover {
  background: white;
  color: rgb(17 24 39);
}

.row-iconbtn-danger:hover {
  color: rgb(239 68 68);
}

/* v18: 항목 추가 행 */
.add-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  color: rgb(156 163 175);
  font-size: 12px; font-weight: 500;
  cursor: pointer;
  border-bottom: 1px solid rgb(243 244 246);
}
.add-row:hover {
  color: rgb(37 99 235);
  background: rgb(249 250 251);
}
.add-row-hint {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px; color: rgb(209 213 219);
  margin-left: auto;
}
.add-row:hover .add-row-hint { color: rgb(147 197 253); }

.add-row-category {
  margin-bottom: 4px;
}
</style>