<script setup lang="ts">
/**
 * ControlsView (Framework 상세) — Phase 5-14h 편집 모드 진입 (v14.8 final).
 *
 * <p>spec §3.3 정합. v13 의 평면 테이블 (코드/영역/항목명/수집현황/상태/관리 6컬럼) 을
 * 5-14g 에서 N단 재귀 트리로 전환했고, 5-14h 에서 단일 [통제 관리] 다이얼로그가
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
 * </ul>
 *
 * <h3>5-14g 동작 보존</h3>
 * <ul>
 *   <li>leaf 행 클릭 → 인라인 펼침으로 evidence 카드 목록 (v12 5-12b)</li>
 *   <li>증빙 유형 카드 클릭 → EvidenceTypeDetailView 별도 페이지 이동 (goToEvidenceTypeDetail)</li>
 *   <li>증빙 유형 우측 🗑 → evidenceTypesApi.delete (v15.6 namespace 일치)</li>
 *   <li>[ZIP 다운로드] 버튼 (펼친 leaf 안). 증빙 유형 추가는 [통제 관리] 다이얼로그로 통합 (v15.5)</li>
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
 *       @request-import 인터페이스 보존, 5-14h 에서 @saved emit 추가</li>
 *   <li>ImportControlsDialog — UnifiedControlsDialog 의 [↑ Import] 아이콘 클릭 → 본 페이지가
 *       기존 ImportControlsDialog 를 띄움 (다이얼로그가 다이얼로그를 띄우는 stacking 구조)</li>
 * </ul>
 */
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  frameworksApi,
  controlNodesApi,
  evidenceTypesApi,
  evidenceFilesApi,
} from '@/services/evidenceApi'
import { useControlTree, type LeafStatus } from '@/composables/useControlTree'
import ControlNodeRow from '@/components/controls/ControlNodeRow.vue'
import UnifiedControlsDialog from '@/components/controls/UnifiedControlsDialog.vue'
import type {
  Framework,
  ControlDetail,
  ExcelImportResult,
} from '@/types/evidence'

// ========================================
// Props (v11 Phase 5-3: 라우트 /controls/:frameworkId 에서 전달)
// ========================================
const props = defineProps<{
  frameworkId?: number
}>()

const router = useRouter()

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
const detailLoading = ref(false)

async function toggleLeaf(nodeId: number) {
  if (expandedLeafId.value === nodeId) {
    expandedLeafId.value = null
    controlDetail.value = null
    return
  }
  expandedLeafId.value = nodeId
  detailLoading.value = true
  try {
    const { data } = await controlNodesApi.getDetail(nodeId)
    if (data.success) controlDetail.value = data.data
  } catch (e) {
    console.error(e)
    // v15.6 정상화: 새 endpoint /control-nodes/{id} 호출. 단순 에러 토스트 + 펼침
    // 상태 롤백. v15.5.1 의 안내 메시지 ("v15.6 업데이트에서 정상화 예정") 회수.
    expandedLeafId.value = null
    controlDetail.value = null
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
// 다이얼로그 — UnifiedControlsDialog (5-14g 신규)
//             ImportControlsDialog (기존, [↑ Import] 호출 재사용)
// ========================================
const showUnifiedDialog = ref(false)
const showImportDialog = ref(false)
const importFile = ref<File | null>(null)
const importLoading = ref(false)
const importResult = ref<ExcelImportResult | null>(null)

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
    tree.toggleExpand(nodeId)
  } else {
    void toggleLeaf(nodeId)
  }
}

/**
 * v15.6: param 명 controlId → nodeId 정리. router params 의 `controlId` 키는
 * router/index.ts 의 path 정의 (/evidence-types/:controlId) wire shape 보존
 * (BE 명명 변경은 별도 phase — Q3=B 정합).
 */
