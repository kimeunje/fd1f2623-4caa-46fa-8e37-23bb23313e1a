<script setup lang="ts">
/**
 * Phase 5-14g + 5-14h — UnifiedControlsDialog.vue
 *
 * 5-14g 인터페이스 보존:
 *   - props: open (v-model), treeState, frameworkName
 *   - emits: update:open
 *
 * 5-14h 추가:
 *   - 인라인 편집 (코드/이름 input — ControlNodeRow 가 처리)
 *   - 카테고리/leaf hover 액션
 *   - 헤더 메타 (Framework · 분류 N · 통제 M · 미저장 변경 K) — Q4=B+헤더
 *   - 푸터 [취소] [변경 저장 (N)] + 키보드 힌트
 *   - 저장 흐름: 200/409/422 분기
 *   - 코드 변경 사전 경고 (impact-summary)
 *   - 이동 다이얼로그 (⋯ 액션)
 *   - 자손 cascading 삭제 confirm
 *   - 글로벌 ⌘S / ⌘F / Esc
 *   - dirty 있는 상태로 닫기 시 confirm + beforeunload 가드
 *   - emits 추가: 'saved' (저장 성공)
 *
 * provide(CONTROL_TREE_INJECTION_KEY, treeState) — ControlNodeRow 가 inject
 */

