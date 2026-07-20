<script setup lang="ts">
/**
 * ControlsView (Framework 상세) — Phase 5-14h 편집 모드 진입 (v14.8 final).
 *
 * <p>spec §3.3 정합. v13 의 평면 테이블 (코드/영역/항목명/수집현황/상태/관리 6컬럼) 을
 * 5-14g 에서 N단 재귀 트리로 전환했고, 5-14h 에서 단일 [관리 항목] 다이얼로그가
 * read-only shell → 편집 모드 (dirty 추적 + PATCH /tree 단일 트랜잭션 저장) 로 진입.</p>
 *
 * <h3>5-14h 변경점</h3>
 * <ul>
 *   <li>UnifiedControlsDialog 의 신규 emit `@saved` 처리 → 토스트 + 트리는 다이얼로그
 *       내부에서 자동 reload 되므로 view 측에서는 별도 reload 불요</li>
 *   <li>AddControlDialog / EditControlDialog 사용처 0 — Phase 5-14h 진입 결정 (Q2=A)
 *       으로 본 phase 에서 파일도 함께 삭제됨. 신규 분류/통제 추가는 모두 다이얼로그
 *       편집 모드에서 PATCH /tree 로 처리</li>
 *   <li>v15.6: controlsApi 통째 제거 — controlNodesApi (detail/evidence-types/impact-summary)
 *       + evidenceTypesApi (delete) 로 분리 (Q1=A / Q2=B / Q4=A 결정 정합)</li>
 *   <li>v15.7: router param `:controlId` → `:nodeId` (Q3=B). goToEvidenceTypeDetail
 *       의 router.push params 도 동기 변경 (router/index.ts 정의와 정합)</li>
 * </ul>
 *
 * <h3>5-14g 동작 보존</h3>
 * <ul>
 *   <li>leaf 행 클릭 → 인라인 펼침으로 evidence 카드 목록 (v12 5-12b)</li>
 *   <li>증빙 유형 카드 클릭 → EvidenceTypeDetailView 별도 페이지 이동 (goToEvidenceTypeDetail)</li>
 *   <li>증빙 유형 우측 🗑 → evidenceTypesApi.delete (v15.6 namespace 일치)</li>
 *   <li>[ZIP 다운로드] 버튼 (펼친 leaf 안). 증빙 유형 추가는 [관리 항목] 다이얼로그로 통합 (v15.5)</li>
 *   <li>pending 행 배경 강조 (Phase 5-9)</li>
 *   <li>본문 헤더의 Framework 이름 + 카운트 서브텍스트 (Phase 5-13f)</li>
 *   <li>useControlTree(selectedFrameworkIdRef) 시그니처 + tree.load() / tree.reload() /
 *       tree.searchText / tree.statusFilter 한국어 라벨 / tree.statusCounts /
 *       tree.effectiveExpandedIds / tree.filterActive / tree.isMatched(id) /
 *       tree.toggleExpand(id) 모두 보존</li>
 * </ul>
 *
 * <h3>유지되는 외부 컴포넌트</h3>
 * <ul>
 *   <li>UnifiedControlsDialog — v-model:open / :tree-state / :framework-name /
 *       5-14h 에서 @saved emit 추가</li>
 * </ul>
 */
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import {
  frameworksApi,
  controlNodesApi,
  evidenceTypesApi,
  evidenceFilesApi,
} from '@/services/evidenceApi'
import { useControlTree, type LeafStatus } from '@/composables/useControlTree'
import { notesApi } from '@/services/api'
import type { ControlNodeNote } from '@/types'
import ControlNodeRow from '@/components/controls/ControlNodeRow.vue'
import UnifiedControlsDialog from '@/components/controls/UnifiedControlsDialog.vue'
import NodeNotesDialog from '@/components/controls/NodeNotesDialog.vue'
import { useAuthStore } from '@/stores/auth'
import type {
  Framework,
  ControlDetail,
} from '@/types/evidence'
import type { UnifiedNode, TreeRootNode } from '@/composables/useControlTree'

// ========================================
// Props (v11 Phase 5-3: 라우트 /controls/:frameworkId 에서 전달)
// ========================================
const props = defineProps<{
  frameworkId?: number
}>()

const router = useRouter()
const route = useRoute()

// ========================================
// v18.9.2 — 도착 시 evidence 카드 포커싱 (EvidenceTypeDetailView goBack 진입)
// ========================================
//
// EvidenceTypeDetailView 의 goBack 이 query.expandNodeId + query.focusEvidenceTypeId
// 전달 → onMounted 가 그 leaf 의 ancestors 모두 expand + leaf 인라인 펼침 +
// evidence 카드 포커싱 (border-blue-500 + bg-blue-50 + box-shadow inset). 3초 자동
// 해제 + transition fade out. v18.9.1 의 focusJob 패턴 정합.
const focusedEvidenceTypeId = ref<number | null>(null)

// ========================================
// 프레임워크 상태 (헤더 메타용)
// ========================================
const frameworks = ref<Framework[]>([])
const selectedFrameworkId = ref<number | null>(null)

// 본문 헤더용 — 현재 Framework 메타 (Phase 5-13f)
const currentFramework = computed<Framework | null>(() => {
  if (selectedFrameworkId.value == null) return null
  return frameworks.value.find((f) => f.id === selectedFrameworkId.value) ?? null
})

// ========================================
// 트리 composable — 본 view 의 핵심 상태
// ========================================
const tree = useControlTree(selectedFrameworkId)