function goToEvidenceTypeDetail(evidenceTypeId: number, nodeId: number) {
  if (selectedFrameworkId.value == null) return
  router.push({
    name: 'evidence-type-detail',
    params: {
      frameworkId: selectedFrameworkId.value,
      controlId: nodeId,   // ← router 정의 wire shape 보존
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

function openImportDirectly() {
  // empty Framework 상태에서 [엑셀 Import] CTA 직접 호출.
  // UnifiedControlsDialog 를 거치지 않고 ImportControlsDialog 를 바로 띄움.
  importResult.value = null
  importFile.value = null
  showImportDialog.value = true
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
    }
    // P19: 첫 매치 항목 자동 포커스 (다음 tick 에 DOM 업데이트 후)
    if (tree.searchText.value.trim()) {
      setTimeout(() => {
        tree.focusFirstMatchInContainer(treeBodyRef.value)
      }, 50)
    }
  })
}

function onRequestImport() {
  // 다이얼로그 안에서 [↑ Import] 클릭 → 본 view 가 ImportControlsDialog 띄움
  // (다이얼로그 stacking — UnifiedControlsDialog 닫지 않고 위에 ImportControlsDialog 띄움)
  importResult.value = null
  importFile.value = null
  showImportDialog.value = true
}

function closeImportDialog() {
  showImportDialog.value = false
  importResult.value = null
}

// ─── Phase 5-14h 신규 — UnifiedControlsDialog 저장 성공 핸들러 ───
//
// 다이얼로그가 PATCH /tree 호출 후 200 응답을 받으면 emit('saved', payload) 발화.
// 다이얼로그는 닫지 않고 dirty=0 상태로 그대로 두므로 사용자가 추가 작업 가능.
// view 측은 토스트만 노출 — 트리 reload 는 다이얼로그 내부에서 자동 (saveTree 안의
// reload() 호출) 이므로 별도 호출 불요.
function onUnifiedSaved(payload: { newVersion: number; createdCount: number }) {
  const msg = payload.createdCount > 0
    ? `저장되었습니다. (신규 ${payload.createdCount}개, v${payload.newVersion})`
    : `저장되었습니다. (v${payload.newVersion})`
  showToast(msg, 'success')
}

async function handleImport() {
  if (!importFile.value || !selectedFrameworkId.value) return
  importLoading.value = true
  try {
    const { data } = await frameworksApi.importControls(
      selectedFrameworkId.value,
      importFile.value,
    )
    if (data.success) {
      importResult.value = data.data
      await tree.reload()
      showToast(`Import 완료: ${data.data.successCount}건 성공`, 'success')
    }
  } catch (e: any) {
    showToast(e?.response?.data?.message ?? 'Import 에 실패했습니다.', 'error')
    console.error(e)
  } finally {
    importLoading.value = false
  }
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
  loadFrameworks()
  // P11 (5-14i v3) — Framework switcher dropdown: 외부 클릭 + Esc 로 닫기
  document.addEventListener('click', closeFwSwitcher)
  document.addEventListener('keydown', onGlobalKeydown)
  // P14/P19/P21 (5-14i v3~v5) — 검색 적용 hook 등록 (debounce 200ms 후 호출됨)
  setupSearchHook()
})

onBeforeUnmount(() => {
  document.removeEventListener('click', closeFwSwitcher)
  document.removeEventListener('keydown', onGlobalKeydown)
  if (searchHookCleanup) {
    searchHookCleanup()
    searchHookCleanup = null
  }
})