import { computed, nextTick, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue'
import { treeApi } from '@/services/evidenceApi'
import {
  CONTROL_TREE_INJECTION_KEY,
  type ControlTreeApi,
  type UnifiedNode,
} from '@/composables/useControlTree'
import type { ImpactSummary, TreeValidationDetail } from '@/types/evidence'
import ControlNodeRow from '@/components/controls/ControlNodeRow.vue'
import ControlCodeChangeWarningDialog from '@/components/controls/ControlCodeChangeWarningDialog.vue'
import MoveNodeDialog from '@/components/controls/MoveNodeDialog.vue'
import TreeConflictDialog from '@/components/controls/TreeConflictDialog.vue'

interface Props {
  open: boolean
  treeState: ControlTreeApi
  frameworkName: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:open': [v: boolean]
  /** 5-14h 신규 — 저장 성공 (createdCount = tempId 매핑 수, 부모가 toast 등) */
  saved: [payload: { newVersion: number; createdCount: number }]
}>()

provide(CONTROL_TREE_INJECTION_KEY, props.treeState)

// ============================================================================
// 검색 input ref (⌘F 포커스용)
// ============================================================================
const searchInputRef = ref<HTMLInputElement | null>(null)

function handleDialogSearchInput(e: Event): void {
  props.treeState.dialogSearch.value = (e.target as HTMLInputElement).value
}

// ============================================================================
// Export / Close
// ============================================================================
const isExporting = ref(false)
async function handleExport(): Promise<void> {
  if (!props.treeState.framework.value) return
  if (isExporting.value) return
  isExporting.value = true
  try {
    await treeApi.exportFramework(
      props.treeState.framework.value.id,
      `${props.frameworkName}.xlsx`,
    )
  } catch (err) {
    console.error('[UnifiedControlsDialog] export failed', err)
  } finally {
    isExporting.value = false
  }
}

function handleCloseRequest(): void {
  if (props.treeState.hasDirty.value) {
    const ok = window.confirm(
      `미저장 변경 ${props.treeState.dirtyCount.value}건이 있습니다. 변경사항을 버리고 닫을까요?`,
    )
    if (!ok) return
    props.treeState.discardAllDirty()
  }
  props.treeState.dialogSearch.value = ''
  emit('update:open', false)
}

function handleCancelClick(): void {
  handleCloseRequest()
}

// ============================================================================
// 저장 흐름
// ============================================================================
const conflictData = ref<{ currentVersion: number } | null>(null)

async function handleSaveClick(): Promise<void> {
  if (!props.treeState.hasDirty.value) return
  if (props.treeState.isSaving.value) return
  const result = await props.treeState.saveTree()
  if (result.ok) {
    emit('saved', {
      newVersion: result.newVersion,
      createdCount: result.mappings.size,
    })
    return
  }
  if (result.kind === 'conflict') {
    conflictData.value = { currentVersion: result.currentVersion }
    return
  }
  if (result.kind === 'validation') {
    showValidationToast(result.details)
    return
  }
  window.alert(result.message || '저장에 실패했습니다')
}

async function handleConflictReload(): Promise<void> {
  conflictData.value = null
  await props.treeState.load()
}

function handleConflictDismiss(): void {
  conflictData.value = null
}

// ============================================================================
// validation toast
// ============================================================================
const validationToast = ref<string | null>(null)
let validationToastSeq = 0
function showValidationToast(details: TreeValidationDetail[]): void {
  validationToastSeq++
  const seq = validationToastSeq
  validationToast.value = `${details.length}건의 검증 오류가 있습니다. 빨간 표시된 행을 확인해 주세요.`
  setTimeout(() => {
    if (seq === validationToastSeq) validationToast.value = null
  }, 6000)
}

// ============================================================================
// 코드 변경 경고
// ============================================================================
const codeWarning = ref<{
  node: UnifiedNode
  oldCode: string
  newCode: string
  impact: ImpactSummary
} | null>(null)

async function handleLeafCodeBlur(payload: {
  node: UnifiedNode
  oldCode: string
  newCode: string
}): Promise<void> {
  if (payload.node._kind !== 'existing') return
  if (payload.node.nodeType !== 'control') return
  if (payload.oldCode === payload.newCode) return
  try {
    const impact = await props.treeState.fetchImpactSummary(payload.node.id)
    // v15.7 Q2=A: legacy alias 3 필드 제거. own + descendant 합산 (spec §3.3.1.5).
    //             hybrid 모델에서 leaf 코드 변경의 자손 영향까지 포함한 임계값 판정.
    const sum = impact.ownEvidenceFileCount + impact.descendantEvidenceFileCount
              + impact.ownJobCount         + impact.descendantJobCount
              + impact.ownReviewCount      + impact.descendantReviewCount
    if (sum > 0) {
      codeWarning.value = { ...payload, impact }
    }
  } catch (err) {
    console.warn('[UnifiedControlsDialog] impact-summary 호출 실패, 경고 skip', err)
  }
}

function handleCodeWarningConfirm(): void {
  codeWarning.value = null
}

function handleCodeWarningCancel(): void {
  if (codeWarning.value) {
    props.treeState.setCode(codeWarning.value.node, codeWarning.value.oldCode)
  }
  codeWarning.value = null
}

// ============================================================================
// 이동
// ============================================================================
const moveTarget = ref<UnifiedNode | null>(null)

function handleRequestMove(node: UnifiedNode): void {
  moveTarget.value = node
}

function handleMoveConfirm(payload: { node: UnifiedNode; newParent: UnifiedNode }): void {
  try {
    props.treeState.moveNode(payload.node, payload.newParent)
  } catch (err) {
    window.alert((err as Error).message)
  } finally {
    moveTarget.value = null
  }
}

function handleMoveCancel(): void {
  moveTarget.value = null
}

// ============================================================================
// 삭제 (자손 cascading confirm)
// ============================================================================
function handleRequestDelete(node: UnifiedNode): void {
  if (node._kind === 'draft') {
    props.treeState.deleteNode(node)
    return
  }
  const counts = props.treeState.countDescendants(node)
  let msg = ''
  if (counts.total === 0) {
    msg = node.nodeType === 'category'
      ? `분류 "${node.name}" 을(를) 삭제하시겠습니까?`
      : `항목 "${node.code} ${node.name}" 을(를) 삭제하시겠습니까?\n\n(이 항목에 연결된 증빙도 함께 삭제됩니다)`
  } else {
    msg =
      `"${node.name}" 을(를) 삭제하면 다음도 함께 삭제됩니다:\n` +
      `· 자식 분류 ${counts.categories}개\n` +
      `· 자식 항목 ${counts.controls}개\n` +
      `· 그에 매달린 모든 증빙\n\n계속하시겠습니까?`
  }
  if (window.confirm(msg)) {
    props.treeState.deleteNode(node)
  }
}

// ============================================================================
// 새 대분류 추가
// ============================================================================
async function handleAddRootCategory(): Promise<void> {
  const draft = props.treeState.createRootCategory()
  await nextTick()
  const el = document.querySelector<HTMLInputElement>(
    `[data-temp-id="${draft.tempId}"] input.row-name-input`,
  )
  el?.focus()
}

// ============================================================================
// 글로벌 키
// ============================================================================
function handleGlobalKey(e: KeyboardEvent): void {
  if (!props.open) return
  const isMeta = e.metaKey || e.ctrlKey
  if (isMeta && e.key.toLowerCase() === 's') {
    e.preventDefault()
    handleSaveClick()
    return
  }
  if (isMeta && e.key.toLowerCase() === 'f') {
    e.preventDefault()
    nextTick(() => searchInputRef.value?.focus())
    return
  }
  if (e.key === 'Escape') {
    const t = e.target as HTMLElement
    if (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA') return
    e.preventDefault()
    handleCloseRequest()
  }
}

function handleBeforeUnload(e: BeforeUnloadEvent): void {
  if (props.open && props.treeState.hasDirty.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleGlobalKey)
  window.addEventListener('beforeunload', handleBeforeUnload)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleGlobalKey)
  window.removeEventListener('beforeunload', handleBeforeUnload)
})

