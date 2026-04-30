<script setup lang="ts">
/**
 * Phase 5-14g + 5-14h — ControlNodeRow.vue (재귀 트리 행)
 *
 * 두 모드 지원:
 *   - mode='view'   : 5-14g 동작 보존 — props 통과, leaf 펼침 시 evidence 카드 표시,
 *                     5-14g emits (toggle-expand / go-evidence-type / add-evidence-type /
 *                     zip-download / delete-evidence-type) 모두 그대로
 *   - mode='dialog' : 5-14h 신규 — 인라인 input (코드/이름) + 카테고리/leaf hover 액션 +
 *                     키보드 단축키. tree composable 은 inject 로 받음
 *
 * provide/inject (dialog 모드 only): UnifiedControlsDialog 가
 * provide(CONTROL_TREE_INJECTION_KEY, tree) → 본 컴포넌트가 inject. view 모드는
 * tree 의존성 없음 (5-14g 인터페이스 보존).
 */

import { computed, inject, ref, nextTick } from 'vue'
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
  // ─── view 모드 emits (5-14g 보존) ───
  'toggle-expand': [nodeId: number, nodeType: 'category' | 'control']
  'go-evidence-type': [evidenceTypeId: number, controlId: number]
  'add-evidence-type': [controlId: number]
  'zip-download': [controlId: number, controlCode: string]
  'delete-evidence-type': [evidenceTypeId: number, evidenceTypeName: string]

  // ─── dialog 모드 emits (5-14h 신규) ───
  'leaf-code-blur': [
    payload: { node: UnifiedNode; oldCode: string; newCode: string }
  ]
  'tab-on-existing': [node: UnifiedNode]
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
    case 1: return 'text-[14px] font-bold text-gray-900'
    case 2: return 'text-[13px] font-semibold text-gray-900'
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
  // P2 (5-14i v2): 검토 대기 행은 status 배지도 amber 톤으로 정합 (pending vs 완료/진행중/미수집 구분)
  if (viewHasPendingReview.value) return 'badge-pending'
  switch (viewLeafStatus()) {
    case '완료': return 'bg-green-100 text-green-700'
    case '진행중': return 'bg-blue-100 text-blue-700'
    default: return 'bg-gray-100 text-gray-600'
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

function viewOnAddEt(): void {
  emit('add-evidence-type', props.node.id)
}

function viewOnZipDownload(): void {
  emit('zip-download', props.node.id, props.node.code)
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
function forwardAddEt(ctrlId: number): void {
  emit('add-evidence-type', ctrlId)
}
function forwardZip(ctrlId: number, ctrlCode: string): void {
  emit('zip-download', ctrlId, ctrlCode)
}
function forwardDeleteEt(etId: number, etName: string): void {
  emit('delete-evidence-type', etId, etName)
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

// ─── 키보드 단축키 (메서드 추출 — Vue 3.5 SFC 학습) ───
async function dialogHandleEnterKey(): Promise<void> {
  if (!tree || !dialogNode.value) return
  if (dialogNode.value.nodeType === 'category') {
    const draft = tree.createChildControl(dialogNode.value)
    await nextTick()
    focusDraftByTempId(draft.tempId)
  } else {
    const draft = tree.createSiblingControl(dialogNode.value)
    if (draft) {
      await nextTick()
      focusDraftByTempId(draft.tempId)
    }
  }
}

async function dialogHandleTabKey(e: KeyboardEvent): Promise<void> {
  if (!tree || !dialogNode.value) return
  e.preventDefault()
  if (dialogNode.value._kind === 'existing') {
    emit('tab-on-existing', dialogNode.value)
    return
  }
  const result = tree.convertNodeType(dialogNode.value)
  if (result.ok) {
    await nextTick()
    focusDraftByTempId(result.childDraft.tempId)
  }
}

function dialogHandleEscKey(): void {
  if (!tree || !dialogNode.value) return
  if (dialogNode.value._kind === 'draft') {
    tree.deleteNode(dialogNode.value)
  }
}

function dialogHandleBackspaceKey(e: KeyboardEvent): void {
  if (!tree || !dialogNode.value) return
  if (dialogNode.value._kind !== 'draft') return
  const target = e.target as HTMLInputElement
  if (target.value !== '') return
  if (target === nameInputRef.value && codeInputRef.value && codeInputRef.value.value === '') {
    e.preventDefault()
    tree.deleteNode(dialogNode.value)
  }
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
function forwardTabOnExisting(n: UnifiedNode): void {
  emit('tab-on-existing', n)
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
    <div
      v-if="node.nodeType === 'category'"
      class="row-view category-row group cursor-pointer hover:bg-gray-50"
      :style="{ paddingLeft: `${indentPx}px` }"
      @click="viewHandleToggle">
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
        통제
        <span v-if="'descendantLeafCount' in node">{{ node.descendantLeafCount }}</span>
      </span>
    </div>

    <!--
      leaf 행 — Phase 5-14i 폴리싱 (P2/P3/P10/P14):
        P2: pending 우선 amber, 펼침 우선 blue. 좌측 3px 바 (CSS ::before).
        P3: progress bar 두께 4px (h-1) + transition .3s (duration-300).
        P10: leaf-chev hover 색 변화 (text-gray-300 → -gray-500), expanded → blue-500.
        P14: highlightFn 으로 코드 + 이름 substring amber 하이라이트 (v-html).

      v15 Phase 5-15a (hybrid):
        P20.v6: hasChildren 일 때 좌측 chev 12px SVG + padding-left 16px 깊이.
        chev 클릭 = 'category' 시그널 트릭 (자식 펼침), 행 클릭 = 'control' (증빙 펼침).
    -->
    <div
      v-else
      :class="[
        'row-view leaf-row group cursor-pointer',
        hasChildren ? 'has-children' : '',
        viewChildrenExpanded ? 'children-expanded' : '',
        hasChildren && !viewExpanded ? 'children-collapsed' : '',
        viewHasPendingReview ? 'has-pending' : '',
        isLeafExpanded ? 'expanded' : '',
      ]"
      :style="{ paddingLeft: `${hasChildren ? indentPx + 32 : indentPx + 16}px` }"
      @click="viewHandleToggle">
      <!--
        v15 Phase 5-15a (P20.v6) — hybrid 좌측 chev.
        행 클릭과 분리 (.stop). collapsed 상태는 CSS .children-collapsed 가 -90deg.
        a11y: role/tabindex/aria-expanded.
      -->
      <span
        v-if="hasChildren"
        class="leading-chev"
        :style="{ left: `${indentPx + 14}px` }"
        role="button"
        tabindex="0"
        :aria-label="viewExpanded ? '자식 노드 접기' : '자식 노드 펼치기'"
        :aria-expanded="viewExpanded"
        @click.stop="viewHandleToggleChildren"
        @keydown.enter.prevent="viewHandleToggleChildren"
        @keydown.space.prevent="viewHandleToggleChildren">
        <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" width="12" height="12" aria-hidden="true">
          <path d="M4 6l4 4 4-4"/>
        </svg>
      </span>
      <span
        class="ctrl-code-view font-mono text-[11.5px] text-blue-600 tabular-nums w-16 shrink-0"
        v-html="renderCode"
      ></span>
      <span
        class="ctrl-name-view text-[13px] text-gray-900 flex-1 truncate min-w-0"
        v-html="renderName"
      ></span>
      <span class="text-[11px] text-gray-500 tabular-nums w-14 text-right shrink-0">
        증빙 {{ node.evidenceTypeCount ?? 0 }}
      </span>
      <div class="flex items-center gap-1.5 w-24 shrink-0">
        <span class="text-[11px] text-gray-500 tabular-nums">
          {{ node.collectedCount ?? 0 }}/{{ node.evidenceTypeCount ?? 0 }}
        </span>
        <div class="flex-1 h-1 bg-gray-200 rounded-full overflow-hidden">
          <div
            :class="['h-full rounded-full transition-all duration-300 ease-out',
              viewLeafStatus() === '완료' ? 'bg-green-500' : 'bg-blue-500']"
            :style="{ width: `${viewProgressPercent}%` }">
          </div>
        </div>
      </div>
      <span :class="[
        'inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-medium shrink-0',
        viewStatusBadgeClass,
      ]">
        <span v-if="viewHasPendingReview" class="pending-badge-dot"></span>
        {{ viewHasPendingReview ? `검토 대기 ${node.pendingReviewCount ?? 0}` : viewLeafStatus() }}
      </span>
      <i :class="['leaf-chev pi text-[10px] ml-2 transition-all duration-150',
        isLeafExpanded ? 'pi-chevron-down' : 'pi-chevron-right']"></i>
    </div>

    <!--
      leaf 펼침 패널 — Phase 5-14i 폴리싱: leaf row 가 (indentPx + 16) 이므로 펼침 패널은
      그보다 더 안쪽 (+ 32) 으로 들여서 evidence 카드가 명확히 종속 표시.
    -->
    <div
      v-if="isLeafExpanded && controlDetail"
      class="leaf-expanded bg-blue-50/40 border-t border-blue-100"
      :style="{ paddingLeft: `${indentPx + 32}px` }">
      <div v-if="detailLoading" class="px-4 py-6 text-center text-gray-400 text-sm">
        <i class="pi pi-spin pi-spinner mr-2"></i> 로딩 중...
      </div>
      <div v-else>
        <!-- evidence 카드 -->
        <div
          v-for="et in controlDetail.evidenceTypes"
          :key="et.id"
          class="flex items-center gap-3 py-2 pr-4 border-b border-gray-100 group/et hover:bg-white">
          <i class="pi pi-file text-gray-400 text-xs shrink-0"></i>
          <button
            type="button"
            class="flex-1 text-left text-sm text-gray-900 hover:text-blue-700 truncate"
            @click.stop="viewOnGoEt(et)">
            {{ et.name }}
          </button>
          <span
            v-if="et.collected"
            class="text-[10.5px] px-2 py-0.5 bg-green-100 text-green-700 rounded-full shrink-0">
            수집
          </span>
          <span
            v-else
            class="text-[10.5px] px-2 py-0.5 bg-gray-100 text-gray-500 rounded-full shrink-0">
            미수집
          </span>
          <button
            type="button"
            class="opacity-0 group-hover/et:opacity-100 text-gray-400 hover:text-red-500 px-1 transition-opacity"
            title="증빙 유형 삭제"
            @click.stop="viewOnDeleteEt(et)">
            <i class="pi pi-trash text-xs"></i>
          </button>
        </div>
        <!-- empty -->
        <div
          v-if="controlDetail.evidenceTypes.length === 0"
          class="py-4 px-4 text-xs text-gray-400">
          이 통제에 등록된 증빙 유형이 없습니다.
        </div>
        <!-- 액션바 -->
        <div class="flex items-center gap-2 py-2 pr-4">
          <button
            type="button"
            class="text-xs text-blue-600 hover:text-blue-700 flex items-center gap-1"
            @click.stop="viewOnAddEt">
            <i class="pi pi-plus text-[10px]"></i> 증빙 유형
          </button>
          <span class="text-gray-300">·</span>
          <button
            type="button"
            class="text-xs text-gray-600 hover:text-gray-900 flex items-center gap-1"
            @click.stop="viewOnZipDownload">
            <i class="pi pi-download text-[10px]"></i> ZIP 다운로드
          </button>
        </div>
      </div>
    </div>

    <!--
      v15 Phase 5-15a (P20.v6) — hybrid leaf 의 자식 재귀.
      좌측 chev 클릭 (viewChildrenExpanded === true) 시 자식 트리 표시.
      category 자식 재귀와 같은 props/emits 패턴 (forwarding 동일).
    -->
    <div v-if="node.nodeType === 'control' && viewChildrenExpanded">
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
        @add-evidence-type="forwardAddEt"
        @zip-download="forwardZip"
        @delete-evidence-type="forwardDeleteEt"
      />
    </div>

    <!-- 자식 재귀 (view) — Stage 3 props 전달 (P12/P14) -->
    <div v-if="node.nodeType === 'category' && viewExpanded">
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
        @add-evidence-type="forwardAddEt"
        @zip-download="forwardZip"
        @delete-evidence-type="forwardDeleteEt"
      />
      <!--
        P6 (5-14i v2) — empty CTA. category 가 자식 0 일 때 "통제 항목 없음" 점선 행.
        분류 안 통제 0 인 신규 framework / import 직후 발견성 향상.
      -->
      <div
        v-if="(node.children as TreeRootNode[]).length === 0"
        class="empty-cta-row"
        :style="{ paddingLeft: `${indentPx + 16}px` }">
        <i class="pi pi-info-circle text-[10px] text-gray-300 mr-1.5"></i>
        <span>이 분류에 통제 항목이 없습니다 ·</span>
        <strong class="text-blue-500 ml-1">[통제 관리]</strong>
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
        class="row-code-input cat-code-input"
        :value="node.code"
        placeholder="1"
        spellcheck="false"
        @input="dialogHandleCodeInput"
        @focus="dialogHandleCodeFocus"
        @blur="dialogHandleCodeBlur"
        @keydown.enter.prevent="dialogHandleEnterKey"
        @keydown.tab="dialogHandleTabKey"
        @keydown.esc.prevent="dialogHandleEscKey"
        @keydown.backspace="dialogHandleBackspaceKey"
      />
      <input
        ref="nameInputRef"
        :class="['row-name-input cat-name-input', nameWeightClass]"
        :value="node.name"
        placeholder="분류 이름"
        spellcheck="false"
        @input="dialogHandleNameInput"
        @keydown.enter.prevent="dialogHandleEnterKey"
        @keydown.tab="dialogHandleTabKey"
        @keydown.esc.prevent="dialogHandleEscKey"
        @keydown.backspace="dialogHandleBackspaceKey"
      />
      <span class="cat-meta-dialog">
        통제
        <span v-if="dialogNode">{{ dialogNode.descendantLeafCount }}</span>
      </span>
      <span v-if="dialogShowDirtyDot" class="dirty-dot" title="미저장 변경"></span>
      <span
        v-if="dialogHasValidationErrors"
        class="error-dot"
        :title="dialogValidationErrorTitle"></span>
      <div class="row-actions">
        <button type="button" class="row-iconbtn" title="자식 분류 추가"
          @click="dialogHandleAddChildCategory">
          <i class="pi pi-folder-plus"></i>
        </button>
        <button type="button" class="row-iconbtn" title="자식 통제 추가"
          @click="dialogHandleAddChildControl">
          <i class="pi pi-plus"></i>
        </button>
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

    <!-- leaf 행 -->
    <div
      v-else
      class="row-dialog control-row-dialog group"
      :class="{ 'is-draft-row': isDraft }"
      :style="{ paddingLeft: `${indentPx + 20}px` }">
      <input
        ref="codeInputRef"
        class="row-code-input ctrl-code-input"
        :value="node.code"
        placeholder="1.1.1"
        spellcheck="false"
        @input="dialogHandleCodeInput"
        @focus="dialogHandleCodeFocus"
        @blur="dialogHandleCodeBlur"
        @keydown.enter.prevent="dialogHandleEnterKey"
        @keydown.tab="dialogHandleTabKey"
        @keydown.esc.prevent="dialogHandleEscKey"
        @keydown.backspace="dialogHandleBackspaceKey"
      />
      <input
        ref="nameInputRef"
        class="row-name-input ctrl-name-input"
        :value="node.name"
        placeholder="통제 이름"
        spellcheck="false"
        @input="dialogHandleNameInput"
        @keydown.enter.prevent="dialogHandleEnterKey"
        @keydown.tab="dialogHandleTabKey"
        @keydown.esc.prevent="dialogHandleEscKey"
        @keydown.backspace="dialogHandleBackspaceKey"
      />
      <span class="ctrl-evidence-dialog">· 증빙 {{ node.evidenceTypeCount ?? 0 }}</span>
      <span v-if="dialogShowDirtyDot" class="dirty-dot" title="미저장 변경"></span>
      <span
        v-if="dialogHasValidationErrors"
        class="error-dot"
        :title="dialogValidationErrorTitle"></span>
      <div class="row-actions">
        <button type="button" class="row-iconbtn" title="형제 통제 추가"
          @click="dialogHandleAddSiblingControl">
          <i class="pi pi-plus"></i>
        </button>
        <button type="button" class="row-iconbtn row-iconbtn-danger" title="삭제"
          @click="dialogHandleRequestDelete">
          <i class="pi pi-trash"></i>
        </button>
      </div>
    </div>

    <!-- 자식 재귀 (dialog) -->
    <div v-if="node.nodeType === 'category' && dialogExpanded">
      <ControlNodeRow
        v-for="child in (node.children as UnifiedNode[])"
        :key="(child as UnifiedNode)._key"
        :node="child"
        mode="dialog"
        @leaf-code-blur="forwardLeafCodeBlur"
        @tab-on-existing="forwardTabOnExisting"
        @request-move="forwardRequestMove"
        @request-delete="forwardRequestDelete"
      />
      <!-- 카테고리 끝의 [+ 통제 추가] / [+ 자식 분류 추가] -->
      <div
        class="add-row"
        :style="{ paddingLeft: `${indentPx + 20}px` }"
        @click="dialogHandleAddChildControl">
        <i class="pi pi-plus"></i>
        <span>통제 추가</span>
        <span v-if="tree && dialogNode" class="next-code">
          {{ tree.nextSiblingCode(dialogNode) }} · 다음 번호 자동
        </span>
      </div>
      <div
        class="add-row add-row-category"
        :style="{ paddingLeft: `${indentPx + 20}px` }"
        @click="dialogHandleAddChildCategory">
        <i class="pi pi-folder-plus"></i>
        <span>자식 분류 추가</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ─────────────────────── view 모드 (Phase 5-14i 폴리싱 적용) ─────────────────────── */
.control-node-row-view {
  position: relative;
}

/*
 * P13 (5-14i v3) — 필터/검색 비매치 dim 처리.
 *   spec §3.3.1.4 line 423 "회색 처리, 숨김 X" 정합.
 *   v6 mockup: opacity 0.35 → hover 0.6.
 */
.control-node-row-view.dimmed {
  opacity: 0.35;
  transition: opacity 0.15s;
}
.control-node-row-view.dimmed:hover {
  opacity: 0.6;
}

.row-view {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px 8px 0;
  font-size: 13px;
  user-select: none;
  border-bottom: 1px solid rgb(249 250 251);
  position: relative;
  transition: background-color 0.12s;
}

.row-view.category-row {
  border-bottom-color: rgb(243 244 246);
}

/*
 * P2 (5-14i v2) — pending vs expanded 색 분리 + 좌측 3px bar.
 *   pending  → bg amber-50 (#fffbeb) + 좌 3px amber-400 (#fbbf24)
 *   expanded → bg blue-50  (#eff6ff) + 좌 3px blue-500  (#3b82f6)
 *   pending + expanded 동시 → expanded 우선 (블루 톤)
 */
.row-view.leaf-row.has-pending {
  background-color: rgb(255 251 235); /* amber-50 */
}
.row-view.leaf-row.has-pending:hover {
  background-color: rgb(254 246 224);
}
.row-view.leaf-row.has-pending::before {
  content: "";
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background-color: rgb(251 191 36); /* amber-400 */
}

.row-view.leaf-row.expanded {
  background-color: rgb(239 246 255); /* blue-50 */
}
.row-view.leaf-row.expanded:hover {
  background-color: rgb(228 240 255);
}
.row-view.leaf-row.expanded::before {
  content: "";
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background-color: rgb(59 130 246); /* blue-500 */
}

.row-view.leaf-row:not(.has-pending):not(.expanded):hover {
  background-color: rgb(249 250 251);
}

/*
 * P2 (5-14i v2) — 검토 대기 status 배지 amber 톤 (pending 강조).
 *   v6 mockup .badge-pending: bg amber-100 / color amber-700 / weight 600 / dot amber-500.
 */
.row-view .badge-pending {
  background-color: rgb(254 243 199); /* amber-100 */
  color: rgb(180 83 9);                /* amber-700 */
  font-weight: 600;
}
.row-view .pending-badge-dot {
  width: 5px;
  height: 5px;
  border-radius: 9999px;
  background-color: rgb(245 158 11); /* amber-500 */
  flex-shrink: 0;
}

/*
 * P10 (5-14i v2) — leaf chevron hover 색 강화 + expanded 시 blue.
 *   default: gray-300 / hover: gray-500 / expanded: blue-500 + rotate(90deg) (이미 pi-chevron-down 으로 표현)
 */
.row-view.leaf-row .leaf-chev {
  color: rgb(209 213 219); /* gray-300 */
}
.row-view.leaf-row:hover .leaf-chev {
  color: rgb(107 114 128); /* gray-500 */
}
.row-view.leaf-row.expanded .leaf-chev {
  color: rgb(59 130 246); /* blue-500 */
}

/* ─────────────────────── v15 Phase 5-15a — hybrid leaf 의 좌측 chev (P20.v6) ─────────────────────── */
/*
 * spec §3.3.1.9 + 5-14i v6 mockup CSS 정합:
 *   .leaf.has-children: 일반 leaf 보다 padding-left +16px (chev 자리 확보, :style 동적).
 *   .leading-chev: 12px SVG, position absolute, vertical-center.
 *     색  default = #9ca3af (gray-400)
 *     색  hover (행) = #4b5563 (gray-600)
 *     색  hover (chev 직접) = #111827 (gray-900)
 *   .children-collapsed: chev -90deg 회전 (아래 펼침 → 우측 화살표).
 *
 * 행 자체는 일반 leaf 와 동일 톤 (P20.v6: "추가 색·라벨 0").
 * pending / expanded 의 좌측 3px bar 도 그대로 (chev 와 별도 시각 채널).
 */
.row-view.leaf-row.has-children .leading-chev {
  position: absolute;
  top: 50%;
  width: 12px;
  height: 12px;
  transform: translateY(-50%);
  color: rgb(156 163 175); /* gray-400 */
  cursor: pointer;
  transition: transform 0.15s, color 0.15s;
  z-index: 2;
  /* 작은 클릭 패딩 영역 — chev 본체 12px + 외곽 4px 확장 = 20px 클릭 타겟 */
  padding: 4px;
  margin: -4px;
  box-sizing: content-box;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.row-view.leaf-row.has-children:hover .leading-chev {
  color: rgb(75 85 99); /* gray-600 */
}

.row-view.leaf-row.has-children .leading-chev:hover {
  color: rgb(17 24 39); /* gray-900 */
}

/* 키보드 포커스 — a11y */
.row-view.leaf-row.has-children .leading-chev:focus-visible {
  outline: 2px solid rgb(59 130 246); /* blue-500 */
  outline-offset: 2px;
  border-radius: 3px;
}

.row-view.leaf-row.has-children.children-collapsed .leading-chev {
  transform: translateY(-50%) rotate(-90deg);
}

.row-view.leaf-row.has-children .leading-chev svg {
  display: block;
}

/* ─────────────────────── P14 (5-14i) — 검색 매치 substring 하이라이트 ─────────────────────── */
/* highlightFn 이 v-html 로 삽입한 <span class="match-highlight"> 에 적용. scoped 가
   v-html 내용에는 자동 적용되지 않으므로 :deep() 으로 강제. */
.row-view :deep(.match-highlight) {
  background-color: rgb(254 243 199); /* amber-100 */
  color: rgb(146 64 14);              /* amber-800 */
  padding: 1px 2px;
  border-radius: 2px;
  font-weight: 600;
}

/* ─────────────────────── P12 (5-14i) — 카테고리 매치 카운트 핍 ─────────────────────── */
.match-count-pip {
  background-color: rgb(254 243 199); /* amber-100 */
  color: rgb(180 83 9);                /* amber-700 */
  font-size: 10px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 9999px;
  margin-left: 8px;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}

/* ─────────────────────── P19 (5-14i) — 첫 매치 focus-flash 애니메이션 ─────────────────────── */
@keyframes focusFlash {
  0%   { box-shadow: inset 0 0 0 2px rgba(251, 191, 36, 0); background-color: transparent; }
  20%  { box-shadow: inset 0 0 0 2px rgb(251 191 36); background-color: rgb(255 251 235); }
  100% { box-shadow: inset 0 0 0 2px rgba(251, 191, 36, 0); background-color: transparent; }
}
.row-view.focus-flash {
  animation: focusFlash 1.4s ease-out;
}

/* ─────────────────────── P6 (5-14i) — empty CTA (분류 안 통제 0) ─────────────────────── */
.empty-cta-row {
  display: flex;
  align-items: center;
  padding: 8px 16px;
  border-bottom: 1px dashed rgb(229 231 235);
  font-size: 12px;
  color: rgb(107 114 128);
  cursor: default;
  background-color: rgb(250 251 252);
}

.leaf-expanded {
  font-size: 13px;
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
  padding: 6px 16px 6px 0;
  font-size: 13px;
  border-radius: 4px;
  user-select: none;
}

.row-dialog.category-row-dialog:hover,
.row-dialog.control-row-dialog:hover {
  background-color: rgb(249 250 251);
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

.row-code-input,
.row-name-input {
  border: 1.5px solid transparent;
  border-radius: 4px;
  padding: 0 6px;
  height: 24px;
  background: transparent;
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
  width: 56px;
  flex-shrink: 0;
}

.ctrl-code-input {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11.5px;
  color: rgb(37 99 235);
  width: 64px;
  flex-shrink: 0;
}

.cat-name-input,
.ctrl-name-input {
  flex: 1;
  min-width: 0;
  color: rgb(17 24 39);
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

.add-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 16px 5px 30px;
  color: rgb(156 163 175);
  font-size: 12px;
  cursor: pointer;
  border-radius: 4px;
}

.add-row:hover {
  color: rgb(37 99 235);
  background: rgb(249 250 251);
}

.add-row .next-code {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
  color: rgb(209 213 219);
  margin-left: auto;
}

.add-row:hover .next-code {
  color: rgb(147 197 253);
}

.add-row-category {
  margin-bottom: 4px;
}
</style>