// 라우트 prop 변경 시 Framework 전환
watch(
  () => props.frameworkId,
  async (newId) => {
    if (newId != null && frameworks.value.length > 0) {
      const match = frameworks.value.find((f) => f.id === newId)
      if (match && match.id !== selectedFrameworkId.value) {
        selectedFrameworkId.value = match.id
        expandedLeafId.value = null
        controlDetail.value = null
        await tree.load()
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
              <span class="fw-item-meta tabular-nums">통제 {{ fw.controlCount ?? 0 }}</span>
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
          <strong class="text-gray-900 font-semibold">통제 {{ tree.totalLeafCount.value }}</strong>
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
      <!-- 단일 [통제 관리] 버튼 — v13 의 두 버튼 (엑셀 Import / + 통제 항목) 통합 -->
      <button
        @click="openUnifiedDialog"
        class="h-8 px-3 text-xs bg-gray-900 text-white rounded-md hover:bg-gray-800 flex items-center gap-1.5 shrink-0">
        <i class="pi pi-cog text-[10px]"></i> 통제 관리
      </button>
    </div>

    <!-- ────────────────────────────── 검색/필터 ────────────────────────────── -->
    <div class="flex items-center gap-3 flex-wrap">
      <div class="relative">
        <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs"></i>
        <input
          ref="searchInputRef"
          :value="tree.searchText.value"
          @input="onSearchInput"
          type="text"
          placeholder="🔍 코드, 분류, 통제명으로 검색"
          class="pl-8 pr-3 h-9 border border-gray-300 rounded-lg text-sm w-64 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
        />
      </div>

      <!--
        Phase 5-14i 폴리싱 (P4):
          검토 대기 필터 탭에 amber-500 5px dot prefix (count > 0 일 때).
          spec §3.3.1.4 의 두 상태 (검토 대기 vs 일반) 시각 분리.
      -->
      <div class="flex bg-gray-100 rounded-lg p-0.5">
        <button
          v-for="status in statusOptions"
          :key="status"
          @click="tree.statusFilter.value = status"
          :class="[
            'px-3 py-1.5 rounded-md text-xs font-medium transition-colors flex items-center gap-1.5',
            tree.statusFilter.value === status
              ? 'bg-white text-gray-900 shadow-sm'
              : 'text-gray-500 hover:text-gray-700',
          ]">
          <span
            v-if="status === '검토 대기' && (tree.statusCounts.value[status] ?? 0) > 0"
            class="filter-pending-dot"
            aria-hidden="true"
          ></span>
          {{ status }}
          <span
            class="text-gray-400 tabular-nums"
            :class="tree.statusFilter.value === status ? 'text-gray-500' : ''">
            {{ tree.statusCounts.value[status] ?? 0 }}
          </span>
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
    <div class="bg-white rounded-xl border border-gray-200 overflow-hidden tree-body-container">
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
        <h3 class="state-title">통제 트리를 불러오지 못했습니다</h3>
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
        <h3 class="state-title">이 Framework 에 통제 항목이 없습니다</h3>
        <p class="state-desc">엑셀 파일을 import 하거나 [통제 관리] 에서 직접 추가하세요.</p>
        <div class="state-actions">
          <button
            type="button"
            class="state-btn-primary"
            @click="openUnifiedDialog">
            <i class="pi pi-cog text-[10px] mr-1.5"></i> 통제 관리
          </button>
          <button
            type="button"
            class="state-btn-secondary"
            @click="openImportDirectly">
            <i class="pi pi-upload text-[10px] mr-1.5"></i> 엑셀 Import
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
        <h4 class="state-title-sm">일치하는 통제 항목이 없습니다</h4>
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
          :dimmed="tree.filterActive.value && !tree.isMatched(root.id)"
          :is-root="true"
          :highlight-fn="tree.highlightMatch"
          :match-count-by-category-id="tree.matchCountByCategoryId.value"
          :search-active="tree.filterActive.value"
          @toggle-expand="onTreeToggle"
          @go-evidence-type="goToEvidenceTypeDetail"
          @zip-download="onZipDownload"
          @delete-evidence-type="onDeleteEvidenceType"
        />
      </div>
    </div>

    <!-- ────────────────────────────── UnifiedControlsDialog ────────────────────────────── -->
    <UnifiedControlsDialog
      v-if="currentFramework"
      v-model:open="showUnifiedDialog"
      :tree-state="tree"
      :framework-name="currentFramework.name"
      @request-import="onRequestImport"
      @saved="onUnifiedSaved"
    />

    <!-- ────────────────────────────── 다이얼로그: 엑셀 Import ────────────────────────────── -->
    <!-- UnifiedControlsDialog 의 [↑ Import] 가 본 다이얼로그를 띄움. -->
    <div
      v-if="showImportDialog"
      class="fixed inset-0 bg-black/40 flex items-center justify-center z-[60]"
      @click.self="closeImportDialog">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-gray-900">통제항목 엑셀 Import</h3>
          <button
            @click="closeImportDialog"
            class="p-1 text-gray-400 hover:text-gray-600">
            <i class="pi pi-times"></i>
          </button>
        </div>
        <p class="text-sm text-gray-500 mb-4">
          엑셀 파일 컬럼: 코드 | 영역 | 항목명 | 설명 | 필요 증빙 (쉼표 구분)
        </p>
        <input
          type="file"
          accept=".xlsx,.xls"
          @change="(e: any) => (importFile = e.target.files?.[0])"
          class="w-full text-sm mb-4"
        />

        <div v-if="importResult" class="mb-4 p-3 bg-gray-50 rounded-lg text-sm">
          <p>
            전체 {{ importResult.totalRows }}행 / 성공
            <strong class="text-green-600">{{ importResult.successCount }}</strong> /
            실패
            <strong class="text-red-600">{{ importResult.failCount }}</strong>
          </p>
          <ul
            v-if="importResult.errors.length > 0"
            class="mt-2 text-xs text-red-500 space-y-0.5">
            <li v-for="(err, i) in importResult.errors" :key="i">{{ err }}</li>
          </ul>
        </div>

        <div class="flex justify-end gap-2">
          <button
            @click="closeImportDialog"
            class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-lg">
            취소
          </button>
          <button
            @click="handleImport"
            :disabled="importLoading || !importFile"
            class="px-4 py-2 text-sm bg-gray-900 text-white rounded-lg hover:bg-gray-800 disabled:opacity-50">
            {{ importLoading ? '업로드 중...' : 'Import 실행' }}
          </button>
        </div>
      </div>
    </div>
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

/* ─────────────────────── P4 (5-14i) — 검토 대기 필터 dot ─────────────────────── */
.filter-pending-dot {
  width: 5px;
  height: 5px;
  border-radius: 9999px;
  background-color: rgb(245 158 11); /* amber-500 */
  flex-shrink: 0;
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