// ============================================================================
// open watcher — 열릴 때 dialog 검색 / 검증 에러 reset
// ============================================================================
watch(
  () => props.open,
  (v) => {
    if (v) {
      props.treeState.dialogSearch.value = ''
      props.treeState.validationErrors.value = []
    }
  },
)

// ============================================================================
// 표시용 메타
// ============================================================================
const headerSubtitle = computed<string>(() => {
  const cats = props.treeState.dialogCategoryCount.value
  const ctrls = props.treeState.dialogControlCount.value
  const dirty = props.treeState.dirtyCount.value
  const parts = [props.frameworkName, `분류 ${cats}`, `항목 ${ctrls}`]
  if (dirty > 0) parts.push(`미저장 변경 ${dirty}`)
  return parts.join(' · ')
})

const isEmpty = computed<boolean>(() => props.treeState.dialogRootNodes.value.length === 0)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="dialog-backdrop" @click.self="handleCloseRequest">
      <div class="dialog-shell" role="dialog" aria-labelledby="unified-controls-title">
        <!-- ─────────── 헤더 ─────────── -->
        <header class="dialog-header">
          <div class="header-left">
            <h2 id="unified-controls-title" class="dialog-title">관리 항목</h2>
            <p class="dialog-subtitle">{{ headerSubtitle }}</p>
          </div>
          <div class="header-actions">
            <button type="button" class="header-iconbtn" title="엑셀 Export"
              :disabled="isExporting || isEmpty" @click="handleExport">
              <i :class="['pi', isExporting ? 'pi-spinner pi-spin' : 'pi-arrow-down']"></i>
            </button>
            <button type="button" class="header-iconbtn" title="닫기"
              @click="handleCloseRequest">
              <i class="pi pi-times"></i>
            </button>
          </div>
        </header>

        <!-- ─────────── toast ─────────── -->
        <div v-if="validationToast" class="dialog-toast dialog-toast-error">
          <i class="pi pi-exclamation-triangle"></i> {{ validationToast }}
        </div>
        <!-- v15.1 5-15a 후속-2 — tabToast 폐기 (Tab 의미 변경) -->

        <!-- ─────────── 검색 ─────────── -->
        <div class="dialog-search">
          <i class="pi pi-search search-icon"></i>
          <input
            ref="searchInputRef"
            class="search-input"
            type="text"
            placeholder="코드, 분류, 항목명 검색…"
            :value="treeState.dialogSearch.value"
            spellcheck="false"
            @input="handleDialogSearchInput"
          />
        </div>

        <!-- ─────────── 본문 ─────────── -->
        <div class="dialog-body">
          <div v-if="treeState.loading.value" class="dialog-empty">
            <i class="pi pi-spinner pi-spin"></i> 트리를 불러오는 중…
          </div>
          <div v-else-if="isEmpty" class="dialog-empty empty-cta">
            <i class="pi pi-folder-open empty-icon"></i>
            <p class="empty-title">관리 항목이 아직 없습니다</p>
            <p class="empty-sub">
              분류를 추가하고 관리 항목을 만들어 시작하거나, 엑셀로 한 번에 가져올 수 있습니다.
            </p>
            <div class="empty-actions">
              <button class="btn-primary" type="button" @click="handleAddRootCategory">
                <i class="pi pi-plus"></i> 첫 분류 추가
              </button>
            </div>
          </div>
          <template v-else>
            <ControlNodeRow
              v-for="root in treeState.dialogRootNodes.value"
              :key="root._key"
              :node="root"
              mode="dialog"
              @leaf-code-blur="handleLeafCodeBlur"
              @request-move="handleRequestMove"
              @request-delete="handleRequestDelete"
            />
            <div class="add-root-row" @click="handleAddRootCategory">
              <i class="pi pi-plus"></i>
              <span>새 대분류 추가</span>
            </div>
          </template>
        </div>

        <!-- ─────────── 푸터 (v18 — 키보드 힌트 제거) ─────────── -->
        <footer class="dialog-footer">
          <button type="button" class="btn-secondary" @click="handleCancelClick">
            취소
          </button>
          <button
            type="button"
            class="btn-primary"
            :disabled="!treeState.hasDirty.value || treeState.isSaving.value"
            @click="handleSaveClick">
            <i v-if="treeState.isSaving.value" class="pi pi-spinner pi-spin"></i>
            변경 저장
            <span v-if="treeState.dirtyCount.value > 0" class="dirty-badge">
              {{ treeState.dirtyCount.value }}
            </span>
          </button>
        </footer>
      </div>
    </div>

    <!-- ─────────── 보조 다이얼로그 (z: 70) ─────────── -->
    <ControlCodeChangeWarningDialog
      v-if="codeWarning"
      :node="codeWarning.node"
      :old-code="codeWarning.oldCode"
      :new-code="codeWarning.newCode"
      :impact="codeWarning.impact"
      @confirm="handleCodeWarningConfirm"
      @cancel="handleCodeWarningCancel"
    />
    <MoveNodeDialog
      v-if="moveTarget"
      :node="moveTarget"
      :tree="treeState"
      @confirm="handleMoveConfirm"
      @cancel="handleMoveCancel"
    />
    <TreeConflictDialog
      v-if="conflictData"
      :current-version="conflictData.currentVersion"
      @reload="handleConflictReload"
      @dismiss="handleConflictDismiss"
    />
  </Teleport>
