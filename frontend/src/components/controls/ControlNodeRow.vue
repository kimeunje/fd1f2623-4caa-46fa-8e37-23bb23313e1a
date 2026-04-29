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
}

const props = withDefaults(defineProps<Props>(), {
  expandedIds: () => new Set<number>(),
  expandedLeafId: null,
  controlDetail: null,
  detailLoading: false,
  dimmed: false,
  isRoot: false,
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
// 공통 — depth 별 indent + font weight
// ============================================================================
const indentPx = computed<number>(() => 16 + 20 * (props.node.depth - 1))

const nameWeightClass = computed<string>(() => {
  switch (props.node.depth) {
    case 1: return 'text-sm font-bold'
    case 2: return 'text-sm font-semibold'
    case 3: return 'text-sm font-medium'
    case 4: return 'text-[13px] font-medium'
    case 5: return 'text-[12.5px] font-medium'
    default: return 'text-[12px] font-medium'
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
    <!-- 카테고리 행 -->
    <div
      v-if="node.nodeType === 'category'"
      class="row-view category-row-view group cursor-pointer hover:bg-gray-50"
      :style="{ paddingLeft: `${indentPx}px` }"
      @click="viewHandleToggle">
      <i :class="['pi text-xs text-gray-400 transition-transform duration-150 mr-2',
        viewExpanded ? 'pi-chevron-down' : 'pi-chevron-right']"></i>
      <span class="cat-code-view font-mono text-[11px] text-gray-500 tabular-nums mr-2">
        {{ node.code }}
      </span>
      <span :class="['cat-name-view text-gray-900 flex-1 truncate', nameWeightClass]">
        {{ node.name }}
      </span>
      <span class="text-[11px] text-gray-400 tabular-nums shrink-0 ml-2">
        통제
        <span v-if="'descendantLeafCount' in node">{{ node.descendantLeafCount }}</span>
      </span>
    </div>

    <!-- leaf 행 -->
    <div
      v-else
      :class="[
        'row-view control-row-view group cursor-pointer hover:bg-gray-50',
        viewHasPendingReview ? 'bg-blue-50/50 hover:bg-blue-50/70' : '',
        isLeafExpanded ? 'bg-gray-50/50' : '',
      ]"
      :style="{ paddingLeft: `${indentPx + 20}px` }"
      @click="viewHandleToggle">
      <span class="ctrl-code-view font-mono text-[11.5px] text-blue-600 tabular-nums w-16 shrink-0">
        {{ node.code }}
      </span>
      <span class="ctrl-name-view text-sm text-gray-900 flex-1 truncate min-w-0">
        {{ node.name }}
      </span>
      <span class="text-[11px] text-gray-500 tabular-nums w-14 text-right shrink-0">
        증빙 {{ node.evidenceTypeCount ?? 0 }}
      </span>
      <div class="flex items-center gap-1.5 w-24 shrink-0">
        <span class="text-[11px] text-gray-500 tabular-nums">
          {{ node.collectedCount ?? 0 }}/{{ node.evidenceTypeCount ?? 0 }}
        </span>
        <div class="flex-1 h-1 bg-gray-200 rounded-full overflow-hidden">
          <div
            :class="['h-full rounded-full transition-all duration-200',
              viewLeafStatus() === '완료' ? 'bg-green-500' : 'bg-blue-500']"
            :style="{ width: `${viewProgressPercent}%` }">
          </div>
        </div>
      </div>
      <span :class="[
        'inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10.5px] font-medium shrink-0',
        viewStatusBadgeClass,
      ]">
        <span v-if="viewHasPendingReview" class="w-1 h-1 rounded-full bg-blue-600"></span>
        {{ viewHasPendingReview ? `검토 ${node.pendingReviewCount ?? 0}` : viewLeafStatus() }}
      </span>
      <i :class="['pi text-[10px] text-gray-400 ml-2',
        isLeafExpanded ? 'pi-chevron-down' : 'pi-chevron-right']"></i>
    </div>

    <!-- leaf 펼침 패널 (evidence 카드 + 액션바) -->
    <div
      v-if="isLeafExpanded && controlDetail"
      class="leaf-expanded bg-gray-50/40 border-t border-gray-100"
      :style="{ paddingLeft: `${indentPx + 40}px` }">
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

    <!-- 자식 재귀 (view) -->
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
        @toggle-expand="forwardToggle"
        @go-evidence-type="forwardGoEt"
        @add-evidence-type="forwardAddEt"
        @zip-download="forwardZip"
        @delete-evidence-type="forwardDeleteEt"
      />
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
/* ─────────────────────── view 모드 ─────────────────────── */
.control-node-row-view {
  position: relative;
}

.control-node-row-view.dimmed {
  opacity: 0.4;
}

.row-view {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px 8px 0;
  font-size: 13px;
  user-select: none;
  border-bottom: 1px solid rgb(243 244 246);
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