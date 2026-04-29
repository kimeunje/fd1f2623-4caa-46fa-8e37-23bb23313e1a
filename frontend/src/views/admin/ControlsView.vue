<script setup lang="ts">
/**
 * ControlsView (Framework 상세) — Phase 5-14g 트리 본문 전환 (v14.7).
 *
 * <p>spec §3.3 정합. v13 의 평면 테이블 (코드/영역/항목명/수집현황/상태/관리 6컬럼) 을
 * N단 재귀 트리로 전환. 단일 `[통제 관리]` 버튼 (HANDOFF 의 5-14h 통합 항목을 본
 * phase 로 앞당김 — 다이얼로그 read-only 라도 자연 동선이 더 깔끔).</p>
 *
 * <h3>동작 보존</h3>
 * <ul>
 *   <li>leaf 행 클릭 → 인라인 펼침으로 evidence 카드 목록 (v12 5-12b)</li>
 *   <li>증빙 유형 카드 클릭 → EvidenceTypeDetailView 별도 페이지 이동 (goToEvidenceTypeDetail)</li>
 *   <li>증빙 유형 우측 🗑 → controlsApi.deleteEvidenceType (5-14f 보존 endpoint)</li>
 *   <li>[+ 증빙 유형] / [ZIP 다운로드] 버튼 (펼친 leaf 안)</li>
 *   <li>pending 행 배경 강조 (Phase 5-9)</li>
 *   <li>본문 헤더의 Framework 이름 + 카운트 서브텍스트 (Phase 5-13f)</li>
 * </ul>
 *
 * <h3>제거된 것</h3>
 * <ul>
 *   <li>평면 &lt;table&gt; — 트리 본문 (ControlNodeRow 재귀) 대체</li>
 *   <li>[엑셀 Import] / [+ 통제 항목] 두 버튼 — 단일 [통제 관리] 통합</li>
 *   <li>인라인 ✏ / 🗑 액션 (관리 컬럼) — 모든 편집은 다이얼로그 안에서만</li>
 *   <li>showAddControlDialog / showEditControlDialog / newControl / editControl
 *       — handleAddControl / openEditControl / handleEditControl / deleteControl</li>
 * </ul>
 *
 * <h3>유지되는 외부 컴포넌트</h3>
 * <ul>
 *   <li>ImportControlsDialog — UnifiedControlsDialog 의 [↑ Import] 아이콘 클릭 → 본 페이지가
 *       기존 ImportControlsDialog 를 띄움 (다이얼로그가 다이얼로그를 띄우는 stacking 구조)</li>
 *   <li>AddControlDialog — 5-14g 에선 사용 안 함, 5-14h 진입 시 파일 삭제 예정</li>
 * </ul>
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  frameworksApi,
  controlsApi,
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

async function toggleLeaf(controlId: number) {
  if (expandedLeafId.value === controlId) {
    expandedLeafId.value = null
    controlDetail.value = null
    return
  }
  expandedLeafId.value = controlId
  detailLoading.value = true
  try {
    const { data } = await controlsApi.getDetail(controlId)
    if (data.success) controlDetail.value = data.data
  } catch (e) {
    console.error(e)
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  if (!expandedLeafId.value) return
  try {
    const { data } = await controlsApi.getDetail(expandedLeafId.value)
    if (data.success) controlDetail.value = data.data
  } catch (e) {
    console.error(e)
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

// 증빙 유형 추가 다이얼로그 (leaf 펼침 안의 [+ 증빙 유형] 클릭)
const showAddEtDialog = ref(false)
const newEtName = ref('')
const newEtTargetControlId = ref<number | null>(null)

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

function goToEvidenceTypeDetail(evidenceTypeId: number, controlId: number) {
  if (selectedFrameworkId.value == null) return
  router.push({
    name: 'evidence-type-detail',
    params: {
      frameworkId: selectedFrameworkId.value,
      controlId,
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
function onAddEvidenceTypeClick(controlId: number) {
  newEtTargetControlId.value = controlId
  newEtName.value = ''
  showAddEtDialog.value = true
}

async function handleAddEvidenceType() {
  if (!newEtTargetControlId.value || !newEtName.value.trim()) return
  try {
    await controlsApi.addEvidenceType(newEtTargetControlId.value, {
      name: newEtName.value.trim(),
    })
    newEtName.value = ''
    showAddEtDialog.value = false
    await refreshDetail()
    await tree.reload()
    showToast('증빙 유형이 추가되었습니다.', 'success')
  } catch (e: any) {
    // 5-14f 후 백엔드 410 Gone — 사용자에게 안내
    const msg =
      e?.response?.status === 410
        ? '직접 추가는 차단되어 있습니다. 추후 업데이트에서 [통제 관리] 다이얼로그로 통합 예정입니다.'
        : e?.response?.data?.message ?? '증빙 유형 추가에 실패했습니다.'
    showToast(msg, 'error')
  }
}

async function onZipDownload(controlId: number, controlCode: string) {
  if (zipDownloading.value) return
  zipDownloading.value = true
  try {
    await evidenceFilesApi.downloadZip(controlId, controlCode)
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
    await controlsApi.deleteEvidenceType(etId)
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
onMounted(loadFrameworks)

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

    <!-- ────────────────────────────── 본문 헤더 (Phase 5-13f, v14.7 갱신) ────────────────────────────── -->
    <div class="flex items-center justify-between gap-4 flex-wrap">
      <div class="min-w-0">
        <h1 class="text-lg font-medium text-gray-900 truncate">
          {{ currentFramework?.name ?? '…' }}
        </h1>
        <p v-if="currentFramework" class="text-xs text-gray-500 mt-0.5">
          통제 {{ tree.totalLeafCount.value }}개
          · 증빙 {{ tree.totalEvidenceTypeCount.value }}개
          · 작업 {{ currentFramework.jobCount ?? 0 }}개
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
          v-model="tree.searchText.value"
          type="text"
          placeholder="코드, 분류, 통제명 검색..."
          class="pl-8 pr-3 h-9 border border-gray-300 rounded-lg text-sm w-64 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
        />
      </div>

      <div class="flex bg-gray-100 rounded-lg p-0.5">
        <button
          v-for="status in statusOptions"
          :key="status"
          @click="tree.statusFilter.value = status"
          :class="[
            'px-3 py-1.5 rounded-md text-xs font-medium transition-colors',
            tree.statusFilter.value === status
              ? status === '검토 대기'
                ? 'bg-white text-blue-700 shadow-sm'
                : 'bg-white text-gray-900 shadow-sm'
              : status === '검토 대기'
                ? 'text-blue-600 hover:text-blue-700'
                : 'text-gray-500 hover:text-gray-700',
          ]">
          {{ status }}
          <span
            class="ml-1"
            :class="status === '검토 대기' ? 'text-blue-400' : 'text-gray-400'">
            {{ tree.statusCounts.value[status] ?? 0 }}
          </span>
        </button>
      </div>
    </div>

    <!-- ────────────────────────────── 트리 본문 ────────────────────────────── -->
    <div class="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <!-- 로딩 -->
      <div v-if="tree.loading.value" class="p-12 text-center text-gray-400">
        <i class="pi pi-spin pi-spinner text-2xl mb-2"></i>
        <p class="text-sm">로딩 중...</p>
      </div>

      <!-- 에러 -->
      <div
        v-else-if="tree.error.value"
        class="m-5 p-3 bg-red-50 border border-red-200 rounded-md text-xs text-red-700">
        {{ tree.error.value }}
      </div>

      <!-- 빈 -->
      <div
        v-else-if="tree.flatNodes.value.length === 0"
        class="px-4 py-16 text-center text-gray-400 text-sm">
        <i class="pi pi-list text-3xl mb-3 text-gray-300 block"></i>
        <p class="mb-1">등록된 통제가 없습니다.</p>
        <p class="text-xs text-gray-300">
          상단 [통제 관리] 버튼에서 엑셀 Import 로 시작하세요.
        </p>
      </div>

      <!-- 트리 -->
      <div v-else>
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
          @toggle-expand="onTreeToggle"
          @go-evidence-type="goToEvidenceTypeDetail"
          @add-evidence-type="onAddEvidenceTypeClick"
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

    <!-- ────────────────────────────── 다이얼로그: 증빙 유형 추가 ────────────────────────────── -->
    <div
      v-if="showAddEtDialog"
      class="fixed inset-0 bg-black/40 flex items-center justify-center z-[60]"
      @click.self="showAddEtDialog = false">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-sm p-6">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-gray-900">증빙 유형 추가</h3>
          <button
            @click="showAddEtDialog = false"
            class="p-1 text-gray-400 hover:text-gray-600">
            <i class="pi pi-times"></i>
          </button>
        </div>
        <input
          v-model="newEtName"
          class="w-full px-3 py-2 border rounded-lg text-sm mb-4 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
          placeholder="증빙 유형 이름"
          @keyup.enter="handleAddEvidenceType"
        />
        <div class="flex justify-end gap-2">
          <button
            @click="showAddEtDialog = false"
            class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-lg">
            취소
          </button>
          <button
            @click="handleAddEvidenceType"
            :disabled="!newEtName.trim()"
            class="px-4 py-2 text-sm bg-gray-900 text-white rounded-lg hover:bg-gray-800 disabled:opacity-50">
            추가
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
</style>