</template>

<style scoped>
.dialog-backdrop {
  position: fixed; inset: 0;
  background: rgba(15, 23, 42, 0.55);
  z-index: 55;
  display: flex; align-items: center; justify-content: center;
  padding: 24px;
}

.dialog-shell {
  width: min(960px, 100%);
  height: min(720px, 100%);
  background: white;
  border-radius: 12px;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
  display: flex; flex-direction: column;
  overflow: hidden;
}

.dialog-header {
  padding: 16px 20px;
  border-bottom: 1px solid rgb(229 231 235);
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 16px; flex-shrink: 0;
}

.header-left { flex: 1; min-width: 0; }
.dialog-title { font-size: 16px; font-weight: 600; color: rgb(17 24 39); margin: 0 0 4px 0; }
.dialog-subtitle { font-size: 12px; color: rgb(107 114 128); margin: 0; font-variant-numeric: tabular-nums; }

.header-actions { display: flex; gap: 4px; flex-shrink: 0; }
.header-iconbtn {
  width: 32px; height: 32px;
  border: none; background: transparent; border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center;
  cursor: pointer; color: rgb(107 114 128);
}
.header-iconbtn:hover:not(:disabled) {
  background: rgb(243 244 246); color: rgb(17 24 39);
}
.header-iconbtn:disabled { opacity: 0.4; cursor: not-allowed; }