// ========================================
// leaf 펼침 (인라인 evidence 카드 표시)
// ========================================
const expandedLeafId = ref<number | null>(null)
const controlDetail = ref<ControlDetail | null>(null)
// v19.27 — 펼쳐진 leaf 의 인수인계 노트. controlDetail 과 같은 수명(펼침 단위).
const notes = ref<ControlNodeNote[]>([])
async function loadNotesFor(nodeId: number) {
  try {
    const { data } = await notesApi.list(nodeId)
    notes.value = data.success ? data.data : []
  } catch {
    notes.value = []
  }
}
const detailLoading = ref(false)

async function toggleLeaf(nodeId: number) {
  if (expandedLeafId.value === nodeId) {
    expandedLeafId.value = null
    controlDetail.value = null
    notes.value = []
    return
  }
  expandedLeafId.value = nodeId
  detailLoading.value = true
  void loadNotesFor(nodeId) // v19.27 — 노트는 controlDetail 과 병행 로드(대기 안 함)
  try {
    const { data } = await controlNodesApi.getDetail(nodeId)
    if (data.success) controlDetail.value = data.data
  } catch (e) {
    console.error(e)
    // v15.6 정상화: 새 endpoint /control-nodes/{id} 호출. 단순 에러 토스트 + 펼침
    // 상태 롤백. v15.5.1 의 안내 메시지 ("v15.6 업데이트에서 정상화 예정") 회수.
    expandedLeafId.value = null
    controlDetail.value = null
    notes.value = []
    showToast('증빙 유형을 불러오지 못했습니다.', 'error')
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  if (!expandedLeafId.value) return
  try {
    const { data } = await controlNodesApi.getDetail(expandedLeafId.value)
    if (data.success) controlDetail.value = data.data
  } catch (e) {
    console.error(e)
    // v15.6 정상화. 기존 controlDetail 보존 (fallback) — 갱신 실패가 화면을 비우지
    // 않도록.
    showToast('증빙 유형을 갱신하지 못했습니다.', 'error')
  }
}

// ========================================
// v18.9.2 — EvidenceTypeDetailView goBack 진입 처리
// ========================================
/**
 * leaf 노드의 ancestors 모두 expand + leaf 인라인 펼침 + evidence 카드 포커싱.
 *
 * 흐름:
 *  1. tree.flatNodes 따라 nodeId 의 ancestors 수집 (parentId chain)
 *  2. tree.expandedIds 에 ancestors 모두 add (Set 재할당으로 reactivity trigger)
 *  3. expandedLeafId 가 다르면 toggleLeaf 호출 (펼침 + controlDetail 로드).
 *     같으면 그대로 (toggleLeaf 의 close 분기 회피)
 *  4. focusedEvidenceTypeId 설정 + nextTick 후 scrollIntoView
 *  5. 3초 후 자동 해제 (그 사이 다른 jobId focus 되었으면 그 신규 focus 보존)
 *
 * @param nodeId          포커싱할 leaf control_node.id
 * @param evidenceTypeId  포커싱할 evidence_type.id (null 시 leaf 펼침만)
 */
async function expandAncestorsAndFocus(nodeId: number, evidenceTypeId: number | null) {
  // ── 1. ancestors 수집 ───────────────────────────────────────
  const nodeMap = new Map(tree.flatNodes.value.map((n) => [n.id, n]))
  const targetNode = nodeMap.get(nodeId)
  if (!targetNode) return

  const ancestorIds: number[] = []
  let cursor = targetNode.parentId
  while (cursor != null) {
    ancestorIds.push(cursor)
    cursor = nodeMap.get(cursor)?.parentId ?? null
  }

  // ── 2. ancestors expand (Set 재할당으로 reactivity trigger) ──
  const newExpanded = new Set(tree.expandedIds.value)
  for (const id of ancestorIds) newExpanded.add(id)
  tree.expandedIds.value = newExpanded

  // ── 3. leaf 인라인 펼침 + controlDetail 로드 ──────────────────
  // toggleLeaf 가 같은 nodeId 면 close 분기 → 회피 (이미 펼쳐 있으면 그대로)
  if (expandedLeafId.value !== nodeId) {
    await toggleLeaf(nodeId)
  }

  // ── 4. evidence 카드 포커싱 + scrollIntoView ────────────────
  if (evidenceTypeId != null) {
    focusedEvidenceTypeId.value = evidenceTypeId
    await nextTick()
    // ControlNodeRow 의 .et-card 에 :data-evidence-type-id attribute 있음 (v18.9.2 추가)
    const card = document.querySelector(
      `.et-card[data-evidence-type-id="${evidenceTypeId}"]`,
    ) as HTMLElement | null
    card?.scrollIntoView({ behavior: 'smooth', block: 'center' })

    // ── 5. 3초 후 자동 해제 ─────────────────────────────────
    setTimeout(() => {
      if (focusedEvidenceTypeId.value === evidenceTypeId) {
        focusedEvidenceTypeId.value = null
      }
    }, 3000)
  }
}

// ========================================
// v18.9.3 — sessionStorage 기반 작업 컨텍스트 보존
// ========================================
//
// 묵시적 재진입 (대시보드/홈 → 관리항목) 시 마지막 펼침 + 스크롤 상태 복원.
// v18.9.2 의 URL query 명시적 deep-link 와 보완 패턴.
//
// 우선순위: URL query > sessionStorage > 기본값 (depth=1 카테고리만 펼침)
// 만료: sessionStorage 는 탭 닫을 때 자동 클리어 (별도 만료 로직 없음)
// 격리: framework 별 key 분리 (다른 framework 펼침 상태가 섞이지 않음)

interface ControlsViewSnapshot {
  expandedIds: number[]
  expandedLeafId: number | null
  scrollY: number
}

function snapshotKey(frameworkId: number): string {
  return `secuhub:controlsView:state:${frameworkId}`
}

function saveSnapshot(): void {
  if (selectedFrameworkId.value == null) return
  const snap: ControlsViewSnapshot = {
    expandedIds: Array.from(tree.expandedIds.value),
    expandedLeafId: expandedLeafId.value,
    scrollY: window.scrollY,
  }
  try {
    sessionStorage.setItem(
      snapshotKey(selectedFrameworkId.value),
      JSON.stringify(snap),
    )
  } catch (e) {
    // Quota / disabled storage — silent ignore (보조 기능, 본 흐름 영향 없음)
    console.warn('[v18.9.3] sessionStorage save failed', e)
  }
}

async function restoreSnapshot(frameworkId: number): Promise<boolean> {
  let raw: string | null = null
  try {
    raw = sessionStorage.getItem(snapshotKey(frameworkId))
  } catch {
    return false
  }
  if (!raw) return false
  let snap: ControlsViewSnapshot
  try {
    snap = JSON.parse(raw)
  } catch {
    return false
  }

  // 트리 펼침 복원 — 실제 존재하는 노드만 (stale id 필터)
  const validIds = new Set(tree.flatNodes.value.map((n) => n.id))
  const restoredExpanded = new Set(snap.expandedIds.filter((id) => validIds.has(id)))
  tree.expandedIds.value = restoredExpanded

  // leaf 인라인 펼침 복원
  if (snap.expandedLeafId != null && validIds.has(snap.expandedLeafId)) {
    await toggleLeaf(snap.expandedLeafId)
  }

  // 스크롤 위치 복원 — DOM 렌더링 + expand transition 끝나기 전 약간 일찍 (자연 효과)
  await nextTick()
  setTimeout(() => {
    window.scrollTo({ top: snap.scrollY, behavior: 'auto' })
  }, 100)

  return true
}

// v18.9.3-fix — scroll event listener (debounced 500ms 저장).
// 사용자가 스크롤만 하고 페이지 떠나도 위치 보존됨.
let scrollSaveTimer: number | null = null
function onScroll() {
  if (scrollSaveTimer != null) clearTimeout(scrollSaveTimer)
  scrollSaveTimer = window.setTimeout(() => saveSnapshot(), 500)
}

// ========================================
// 다이얼로그 — UnifiedControlsDialog (5-14g 신규)
// ========================================
const showUnifiedDialog = ref(false)

// ZIP 다운로드 (leaf 펼침 안)
const zipDownloading = ref(false)

// 알림 토스트
const toast = ref({
  show: false,
  message: '',
  type: 'success' as 'success' | 'error',
})
function showToast(message: string, type: 'success' | 'error' = 'success') {
  toast.value = { show: true, message, type }
  setTimeout(() => {
    toast.value.show = false
  }, 3000)
}

// ─── v19.27 — 인수인계 노트 (본문) ───
// 노트는 자체 API(notesApi)로 즉시 저장 — 트리 dirty/version 과 무관하므로
// v19.23 의 dirty 가드/충돌 처리가 필요 없다. 모달이 CRUD 를 직접 수행한다.
const authStore = useAuthStore()
const authorDefault = computed(() => authStore.user?.name ?? '')
const noteTarget = ref<{ id: number | null; code: string; name: string }>({
  id: null,
  code: '',
  name: '',
})
const noteOpen = ref(false)
function onRequestNotes(node: TreeRootNode | UnifiedNode) {
  // 본문 노드는 모두 서버 존재 노드 → id 보유. code/name 은 RowNode 공통.
  noteTarget.value = { id: node.id ?? null, code: node.code, name: node.name }
  noteOpen.value = true
}
function onNotesChanged() {
  // 모달에서 추가/수정/삭제 시 본문 읽기 패널을 최신화.
  if (noteTarget.value.id != null && expandedLeafId.value === noteTarget.value.id) {
    void loadNotesFor(noteTarget.value.id)
  }
}

// ========================================
// 데이터 로드
// ========================================
async function loadFrameworks() {
  try {
    const { data } = await frameworksApi.list()
    if (data.success && data.data.length > 0) {
      frameworks.value = data.data

      // v11 Phase 5-3: URL 의 frameworkId 우선
      const routeFwId = props.frameworkId
      const matched =
        routeFwId != null ? frameworks.value.find((f) => f.id === routeFwId) : null

      selectedFrameworkId.value = matched ? matched.id : frameworks.value[0].id
      await tree.load()
    }
  } catch (e) {
    console.error(e)
  }
}

// ========================================
// 트리 컨트롤 (검색/필터)
// ========================================
const statusOptions: Array<'전체' | LeafStatus> = [
  '전체',
  '완료',
  '진행중',
  '미수집',
  '검토 대기',
]

function onTreeToggle(nodeId: number, nodeType: 'category' | 'control') {
  if (nodeType === 'category') {
    // 하위 펼침 시 증빙 패널 자동 닫기 (겹침 방지)
    if (expandedLeafId.value === nodeId) {
      expandedLeafId.value = null
      controlDetail.value = null
      notes.value = []
    }
    tree.toggleExpand(nodeId)
    saveSnapshot()   // v18.9.3-fix — 사용자 action 시점 즉시 저장
  } else {
    // 증빙 펼침 시 하위 목록 자동 닫기 (겹침 방지)
    if (tree.effectiveExpandedIds.value.has(nodeId)) {
      tree.toggleExpand(nodeId)
    }
    // v18.9.3-fix — toggleLeaf 완료 후 저장 (expandedLeafId 갱신 시점)
    void toggleLeaf(nodeId).then(() => saveSnapshot())
  }
}

/**
 * v15.6: param 명 controlId → nodeId 정리 (FE 인자).
 * v15.7 Q3=B: router/index.ts 의 path 정의도 동기 변경 (`:controlId` → `:nodeId`).
 *             router.push 의 params key 도 nodeId 로 통일.
 */
function goToEvidenceTypeDetail(evidenceTypeId: number, nodeId: number) {
  if (selectedFrameworkId.value == null) return
  router.push({
    name: 'evidence-type-detail',
    params: {
      frameworkId: selectedFrameworkId.value,
      nodeId,                  // v15.7: router 정의 정합 (Q3=B)
      evidenceTypeId,
    },
  })
}

// ========================================
// 다이얼로그 액션
// ========================================
function openUnifiedDialog() {
  showUnifiedDialog.value = true
}

/**
 * v18 — 증빙 패널 인라인 [증빙 유형 추가].
 * 해당 통제에 새 증빙 유형을 생성하고 트리를 새로고침.
 */
async function onCreateEvidenceType(nodeId: number, name: string) {
  try {
    await evidenceTypesApi.create(nodeId, name)
    showToast(`"${name}" 증빙 유형이 추가되었습니다.`, 'success')
    // 현재 열린 leaf 유지 + 상세 갱신 → 트리 카운트 갱신 (펼침 상태 보존)
    expandedLeafId.value = nodeId
    await refreshDetail()
    await tree.reload()
    // 새로 추가된 카드(마지막)로 스크롤 + 하이라이트
    await nextTick()
    const cards = document.querySelectorAll('.evi-panel:not(.collapsed) .et-card')
    if (cards.length > 0) {
      const last = cards[cards.length - 1] as HTMLElement
      last.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
      last.style.boxShadow = 'inset 0 0 0 2px #3b82f6'
      setTimeout(() => { last.style.boxShadow = '' }, 1500)
    }
  } catch (e: any) {
    showToast(e?.response?.data?.message ?? '증빙 유형 추가에 실패했습니다.', 'error')
  }
}

// ─── Phase 5-14i (P11) — Framework switcher dropdown ───
//
// 본문 헤더 h1 옆 chevron 클릭 → 4 framework + 통제 카운트 + 신규 Framework 항목.
// 클릭 시 selectedFrameworkId 변경 → tree.load() → h1 + 헤더 자동 갱신.
const fwSwitcherOpen = ref(false)

function toggleFwSwitcher(ev: MouseEvent) {
  ev.stopPropagation()
  fwSwitcherOpen.value = !fwSwitcherOpen.value
}

async function selectFramework(fwId: number) {
  fwSwitcherOpen.value = false
  if (selectedFrameworkId.value === fwId) return
  selectedFrameworkId.value = fwId
  expandedLeafId.value = null
  controlDetail.value = null
  notes.value = []
  await tree.load()
  // URL 동기화 (라우트 props 가 :frameworkId 를 받는 패턴 보존)
  router.push({
    name: 'admin-controls',
    params: { frameworkId: fwId },
  }).catch(() => { /* 같은 라우트 push 시 NavigationDuplicated 무시 */ })
}

function onCreateNewFramework() {
  fwSwitcherOpen.value = false
  // v15 후보: FrameworkCreateDialog 통합. 5-14i 시점은 안내 토스트만.
  showToast('Framework 신규 추가는 다음 업데이트에서 지원됩니다.', 'success')
}

// 외부 클릭 / Esc 로 dropdown 닫기
function closeFwSwitcher() {
  fwSwitcherOpen.value = false
}
function onGlobalKeydown(ev: KeyboardEvent) {
  if (ev.key === 'Escape' && fwSwitcherOpen.value) {
    fwSwitcherOpen.value = false
  }
}

// ─── Phase 5-14i (P15-P18) — 4 상태 분기 핸들러 ───
async function retryLoadTree() {
  await tree.load()
}

function clearSearchAndFilter() {
  tree.searchText.value = ''
  tree.statusFilter.value = '전체'
}

/**
 * P18 (5-14i v3) — no-results 상태 자동 트리거.
 *
 * 검색/필터가 활성인데 매치 leaf 0 일 때 true. composable 의 `visibleLeafCount` 를
 * 사용하여 정확히 트리거 (Stage 3 보강).
 */
const showNoResults = computed<boolean>(() => {
  if (!tree.filterActive.value) return false
  if (tree.flatNodes.value.length === 0) return false
  return tree.visibleLeafCount.value === 0
})

// ─── Phase 5-14i (P14, P19, P21) — 검색 인터랙션 ───
//
// P14: searchText 입력 → setSearchText 가 200ms debounce 후 사이드 이펙트 발화
// P19: debounce 발화 후 첫 매치 항목 scrollIntoView + flash
// P21: 검색 입력 시 사용자 펼친 leaf 패널 (expandedLeafId) 자동 닫기
const searchInputRef = ref<HTMLInputElement | null>(null)
const treeBodyRef = ref<HTMLElement | null>(null)
let searchHookCleanup: (() => void) | null = null

function onSearchInput(ev: Event) {
  const v = (ev.target as HTMLInputElement).value
  tree.setSearchText(v)
}

function setupSearchHook() {
  searchHookCleanup = tree.onSearchApplied(() => {
    // P21: 검색어가 있으면 펼친 leaf 패널 닫기
    if (tree.searchText.value.trim()) {
      expandedLeafId.value = null
      controlDetail.value = null
      notes.value = []
    }
    // P19: 첫 매치 항목 자동 포커스 (다음 tick 에 DOM 업데이트 후)
    if (tree.searchText.value.trim()) {
      setTimeout(() => {
        tree.focusFirstMatchInContainer(treeBodyRef.value)
      }, 50)
    }
  })
}

// ─── Phase 5-14h 신규 — UnifiedControlsDialog 저장 성공 핸들러 ───
//
// 다이얼로그가 PATCH /tree 호출 후 200 응답을 받으면 emit('saved', payload) 발화.
// 다이얼로그는 닫지 않고 dirty=0 상태로 그대로 두므로 사용자가 추가 작업 가능.
// view 측은 토스트만 노출 — 트리 reload 는 다이얼로그 내부에서 자동 (saveTree 안의
// reload() 호출) 이므로 별도 호출 불요.
function onUnifiedSaved(payload: { newVersion: number; createdCount: number }) {
  const msg = payload.createdCount > 0
    ? `저장되었습니다. (신규 ${payload.createdCount}개)`
    : `저장되었습니다.`
  showToast(msg, 'success')
}

// ========================================
// leaf 펼침 안의 액션
// ========================================
async function onZipDownload(nodeId: number, controlCode: string) {
  if (zipDownloading.value) return
  zipDownloading.value = true
  try {
    await evidenceFilesApi.downloadZip(nodeId, controlCode)
    showToast('ZIP 다운로드가 완료되었습니다.', 'success')
  } catch (e) {
    console.error('ZIP 다운로드 실패:', e)
    showToast('ZIP 다운로드에 실패했습니다.', 'error')
  } finally {
    zipDownloading.value = false
  }
}

async function onDeleteEvidenceType(etId: number, etName: string) {
  if (
    !confirm(
      `"${etName}" 증빙 유형을 삭제하시겠습니까?\n관련된 파일 이력도 함께 삭제됩니다.`,
    )
  )
    return
  try {
    await evidenceTypesApi.delete(etId)
    await refreshDetail()
    await tree.reload()
    showToast('증빙 유형이 삭제되었습니다.', 'success')
  } catch (e) {
    console.error(e)
    showToast('증빙 유형 삭제에 실패했습니다.', 'error')
  }
}

// ========================================
// lifecycle
// ========================================
onMounted(() => {
  // v18.9.2 — loadFrameworks 가 tree.load() 까지 끝나야 expandAncestorsAndFocus 가
  // tree.flatNodes 를 조회할 수 있음. 그래서 await 후 query 처리.
  //
  // v18.9.3 — URL query 우선. 없으면 sessionStorage 묵시적 복원 시도.
  void (async () => {
    await loadFrameworks()
    const qExpandNodeId = Number(route.query.expandNodeId)
    const qFocusEvidenceTypeId = Number(route.query.focusEvidenceTypeId)
    if (qExpandNodeId) {
      await expandAncestorsAndFocus(qExpandNodeId, qFocusEvidenceTypeId || null)
    } else if (selectedFrameworkId.value != null) {
      await restoreSnapshot(selectedFrameworkId.value)
    }
  })()
  // P11 (5-14i v3) — Framework switcher dropdown: 외부 클릭 + Esc 로 닫기
  document.addEventListener('click', closeFwSwitcher)
  document.addEventListener('keydown', onGlobalKeydown)
  // v18.9.3-fix — scroll 위치 debounced 저장
  window.addEventListener('scroll', onScroll, { passive: true })
  // P14/P19/P21 (5-14i v3~v5) — 검색 적용 hook 등록 (debounce 200ms 후 호출됨)
  setupSearchHook()
})

// v18.9.3-fix — onBeforeUnmount 가 일부 환경 (layout wrapper / router transition /
// KeepAlive) 에서 호출 안 될 수 있음. onBeforeRouteLeave 가드는 Vue Router 의
// navigation 시점에 항상 호출되어 더 reliable.
onBeforeRouteLeave(() => {
  saveSnapshot()
})

onBeforeUnmount(() => {
  // v18.9.3 — 페이지 떠나기 직전 현재 상태 저장 (대시보드/홈 등 다른 페이지로
  // navigate 시점). 같은 탭에서 돌아오면 onMounted 의 restoreSnapshot 이 복원.
  saveSnapshot()
  document.removeEventListener('click', closeFwSwitcher)
  document.removeEventListener('keydown', onGlobalKeydown)
  // v18.9.3-fix — scroll listener + timer cleanup
  window.removeEventListener('scroll', onScroll)
  if (scrollSaveTimer != null) {
    clearTimeout(scrollSaveTimer)
    scrollSaveTimer = null
  }
  if (searchHookCleanup) {
    searchHookCleanup()
    searchHookCleanup = null
  }
})

// 라우트 prop 변경 시 Framework 전환
//
// v18.9.3 — framework 전환 시 이전 framework 상태 저장 + 새 framework 의
// sessionStorage 복원 시도. 같은 ControlsView 인스턴스 안에서 framework 만 바뀌는
// 경우라 onBeforeUnmount 가 호출되지 않음 — watch 가 책임짐.
watch(
  () => props.frameworkId,
  async (newId, oldId) => {
    if (newId != null && frameworks.value.length > 0) {
      const match = frameworks.value.find((f) => f.id === newId)
      if (match && match.id !== selectedFrameworkId.value) {
        // v18.9.3 — 이전 framework 상태 저장 (전환 직전)
        if (oldId != null && oldId !== newId) {
          saveSnapshot()
        }
        selectedFrameworkId.value = match.id
        expandedLeafId.value = null
        controlDetail.value = null
        notes.value = []
        await tree.load()
        // v18.9.3 — 새 framework 의 sessionStorage 복원 시도
        await restoreSnapshot(newId)
      }
    }
  },
)
</script>

<template>
  <div class="p-6 space-y-4">
    <!-- ────────────────────────────── 토스트 ────────────────────────────── -->
    <Transition name="toast">
      <div
        v-if="toast.show"
        class="fixed top-6 right-6 z-[60] px-4 py-3 rounded-lg shadow-lg text-sm font-medium flex items-center gap-2"
        :class="
          toast.type === 'success' ? 'bg-green-600 text-white' : 'bg-red-600 text-white'
        ">
        <i
          :class="[
            'pi text-sm',
            toast.type === 'success' ? 'pi-check-circle' : 'pi-times-circle',
          ]"
        ></i>
        {{ toast.message }}
      </div>
    </Transition>

    <!--
      ────────────────────────────── 본문 헤더 (Phase 5-14i 폴리싱) ──────────────────────────────
      P11: h1 옆 chevron → Framework switcher dropdown (4 framework + 신규).
      P7:  메타 라인 다듬기 (통제 / 증빙 / 작업 / 생성일 — 톤 정합).
    -->
    <div class="flex items-center justify-between gap-4 flex-wrap">
      <div class="min-w-0">
        <!-- h1 + Framework switcher (P11) -->
        <div class="relative inline-flex items-center gap-1.5">
          <h1 class="text-lg font-medium text-gray-900 truncate">
            {{ currentFramework?.name ?? '…' }}
          </h1>
          <button
            v-if="frameworks.length > 0"
            type="button"
            class="fw-switcher-btn group inline-flex items-center justify-center w-6 h-6 rounded text-gray-400 hover:text-gray-700 hover:bg-gray-100 transition-colors"
            :class="{ 'is-open': fwSwitcherOpen }"
            aria-label="Framework 선택"
            @click="toggleFwSwitcher">
            <i class="pi pi-chevron-down text-[10px]"></i>
          </button>
          <!-- dropdown 패널 -->
          <div
            v-if="fwSwitcherOpen"
            class="fw-dropdown"
            @click.stop>
            <button
              v-for="fw in frameworks"
              :key="fw.id"
              type="button"
              class="fw-item"
              :class="{ 'is-current': fw.id === selectedFrameworkId }"
              @click="selectFramework(fw.id)">
              <span class="fw-item-name truncate">{{ fw.name }}</span>
              <span class="fw-item-meta tabular-nums">항목 {{ fw.controlCount ?? 0 }}</span>
              <i
                class="pi pi-check fw-item-check text-[11px]"
                :class="fw.id === selectedFrameworkId ? 'opacity-100' : 'opacity-0'"
              ></i>
            </button>
            <div class="fw-divider"></div>
            <button
              type="button"
              class="fw-item fw-item-create"
              @click="onCreateNewFramework">
              <i class="pi pi-plus text-[10px] mr-1.5"></i>
              <span>신규 Framework</span>
            </button>
          </div>
        </div>
        <p v-if="currentFramework" class="text-xs text-gray-500 mt-0.5">
          <strong class="text-gray-900 font-semibold">관리 항목 {{ tree.totalLeafCount.value }}</strong>
          <span class="mx-1.5 text-gray-300">·</span>
          증빙 {{ tree.totalEvidenceTypeCount.value }}
          <span class="mx-1.5 text-gray-300">·</span>
          작업 {{ currentFramework.jobCount ?? 0 }}
          <template v-if="currentFramework.createdAt">
            <span class="mx-1.5 text-gray-300">·</span>
            생성 {{ currentFramework.createdAt.slice(0, 10) }}
          </template>
        </p>
      </div>
      <!-- 단일 [관리 항목] 버튼 — v13 의 두 버튼 (엑셀 Import / + 통제 항목) 통합 -->
      <button
        @click="openUnifiedDialog"
        class="h-8 px-3 text-xs bg-gray-900 text-white rounded-md hover:bg-gray-800 flex items-center gap-1.5 shrink-0">
        <i class="pi pi-cog text-[10px]"></i> 관리 항목
      </button>
    </div>

    <!-- ────────────────────────────── 검색/필터 (v17 — 트리와 시각 연결) ────────────────────────────── -->
    <div class="search-row">
      <input
        ref="searchInputRef"
        :value="tree.searchText.value"
        @input="onSearchInput"
        type="text"
        class="search-input"
        placeholder="🔍 코드, 분류, 항목명으로 검색"
      />
      <div class="filter-tabs">
        <button
          v-for="status in statusOptions"
          :key="status"
          @click="tree.statusFilter.value = status"
          :class="['filter-tab', { active: tree.statusFilter.value === status }]">
          <span
            v-if="status === '검토 대기' && (tree.statusCounts.value[status] ?? 0) > 0"
            class="filter-pending-dot"
          ></span>
          {{ status }}
          <span class="filter-count">{{ tree.statusCounts.value[status] ?? 0 }}</span>
        </button>
      </div>
    </div>

    <!--
      ────────────────────────────── 트리 본문 (Phase 5-14i 4 상태) ──────────────────────────────
      P15: loading skeleton (카테고리 + leaf shimmer 7행)
      P16: empty Framework (CTA 2개)
      P17: error (메시지 + 호출 정보 + 다시 시도)
      P18: no-results (검색/필터 결과 0 자동 전환) — Stage 3 의 매치 카운트 computed 사용
    -->
    <div class="tree-body">
      <!-- P15: loading skeleton -->
      <div v-if="tree.loading.value" class="skeleton-rows" aria-busy="true" aria-label="로딩 중">
        <div class="skeleton-cat"><div></div></div>
        <div class="skeleton-row"><div></div><div></div><div></div><div></div></div>
        <div class="skeleton-row"><div></div><div></div><div></div><div></div></div>
        <div class="skeleton-row"><div></div><div></div><div></div><div></div></div>
        <div class="skeleton-cat"><div></div></div>
        <div class="skeleton-row"><div></div><div></div><div></div><div></div></div>
        <div class="skeleton-row"><div></div><div></div><div></div><div></div></div>
      </div>

      <!-- P17: error -->
      <div v-else-if="tree.error.value" class="state-block error-state">
        <div class="state-icon error-icon"><i class="pi pi-exclamation-triangle"></i></div>
        <h3 class="state-title">관리 항목을 불러오지 못했습니다</h3>
        <p class="state-desc">네트워크 또는 서버 응답에 문제가 있습니다.</p>
        <code class="state-code">
          GET /api/v1/frameworks/{{ selectedFrameworkId ?? '?' }}/tree → {{ tree.error.value }}
        </code>
        <div class="state-actions">
          <button
            type="button"
            class="state-btn-primary"
            @click="retryLoadTree">
            <i class="pi pi-refresh text-[10px] mr-1.5"></i> 다시 시도
          </button>
        </div>
      </div>

      <!-- P16: empty Framework (등록된 통제 0) -->
      <div v-else-if="tree.flatNodes.value.length === 0" class="state-block empty-state">
        <div class="state-icon empty-icon"><i class="pi pi-folder-open"></i></div>
        <h3 class="state-title">이 Framework 에 관리 항목이 없습니다</h3>
        <p class="state-desc">엑셀 파일을 import 하거나 [관리 항목] 에서 직접 추가하세요.</p>
        <div class="state-actions">
          <button
            type="button"
            class="state-btn-primary"
            @click="openUnifiedDialog">
            <i class="pi pi-cog text-[10px] mr-1.5"></i> 관리 항목
          </button>
        </div>
      </div>

      <!--
        P18: no-results — 검색/필터가 활성인데 매치 0 일 때 (Stage 3 의 isMatched + filterActive 조합).
        본 v-else-if 는 Stage 3 의 visibleLeafCount === 0 computed 으로 정확히 트리거됩니다.
        Stage 2 시점에서는 임시로 `tree.filterActive.value && noVisibleLeaf` 패턴으로 표시.
      -->
      <div v-else-if="showNoResults" class="state-block no-results-state">
        <div class="state-icon no-results-icon"><i class="pi pi-search"></i></div>
        <h4 class="state-title-sm">일치하는 관리 항목이 없습니다</h4>
        <p class="state-desc">다른 검색어를 시도하거나 필터를 해제하세요.</p>
        <button
          type="button"
          class="state-btn-clear"
          @click="clearSearchAndFilter">
          검색 / 필터 초기화
        </button>
      </div>

      <!-- 트리 본문 (default) -->
      <div v-else ref="treeBodyRef">
        <ControlNodeRow
          v-for="root in tree.rootNodes.value"
          :key="root.id"
          :node="root"
          mode="view"
          :expanded-ids="tree.effectiveExpandedIds.value"
          :expanded-leaf-id="expandedLeafId"
          :control-detail="controlDetail"
          :detail-loading="detailLoading"
          :notes="notes"
          :dimmed="tree.filterActive.value && !tree.isMatched(root.id)"
          :is-root="true"
          :highlight-fn="tree.highlightMatch"
          :match-count-by-category-id="tree.matchCountByCategoryId.value"
          :search-active="tree.filterActive.value"
          :focused-evidence-type-id="focusedEvidenceTypeId"
          @toggle-expand="onTreeToggle"
          @go-evidence-type="goToEvidenceTypeDetail"
          @zip-download="onZipDownload"
          @delete-evidence-type="onDeleteEvidenceType"
          @create-evidence-type="onCreateEvidenceType"
          @request-notes="onRequestNotes"
        />
      </div>
    </div>

    <!-- ────────────────────────────── UnifiedControlsDialog ────────────────────────────── -->
    <UnifiedControlsDialog
      v-if="currentFramework"
      v-model:open="showUnifiedDialog"
      :tree-state="tree"
      :framework-name="currentFramework.name"
      @saved="onUnifiedSaved"
    />

    <!-- v19.27 — 본문 인수인계 노트 (자체 API 즉시 저장) -->
    <NodeNotesDialog
      v-model:open="noteOpen"
      :node-id="noteTarget.id"
      :node-code="noteTarget.code"
      :node-name="noteTarget.name"
      :default-author="authorDefault"
      @changed="onNotesChanged"
    />

  </div>