.dialog-toast {
  margin: 8px 20px 0 20px;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 12px;
  display: flex; align-items: center; gap: 8px;
  flex-shrink: 0;
}
.dialog-toast-error {
  background: rgb(254 242 242);
  color: rgb(153 27 27);
  border: 1px solid rgb(252 165 165);
}
.dialog-toast-info {
  background: rgb(239 246 255);
  color: rgb(30 64 175);
  border: 1px solid rgb(147 197 253);
}

.dialog-search {
  padding: 12px 20px;
  border-bottom: 1px solid rgb(243 244 246);
  display: flex; align-items: center; gap: 8px;
  flex-shrink: 0;
}
.search-icon { color: rgb(156 163 175); font-size: 12px; }
.search-input {
  flex: 1; border: none; outline: none;
  font-size: 13px; height: 28px; background: transparent;
}

.dialog-body {
  flex: 1; overflow-y: auto;
  padding: 0;
}
.dialog-empty {
  text-align: center; padding: 48px 24px;
  color: rgb(107 114 128); font-size: 13px;
}
.empty-cta {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
}
.empty-icon { font-size: 32px; color: rgb(209 213 219); }
.empty-title { font-size: 14px; font-weight: 500; color: rgb(55 65 81); margin: 8px 0 0 0; }
.empty-sub { font-size: 12px; color: rgb(107 114 128); margin: 0 0 8px 0; }
.empty-actions { display: flex; gap: 8px; }

.add-root-row {
  display: flex; align-items: center; gap: 8px;
  padding: 12px 20px;
  margin: 6px 0 0 0;
  border-top: 1px dashed rgb(229 231 235);
  color: rgb(107 114 128);
  font-size: 13px; font-weight: 500;
  cursor: pointer;
}
.add-root-row:hover {
  background: rgb(249 250 251);
  color: rgb(37 99 235);
}

/* v18: 푸터 — 키보드 힌트 제거, 버튼 우측 정렬 */
.dialog-footer {
  padding: 12px 20px;
  border-top: 1px solid rgb(229 231 235);
  display: flex; align-items: center; justify-content: flex-end;
  gap: 8px;
  background: rgb(249 250 251);
  flex-shrink: 0;
}

.btn-primary {
  height: 32px; padding: 0 14px;
  background: rgb(17 24 39);
  color: white; border: none; border-radius: 6px;
  font-size: 12px; font-weight: 500;
  display: inline-flex; align-items: center; gap: 6px;
  cursor: pointer;
}
.btn-primary:hover:not(:disabled) { background: rgb(31 41 55); }
.btn-primary:disabled { background: rgb(209 213 219); cursor: not-allowed; }

.btn-secondary {
  height: 32px; padding: 0 14px;
  background: white; color: rgb(17 24 39);
  border: 1px solid rgb(229 231 235); border-radius: 6px;
  font-size: 12px; font-weight: 500;
  cursor: pointer;
  display: inline-flex; align-items: center; gap: 6px;
}
.btn-secondary:hover { background: rgb(249 250 251); }

.dirty-badge {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 18px; height: 18px;
  padding: 0 6px;
  background: rgb(59 130 246);
  color: white;
  border-radius: 9999px;
  font-size: 10.5px; font-weight: 600;
  margin-left: 4px;
}
</style>