</template>

<style scoped>
.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}

/* ═══ v17 검색/필터 바 + 트리 컨테이너 ═══ */
.search-row {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 12px 12px 0 0;
  padding: 12px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.search-input {
  flex: 1;
  max-width: 360px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  font-size: 13px;
  outline: none;
  font-family: inherit;
}
.search-input:focus {
  border-color: #3b82f6;
}
.filter-tabs {
  display: flex;
  background: #f3f4f6;
  border-radius: 6px;
  padding: 2px;
}
.filter-tab {
  padding: 5px 10px;
  font-size: 12px;
  border: none;
  background: transparent;
  color: #6b7280;
  cursor: pointer;
  border-radius: 4px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-family: inherit;
}
.filter-tab.active {
  background: white;
  color: #111827;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}
.filter-count {
  margin-left: 2px;
  font-weight: 400;
  color: #9ca3af;
}
.filter-tab.active .filter-count {
  color: #6b7280;
}
.filter-pending-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #f59e0b;
}
.tree-body {
  background: white;
  border: 1px solid #e5e7eb;
  border-top: none;
  border-radius: 0 0 12px 12px;
  padding: 4px 0 12px;
  max-height: calc(100vh - 260px);
  overflow-y: auto;
}

/* ─────────────────────── P11 (5-14i) — Framework switcher dropdown ─────────────────────── */
.fw-switcher-btn {
  cursor: pointer;
}
.fw-switcher-btn .pi-chevron-down {
  transition: transform 0.15s;
}
.fw-switcher-btn.is-open .pi-chevron-down {
  transform: rotate(180deg);
}

.fw-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 50;
  background: white;
  border: 1px solid rgb(229 231 235);
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  min-width: 240px;
  padding: 4px;
}

.fw-item {
  display: flex;
  align-items: center;
  width: 100%;
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 13px;
  color: rgb(55 65 81);
  cursor: pointer;
  background: transparent;
  border: none;
  text-align: left;
  transition: background-color 0.1s;
}
.fw-item:hover {
  background-color: rgb(249 250 251);
}
.fw-item.is-current {
  color: rgb(17 24 39);
  font-weight: 500;
}
.fw-item-name {
  flex: 1;
  min-width: 0;
}
.fw-item-meta {
  font-size: 11px;
  color: rgb(156 163 175);
  margin-left: 10px;
  flex-shrink: 0;
}
.fw-item-check {
  color: rgb(59 130 246); /* blue-500 */
  margin-left: 8px;
  flex-shrink: 0;
  transition: opacity 0.1s;
}
.fw-divider {
  height: 1px;
  background: rgb(243 244 246);
  margin: 4px 0;
}
.fw-item-create {
  color: rgb(59 130 246) !important;
}

/* ─────────────────────── P15 (5-14i) — loading skeleton ─────────────────────── */
.skeleton-rows {
  padding: 6px 0;
}
.skeleton-row {
  display: grid;
  grid-template-columns: 64px 1fr 80px 90px;
  gap: 12px;
  align-items: center;
  padding: 11px 16px;
}
.skeleton-cat {
  padding: 12px 16px;
  border-bottom: 1px solid rgb(243 244 246);
}
.skeleton-row > div,
.skeleton-cat > div {
  height: 12px;
  border-radius: 4px;
  background: linear-gradient(90deg, #f3f4f6 25%, #e5e7eb 50%, #f3f4f6 75%);
  background-size: 200% 100%;
  animation: shimmer 1.4s infinite;
}
.skeleton-row > div:nth-child(1) { width: 38px; }
.skeleton-row > div:nth-child(2) { width: 68%; }
.skeleton-row > div:nth-child(3) { width: 70%; }
.skeleton-row > div:nth-child(4) { width: 50%; }
.skeleton-cat > div { height: 14px; width: 28%; }

@keyframes shimmer {
  0%   { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

/* ─────────────────────── P16/P17/P18 (5-14i) — 상태 블록 ─────────────────────── */
.state-block {
  text-align: center;
}

.empty-state, .error-state {
  padding: 64px 24px;
}
.no-results-state {
  padding: 48px 24px;
}

.state-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 14px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
}
.no-results-state .state-icon {
  width: 40px;
  height: 40px;
  border-radius: 9999px;
  font-size: 16px;
}

.empty-icon { background: rgb(243 244 246); color: rgb(156 163 175); }
.error-icon { background: rgb(254 242 242); color: rgb(239 68 68); }
.no-results-icon { background: rgb(243 244 246); color: rgb(156 163 175); }

.state-title {
  font-size: 15px;
  font-weight: 600;
  color: rgb(17 24 39);
  margin: 0 0 6px;
}
.state-title-sm {
  font-size: 13px;
  font-weight: 600;
  color: rgb(17 24 39);
  margin: 0 0 4px;
}
.state-desc {
  font-size: 12.5px;
  color: rgb(107 114 128);
  margin: 0 0 12px;
  line-height: 1.6;
}
.state-code {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
  background: rgb(243 244 246);
  color: rgb(107 114 128);
  padding: 2px 6px;
  border-radius: 3px;
  display: inline-block;
  margin-bottom: 18px;
  word-break: break-all;
}

.state-actions {
  display: inline-flex;
  gap: 8px;
}

.state-btn-primary,
.state-btn-secondary,
.state-btn-clear {
  height: 32px;
  padding: 0 14px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background-color 0.12s;
}

.state-btn-primary {
  background: rgb(17 24 39); /* gray-900 */
  color: white;
  border: none;
}
.state-btn-primary:hover {
  background: rgb(31 41 55); /* gray-800 */
}

.state-btn-secondary {
  background: white;
  color: rgb(17 24 39);
  border: 1px solid rgb(229 231 235);
}
.state-btn-secondary:hover {
  background: rgb(249 250 251);
}

.state-btn-clear {
  height: 28px;
  padding: 0 12px;
  background: white;
  color: rgb(75 85 99);
  border: 1px solid rgb(229 231 235);
  border-radius: 5px;
}
.state-btn-clear:hover {
  background: rgb(249 250 251);
}
</style>