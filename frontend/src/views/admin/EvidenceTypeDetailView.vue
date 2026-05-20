<script setup lang="ts">
/**
 * 증빙 유형 상세 페이지 (v11 Phase 5-12).
 *
 * secuhub_unified_prototype.html §stage-evidence 의 3탭 구조를 도입한 페이지.
 *
 * 진입 경로: /controls/:frameworkId/:nodeId/evidence-types/:evidenceTypeId
 * 이동 방법: ControlsView 의 통제 항목 확장 → 증빙 유형 카드 이름 클릭
 *
 * 구성:
 *  1. 뒤로가기 링크 (Framework 상세 복귀)
 *  2. 상단 헤더 — 통제 경로 서브텍스트 + 증빙 유형명 h1
 *  3. 메타 2-col 카드 (담당자 / 최신 파일)
 *  4. 검토 알림 박스 — 최신 파일이 pending 일 때만 자동 노출
 *     · [반려하기] 클릭 → 사유 입력 폼 인라인 확장 → [반려 확정 · 이메일 발송]
 *     · [승인] 원클릭
 *  5. 3탭 (파일 이력 / 수동 업로드 / 자동 수집)
 *     · 파일 이력: 버전별 테이블. pending 행은 파란 배경 강조
 *     · 수동 업로드: 액션 분기 (새 파일 등록 / 기존 파일에서 선택) — v18.6a
 *     · 자동 수집: 증빙 유형에 연결된 작업 카드 + 즉시 실행 / 삭제 / 추가
 *
 * v15 Phase 5-15c (v15.7) 변경:
 *  - v15.6 LSP rename 잔존 fix: 옛 `controlsApi` (v15.6 에서 evidenceApi.ts 에서 제거됨)
 *    → `controlNodesApi.getDetail` 로 일괄 변경.
 *  - props.controlId → props.nodeId (Q3=B router param rename 정합)
 *
 * v18.6a — Evidence Asset 신규 채널:
 *  - 수동 업로드 탭의 액션 분기: [새 파일 등록] / [기존 파일에서 선택]
 *  - upload 응답 status="duplicate_detected" 시 confirm dialog 노출
 *    → [기존 사용 (권장)] / [새로 등록] / [취소] 의 3 액션 (Q1=b)
 *  - 기존 파일 선택 시 EvidenceAssetSearchDialog 검색 + asset 선택 → POST /link
 *  - 새로 등록 선택 시 POST /upload?forceUpload=true 재호출 (Q9)
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { controlNodesApi, evidenceFilesApi, evidenceTypesApi, jobsApi } from '@/services/evidenceApi'
import type {
  ControlDetail,
  EvidenceTypeResponse,
  EvidenceFileItem,
  CollectionJobItem,
  ReviewStatus,
  EvidenceAsset,
} from '@/types/evidence'
import EvidenceAssetSearchDialog from '@/components/evidence/EvidenceAssetSearchDialog.vue'
import EvidenceAssetDuplicateConfirmDialog from '@/components/evidence/EvidenceAssetDuplicateConfirmDialog.vue'
import ScriptEditorDialog from '@/components/admin/ScriptEditorDialog.vue'

// ========================================
// Props — 라우트에서 전달
// v15.7: controlId → nodeId (Q3=B 정합, router/index.ts 의 path :nodeId 와 동기)
// ========================================
const props = defineProps<{
  frameworkId: number
  nodeId: number
  evidenceTypeId: number
}>()

const router = useRouter()

// ========================================
// 상태
// ========================================
const loading = ref(false)
const control = ref<ControlDetail | null>(null)
const evidenceType = ref<EvidenceTypeResponse | null>(null)
const files = ref<EvidenceFileItem[]>([])
const jobs = ref<CollectionJobItem[]>([])

type TabKey = 'history' | 'upload' | 'auto'
const activeTab = ref<TabKey>('history')

// 수동 업로드
const uploadFile = ref<File | null>(null)
const uploadSubmitNote = ref('')
const uploading = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
const isDragging = ref(false)

// v18.6a — 수동 업로드 탭의 액션 분기 모드
type UploadMode = 'menu' | 'new'
const uploadMode = ref<UploadMode>('menu')

// v18.6a — 검색 다이얼로그
const showSearchDialog = ref(false)

// v18.6a — 중복 감지 confirm 다이얼로그
const showDuplicateDialog = ref(false)
const duplicateAsset = ref<EvidenceAsset | null>(null)

// duplicate confirm 후 force/link 재호출 시 보존할 컨텍스트
const pendingFile = ref<File | null>(null)
const pendingSubmitNote = ref<string>('')

// 검토 (최신 파일 대상)
const rejectFormOpen = ref(false)
const rejectReason = ref('')
const reviewSubmitting = ref(false)

// 자동 수집 작업 생성 다이얼로그
const showAddJobDialog = ref(false)
const newJob = ref<{
  name: string
  description: string
  jobType: string
  scriptId: number | null         // v18.8.2 — UID 기반
  scriptPath: string              // legacy fallback
  scheduleCron: string
}>({
  name: '',
  description: '',
  jobType: 'web_scraping',   // ← BE JobType enum 정합
  scriptId: null,
  scriptPath: '',
  scheduleCron: '',
})
const addJobSubmitting = ref(false)

// 토스트
const toast = ref({ show: false, message: '', type: 'success' as 'success' | 'error' })
function showToast(message: string, type: 'success' | 'error' = 'success') {
  toast.value = { show: true, message, type }
  setTimeout(() => { toast.value.show = false }, 3000)
}

// ========================================
// Computed
// ========================================
const latestFile = computed<EvidenceFileItem | null>(() => files.value[0] ?? null)
const isPending = computed(() => latestFile.value?.reviewStatus === 'pending')

const historyTabLabel = computed(() => `파일 이력 (${files.value.length})`)
const autoTabLabel = computed(() => `자동 수집 (${jobs.value.length})`)

/** 담당자 정보 — 현재 백엔드 DTO에 노출되지 않으면 "미지정" 표시 */
const ownerName = computed(() => evidenceType.value?.ownerUserName ?? '미지정')
const ownerTeam = computed(() => evidenceType.value?.ownerUserTeam ?? null)

// ========================================
// 데이터 로드 (병렬)
// ========================================
async function loadAll() {
  loading.value = true
  try {
    const [controlRes, filesRes, jobsRes] = await Promise.all([
      // v15.7: controlsApi.getDetail (v15.6 에서 제거됨) → controlNodesApi.getDetail (정상화).
      // props.controlId → props.nodeId (Q3=B 정합).
      controlNodesApi.getDetail(props.nodeId),
      evidenceFilesApi.listByType(props.evidenceTypeId),
      jobsApi.list(),
    ])

    if (controlRes.data.success) {
      control.value = controlRes.data.data
      evidenceType.value = control.value.evidenceTypes.find(
        et => et.id === props.evidenceTypeId
      ) ?? null

      if (!evidenceType.value) {
        showToast('해당 증빙 유형을 찾을 수 없습니다.', 'error')
      }
    }

    if (filesRes.data.success) {
      files.value = filesRes.data.data
    }

    if (jobsRes.data.success) {
      // 증빙 유형 ID 기준 필터링 — 백엔드는 전체 목록을 반환하므로 프론트 필터
      jobs.value = jobsRes.data.data.filter(
        j => j.evidenceTypeId === props.evidenceTypeId
      )
    }
  } catch (e) {
    console.error(e)
    showToast('데이터를 불러오지 못했습니다.', 'error')
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)

// ========================================
// 네비게이션
// ========================================
function goBack() {
  router.push({
    name: 'framework-detail',
    params: { frameworkId: props.frameworkId },
  })
}

// ========================================
// 증빙 유형 삭제 (v18)
// ========================================
const deleting = ref(false)

async function handleDeleteEvidenceType() {
  if (!evidenceType.value) return
  const name = evidenceType.value.name
  const fileCount = files.value.length
  let msg = `"${name}" 증빙 유형을 삭제하시겠습니까?`
  if (fileCount > 0) {
    msg += `\n\n이 증빙 유형에 연결된 파일 ${fileCount}건도 함께 삭제됩니다.`
  }
  if (!confirm(msg)) return

  deleting.value = true
  try {
    await evidenceTypesApi.delete(props.evidenceTypeId)
    showToast(`"${name}" 증빙 유형이 삭제되었습니다.`, 'success')
    setTimeout(() => goBack(), 800)
  } catch (e: any) {
    showToast(e?.response?.data?.message ?? '증빙 유형 삭제에 실패했습니다.', 'error')
  } finally {
    deleting.value = false
  }
}

// ========================================
// 증빙 유형 수정 (v18)
// ========================================
const editing = ref(false)
const editForm = ref({ name: '', description: '', ownerUserId: null as number | null, dueDate: '' })
const saving = ref(false)

function startEdit() {
  if (!evidenceType.value) return
  editForm.value = {
    name: evidenceType.value.name ?? '',
    description: evidenceType.value.description ?? '',
    ownerUserId: evidenceType.value.ownerUserId ?? null,
    dueDate: evidenceType.value.dueDate ?? '',
  }
  editing.value = true
}

function cancelEdit() {
  editing.value = false
}

async function saveEdit() {
  if (!editForm.value.name.trim()) {
    showToast('증빙 유형 이름은 필수입니다.', 'error')
    return
  }
  saving.value = true
  try {
    await evidenceTypesApi.update(props.evidenceTypeId, {
      name: editForm.value.name.trim(),
      description: editForm.value.description.trim() || undefined,
      ownerUserId: editForm.value.ownerUserId,
      dueDate: editForm.value.dueDate || undefined,
    })
    showToast('증빙 유형이 수정되었습니다.', 'success')
    editing.value = false
    await loadAll()
  } catch (e: any) {
    showToast(e?.response?.data?.message ?? '수정에 실패했습니다.', 'error')
  } finally {
    saving.value = false
  }
}

// ========================================
// 검토 — 승인/반려
// ========================================
async function approve() {
  if (!latestFile.value || reviewSubmitting.value) return
  reviewSubmitting.value = true
  try {
    const { data } = await evidenceFilesApi.approve(latestFile.value.id)
    if (data.success) {
      showToast('승인이 완료되었습니다.', 'success')
      await loadAll()
    }
  } catch (e: any) {
    showToast(e?.response?.data?.message ?? '승인 처리에 실패했습니다.', 'error')
  } finally {
    reviewSubmitting.value = false
  }
}

function openRejectForm() {
  rejectFormOpen.value = true
  rejectReason.value = ''
}

function closeRejectForm() {
  rejectFormOpen.value = false
  rejectReason.value = ''
}

async function reject() {
  if (!latestFile.value || reviewSubmitting.value) return
  if (!rejectReason.value.trim()) {
    showToast('반려 사유를 입력하세요.', 'error')
    return
  }
  reviewSubmitting.value = true
  try {
    const { data } = await evidenceFilesApi.reject(latestFile.value.id, {
      reviewNote: rejectReason.value.trim(),
    })
    if (data.success) {
      showToast('반려 처리가 완료되었습니다. 담당자에게 이메일이 발송됩니다.', 'success')
      closeRejectForm()
      await loadAll()
    }
  } catch (e: any) {
    showToast(e?.response?.data?.message ?? '반려 처리에 실패했습니다.', 'error')
  } finally {
    reviewSubmitting.value = false
  }
}

// ========================================
// 파일 다운로드 / 삭제
// ========================================
async function handleDownload(fileId: number, fileName: string) {
  try {
    await evidenceFilesApi.download(fileId, fileName)
  } catch (e) {
    console.error(e)
    showToast('파일 다운로드에 실패했습니다.', 'error')
  }
}

async function handleDeleteFile(fileId: number, fileName: string) {
  if (!confirm(`"${fileName}" 파일을 삭제하시겠습니까?`)) return
  try {
    await evidenceFilesApi.delete(fileId)
    showToast('파일이 삭제되었습니다.', 'success')
    await loadAll()
  } catch (e) {
    showToast('파일 삭제에 실패했습니다.', 'error')
  }
}

// ========================================
// 수동 업로드 탭
// ========================================
function handleFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    uploadFile.value = input.files[0]
  }
}

function handleDragOver(e: DragEvent) {
  e.preventDefault()
  isDragging.value = true
}

function handleDragLeave() {
  isDragging.value = false
}

function handleDrop(e: DragEvent) {
  e.preventDefault()
  isDragging.value = false
  if (e.dataTransfer && e.dataTransfer.files.length > 0) {
    uploadFile.value = e.dataTransfer.files[0]
  }
}

function clearUploadFile() {
  uploadFile.value = null
  if (fileInputRef.value) fileInputRef.value.value = ''
}

/**
 * 수동 업로드 본문 — v18.6a 변경 (응답 status 분기).
 *
 * @param forceUpload true 시 중복 감지 무시하고 별도 asset 생성 (Q9).
 */
async function handleUpload(forceUpload = false) {
  if (!uploadFile.value || uploading.value) return
  uploading.value = true
  try {
    const note = uploadSubmitNote.value.trim() || undefined
    const { data } = await evidenceFilesApi.upload(
      props.evidenceTypeId,
      uploadFile.value,
      note,
      forceUpload,
    )

    if (data.success && data.data.status === 'duplicate_detected') {
      // 중복 감지 — confirm dialog 노출, 사용자 선택 대기
      duplicateAsset.value = data.data.existingAsset ?? null
      pendingFile.value = uploadFile.value
      pendingSubmitNote.value = uploadSubmitNote.value
      showDuplicateDialog.value = true
      uploading.value = false
      return
    }

    // created — 정상 신규 등록
    showToast('파일이 업로드되었습니다.', 'success')
    resetUploadState()
    await loadAll()
    activeTab.value = 'history'
  } catch (e: any) {
    showToast(e?.response?.data?.message ?? '업로드에 실패했습니다.', 'error')
  } finally {
    uploading.value = false
  }
}

// ========================================
// v18.6a — 중복 감지 confirm 다이얼로그 핸들러
// ========================================

/**
 * [기존 사용 (권장)] — POST /evidence-files/link 호출.
 */
async function handleUseExisting() {
  if (!duplicateAsset.value) return
  uploading.value = true
  try {
    await evidenceFilesApi.link({
      evidenceTypeId: props.evidenceTypeId,
      assetId: duplicateAsset.value.id,
      fileName: pendingFile.value?.name,
      submitNote: pendingSubmitNote.value.trim() || undefined,
    })
    showToast('기존 파일이 연결되었습니다.', 'success')
    resetUploadState()
    await loadAll()
    activeTab.value = 'history'
  } catch (e: any) {
    showToast(e?.response?.data?.message ?? '연결에 실패했습니다.', 'error')
  } finally {
    uploading.value = false
  }
}

/**
 * [새로 등록] — POST /upload?forceUpload=true 재호출 (Q9 — 같은 sha 의 별도 asset).
 */
async function handleForceNew() {
  if (!pendingFile.value) return
  // pendingFile 을 uploadFile 로 복원 + handleUpload(true) 재호출
  uploadFile.value = pendingFile.value
  uploadSubmitNote.value = pendingSubmitNote.value
  showDuplicateDialog.value = false
  duplicateAsset.value = null
  await handleUpload(true)   // forceUpload=true
}

/**
 * [취소] — 다이얼로그 닫고 상태 reset (uploadFile 은 유지 — 사용자 재시도 가능).
 */
function handleCancelDuplicate() {
  showDuplicateDialog.value = false
  duplicateAsset.value = null
  pendingFile.value = null
  pendingSubmitNote.value = ''
}

// ========================================
// v18.6a — 검색 다이얼로그 핸들러
// ========================================

/**
 * 검색 다이얼로그에서 asset 선택 → POST /evidence-files/link 호출.
 */
async function handleSelectAsset(asset: EvidenceAsset) {
  showSearchDialog.value = false
  uploading.value = true
  try {
    await evidenceFilesApi.link({
      evidenceTypeId: props.evidenceTypeId,
      assetId: asset.id,
      submitNote: uploadSubmitNote.value.trim() || undefined,
    })
    showToast('기존 파일이 연결되었습니다.', 'success')
    resetUploadState()
    await loadAll()
    activeTab.value = 'history'
  } catch (e: any) {
    showToast(e?.response?.data?.message ?? '연결에 실패했습니다.', 'error')
  } finally {
    uploading.value = false
  }
}

/**
 * 업로드 모드 reset — 액션 분기 menu 로 복귀 + 상태 정리.
 */
function resetUploadState() {
  uploadFile.value = null
  uploadSubmitNote.value = ''
  uploadMode.value = 'menu'
  showDuplicateDialog.value = false
  duplicateAsset.value = null
  pendingFile.value = null
  pendingSubmitNote.value = ''
  if (fileInputRef.value) fileInputRef.value.value = ''
}

// ========================================
// 자동 수집 탭
// ========================================
async function handleExecuteJob(jobId: number) {
  try {
    const { data } = await jobsApi.execute(jobId)
    if (data.success) {
      showToast('수집 작업이 실행되었습니다.', 'success')
      setTimeout(loadAll, 1500)
    }
  } catch (e: any) {
    showToast(e?.response?.data?.message ?? '작업 실행에 실패했습니다.', 'error')
  }
}

async function handleToggleJob(jobId: number) {
  try {
    await jobsApi.toggleActive(jobId)
    showToast('작업 상태가 변경되었습니다.', 'success')
    await loadAll()
  } catch (e) {
    showToast('작업 상태 변경에 실패했습니다.', 'error')
  }
}

async function handleDeleteJob(jobId: number, jobName: string) {
  if (!confirm(`"${jobName}" 수집 작업을 삭제하시겠습니까?`)) return
  try {
    await jobsApi.delete(jobId)
    showToast('수집 작업이 삭제되었습니다.', 'success')
    await loadAll()
  } catch (e) {
    showToast('작업 삭제에 실패했습니다.', 'error')
  }
}

function openAddJobDialog() {
  newJob.value = {
    name: '',
    description: '',
    jobType: 'web_scraping',   // ← BE JobType enum 정합
    scriptId: null,             // v18.8.2
    scriptPath: '',             // legacy fallback
    scheduleCron: '',
  }
  showAddJobDialog.value = true
}

// ========================================
// v18.8.2 — 스크립트 작성/편집 dialog 연계 (UID 기반)
// ========================================
const scriptEditorMode = ref<'create' | 'edit' | null>(null)
const editingScriptId = ref<number | null>(null)

function openScriptCreateDialog() {
  scriptEditorMode.value = 'create'
  editingScriptId.value = null
}

function openScriptEditDialog(scriptId: number) {
  editingScriptId.value = scriptId
  scriptEditorMode.value = 'edit'
}

function closeScriptEditor() {
  scriptEditorMode.value = null
  editingScriptId.value = null
}

function onScriptSaved(payload: { scriptId: number }) {
  closeScriptEditor()
  // 작업 등록 dialog 의 scriptId 자동 채움
  if (showAddJobDialog.value && !newJob.value.scriptId) {
    newJob.value.scriptId = payload.scriptId
  }
}

async function handleCreateJob() {
  if (!newJob.value.name.trim() || !newJob.value.jobType) {
    showToast('작업명과 작업 유형은 필수입니다.', 'error')
    return
  }
  addJobSubmitting.value = true
  try {
    const { data } = await jobsApi.create({
      name: newJob.value.name.trim(),
      description: newJob.value.description.trim() || undefined,
      jobType: newJob.value.jobType,
      scriptId: newJob.value.scriptId ?? undefined,           // v18.8.2
      scriptPath: newJob.value.scriptPath.trim() || undefined, // legacy
      scheduleCron: newJob.value.scheduleCron.trim() || undefined,
      evidenceTypeId: props.evidenceTypeId,
    })
    if (data.success) {
      showToast('수집 작업이 생성되었습니다.', 'success')
      showAddJobDialog.value = false
      await loadAll()
    }
  } catch (e: any) {
    showToast(e?.response?.data?.message ?? '작업 생성에 실패했습니다.', 'error')
  } finally {
    addJobSubmitting.value = false
  }
}

// ========================================
// 포맷터
// ========================================
function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatDate(iso?: string): string {
  if (!iso) return '—'
  return iso.slice(0, 10)
}

function formatDateTime(iso?: string): string {
  if (!iso) return '—'
  return iso.slice(0, 16).replace('T', ' ')
}

function reviewStatusBadge(status?: ReviewStatus): { label: string; cls: string } | null {
  if (!status) return null
  // ControlsView.reviewStatusBadge 와 라벨·클래스 정합 (Phase 5-13c)
  const map: Record<ReviewStatus, { label: string; cls: string }> = {
    pending: { label: '● 검토 대기', cls: 'bg-blue-100 text-blue-700' },
    approved: { label: '승인', cls: 'bg-green-100 text-green-700' },
    rejected: { label: '반려', cls: 'bg-red-100 text-red-700' },
    auto_approved: { label: '자동 승인', cls: 'bg-gray-100 text-gray-600' },
  }
  return map[status] ?? null
}

function methodLabel(method?: string): string {
  if (method === 'auto') return '자동'
  if (method === 'manual') return '수동'
  return method ?? '—'
}

function executionDotCls(status?: string): string {
  if (status === 'success') return 'bg-green-500'
  if (status === 'failed') return 'bg-red-500'
  if (status === 'running') return 'bg-blue-500'
  return 'bg-gray-300'
}
</script>

<template>
  <div class="p-6 max-w-5xl mx-auto">
    <!-- 토스트 -->
    <Transition name="toast">
      <div v-if="toast.show"
        class="fixed top-6 right-6 z-[60] px-4 py-3 rounded-lg shadow-lg text-sm font-medium flex items-center gap-2"
        :class="toast.type === 'success' ? 'bg-green-600 text-white' : 'bg-red-600 text-white'">
        <i :class="['pi text-sm', toast.type === 'success' ? 'pi-check-circle' : 'pi-times-circle']"></i>
        {{ toast.message }}
      </div>
    </Transition>

    <!-- 로딩 스켈레톤 -->
    <div v-if="loading && !evidenceType" class="bg-white border border-gray-200 rounded-xl p-10 text-center text-sm text-gray-400">
      <i class="pi pi-spin pi-spinner text-xl mb-2"></i>
      <div>불러오는 중…</div>
    </div>

    <!-- 본문 -->
    <div v-else-if="evidenceType" class="bg-white border border-gray-200 rounded-xl p-5">

      <!-- 뒤로가기 -->
      <button
        @click="goBack"
        class="inline-flex items-center gap-1.5 text-xs text-gray-500 hover:text-gray-900 mb-3">
        <i class="pi pi-chevron-left text-[10px]"></i>
        관리 항목 목록으로
      </button>

      <!-- 헤더 (Phase 5-14g — ancestors 경로 추가, spec §3.4.0) -->
      <div class="pb-3 border-b border-gray-200">
        <!-- 계층 경로: depth=1 카테고리 부터 leaf 직계 부모까지 ▸ 로 연결, 마지막에 leaf 코드+이름 -->
        <div
          v-if="control"
          class="text-[11px] text-gray-400 mb-1 flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
          <template v-for="(a, i) in (control.ancestors ?? [])" :key="a.id">
            <span class="text-gray-500">{{ a.code }} {{ a.name }}</span>
            <span class="text-gray-300 text-[9px]">▸</span>
          </template>
          <span class="text-gray-700 font-medium">{{ control.code }} {{ control.name }}</span>
        </div>

        <div class="flex items-start justify-between gap-4">
          <div class="flex-1 min-w-0">
            <!-- 뷰 모드 -->
            <template v-if="!editing">
              <h1 class="text-lg font-medium m-0">{{ evidenceType.name }}</h1>
              <p v-if="evidenceType.description" class="text-xs text-gray-500 mt-1">
                {{ evidenceType.description }}
              </p>
            </template>
            <!-- 편집 모드 -->
            <template v-else>
              <input
                v-model="editForm.name"
                class="w-full text-lg font-medium border border-gray-300 rounded-md px-3 py-1.5 outline-none focus:border-blue-500"
                placeholder="증빙 유형 이름" />
              <textarea
                v-model="editForm.description"
                rows="2"
                class="w-full mt-2 text-xs text-gray-500 border border-gray-300 rounded-md px-3 py-2 outline-none focus:border-blue-500 resize-none"
                placeholder="설명 (선택)"></textarea>
            </template>
          </div>
          <!-- v18: 증빙 유형 관리 액션 -->
          <div class="flex items-center gap-2 shrink-0">
            <template v-if="!editing">
              <button
                @click="startEdit"
                class="h-8 px-3 text-xs border border-gray-200 bg-white rounded-md text-gray-600 hover:bg-gray-50 inline-flex items-center gap-1.5"
                title="증빙 유형 수정">
                <i class="pi pi-pencil text-[10px]"></i>
                수정
              </button>
              <button
                @click="handleDeleteEvidenceType"
                :disabled="deleting"
                class="h-8 px-3 text-xs border border-gray-200 bg-white rounded-md text-gray-500 hover:text-red-600 hover:border-red-200 hover:bg-red-50 disabled:opacity-50 inline-flex items-center gap-1.5 transition-colors"
                title="증빙 유형 삭제">
                <i :class="['pi text-[10px]', deleting ? 'pi-spin pi-spinner' : 'pi-trash']"></i>
                삭제
              </button>
            </template>
            <template v-else>
              <button
                @click="cancelEdit"
                :disabled="saving"
                class="h-8 px-3 text-xs border border-gray-200 bg-white rounded-md text-gray-600 hover:bg-gray-50 disabled:opacity-50">
                취소
              </button>
              <button
                @click="saveEdit"
                :disabled="saving || !editForm.name.trim()"
                class="h-8 px-3 text-xs bg-gray-900 text-white rounded-md hover:bg-gray-800 disabled:opacity-50 inline-flex items-center gap-1.5">
                <i v-if="saving" class="pi pi-spin pi-spinner text-[10px]"></i>
                저장
              </button>
            </template>
          </div>
        </div>
      </div>

      <!-- 메타 2-col 카드 -->
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 py-4">
        <div class="bg-gray-50 rounded-md p-3">
          <div class="text-[11px] text-gray-400 mb-1">담당자</div>
          <template v-if="!editing">
            <div class="text-sm font-medium">
              {{ ownerName }}
              <span v-if="ownerTeam" class="text-[11px] text-gray-400 font-normal">· {{ ownerTeam }}</span>
            </div>
          </template>
          <template v-else>
            <input
              v-model.number="editForm.ownerUserId"
              type="number"
              min="0"
              class="w-full text-sm border border-gray-300 rounded-md px-2 py-1 outline-none focus:border-blue-500"
              placeholder="사용자 ID (0 = 미배정)" />
            <p class="text-[10px] text-gray-400 mt-1">담당자 사용자 ID를 입력하세요. 0이면 미배정.</p>
          </template>
        </div>
        <div class="bg-gray-50 rounded-md p-3">
          <div class="text-[11px] text-gray-400 mb-1">
            {{ editing ? '마감일' : '최신 파일' }}
          </div>
          <template v-if="!editing">
            <div v-if="latestFile" class="text-sm font-medium flex items-center gap-1.5 flex-wrap">
              <span>v{{ latestFile.version }}</span>
              <span class="text-[11px] text-gray-400 font-normal">
                · {{ formatDate(latestFile.collectedAt) }}
                · {{ methodLabel(latestFile.collectionMethod) }}
              </span>
              <span
                v-if="reviewStatusBadge(latestFile.reviewStatus)"
                class="inline-block px-1.5 py-0.5 text-[10px] font-medium rounded"
                :class="reviewStatusBadge(latestFile.reviewStatus)!.cls">
                {{ reviewStatusBadge(latestFile.reviewStatus)!.label }}
              </span>
            </div>
            <div v-else class="text-sm text-gray-400">아직 파일이 없습니다</div>
          </template>
          <template v-else>
            <input
              v-model="editForm.dueDate"
              type="date"
              class="w-full text-sm border border-gray-300 rounded-md px-2 py-1 outline-none focus:border-blue-500" />
            <p class="text-[10px] text-gray-400 mt-1">비워두면 마감일 없음</p>
          </template>
        </div>
      </div>

      <!-- 검토 알림 박스 (pending 상태만) -->
      <div v-if="isPending && latestFile" class="bg-blue-50 border border-blue-200 rounded-md p-4 mb-4">
        <div class="flex gap-3 items-start">
          <i class="pi pi-info-circle text-blue-600 shrink-0 mt-0.5 text-sm"></i>
          <div class="flex-1 min-w-0">
            <div class="text-sm font-medium text-blue-900 mb-1">검토 대기 · 최신 파일 승인 필요</div>
            <div class="text-xs text-blue-800 flex items-center gap-1 flex-wrap">
              <span class="font-mono">{{ latestFile.fileName }}</span>
              <span>· {{ formatFileSize(latestFile.fileSize) }}</span>
              <template v-if="latestFile.uploadedByName">
                · {{ latestFile.uploadedByName }} 제출
              </template>
              · {{ formatDateTime(latestFile.collectedAt) }}
              <button
                @click="handleDownload(latestFile.id, latestFile.fileName)"
                class="ml-1 p-0.5 text-blue-700 hover:text-blue-900"
                title="다운로드">
                <i class="pi pi-download text-[11px]"></i>
              </button>
            </div>
            <div
              v-if="latestFile.submitNote"
              class="text-xs text-gray-600 mt-2 pl-3 border-l-2 border-gray-300 italic">
              "{{ latestFile.submitNote }}"
            </div>

            <!-- 기본 액션 -->
            <div v-if="!rejectFormOpen" class="flex gap-2 mt-3">
              <button
                @click="openRejectForm"
                :disabled="reviewSubmitting"
                class="h-8 px-4 text-xs border border-red-200 bg-white rounded-md text-red-600 hover:bg-red-50 disabled:opacity-50">
                반려하기
              </button>
              <button
                @click="approve"
                :disabled="reviewSubmitting"
                class="h-8 px-4 text-xs bg-gray-900 text-white rounded-md hover:bg-gray-800 disabled:opacity-50 inline-flex items-center gap-1.5">
                <i v-if="reviewSubmitting" class="pi pi-spin pi-spinner text-[10px]"></i>
                승인
              </button>
            </div>

            <!-- 반려 사유 입력 -->
            <div v-else class="mt-3 pt-3 border-t border-blue-200">
              <label class="block text-xs font-medium text-blue-900 mb-1.5">
                반려 사유 <span class="text-red-500">*</span>
              </label>
              <textarea
                v-model="rejectReason"
                rows="2"
                placeholder="담당자가 확인할 수 있도록 구체적인 사유를 입력하세요"
                class="w-full px-3 py-2 text-xs border border-blue-200 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none bg-white resize-none"></textarea>
              <div class="flex gap-2 mt-2">
                <button
                  @click="closeRejectForm"
                  :disabled="reviewSubmitting"
                  class="h-7 px-3 text-xs border border-gray-200 bg-white rounded-md text-gray-600 hover:bg-gray-50 disabled:opacity-50">
                  취소
                </button>
                <button
                  @click="reject"
                  :disabled="reviewSubmitting || !rejectReason.trim()"
                  class="h-7 px-3 text-xs bg-red-600 text-white rounded-md hover:bg-red-700 disabled:opacity-50 inline-flex items-center gap-1.5">
                  <i v-if="reviewSubmitting" class="pi pi-spin pi-spinner text-[10px]"></i>
                  반려 확정 · 이메일 발송
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 탭 바 -->
      <div class="flex border-b border-gray-200 -mb-px">
        <button
          @click="activeTab = 'history'"
          class="px-4 py-2.5 text-sm border-b-2"
          :class="activeTab === 'history'
            ? 'text-gray-900 font-medium border-gray-900'
            : 'text-gray-500 border-transparent hover:text-gray-900'">
          {{ historyTabLabel }}
        </button>
        <button
          @click="activeTab = 'upload'"
          class="px-4 py-2.5 text-sm border-b-2"
          :class="activeTab === 'upload'
            ? 'text-gray-900 font-medium border-gray-900'
            : 'text-gray-500 border-transparent hover:text-gray-900'">
          수동 업로드
        </button>
        <button
          @click="activeTab = 'auto'"
          class="px-4 py-2.5 text-sm border-b-2"
          :class="activeTab === 'auto'
            ? 'text-gray-900 font-medium border-gray-900'
            : 'text-gray-500 border-transparent hover:text-gray-900'">
          {{ autoTabLabel }}
        </button>
      </div>

      <!-- ======================================
           탭 1: 파일 이력
           ====================================== -->
      <div v-show="activeTab === 'history'" class="py-5">
        <div v-if="files.length === 0" class="text-center py-10 text-sm text-gray-400">
          <i class="pi pi-file text-3xl text-gray-300 mb-2"></i>
          <p>아직 업로드된 파일이 없습니다.</p>
          <p class="text-[11px] mt-1">"수동 업로드" 또는 "자동 수집" 탭에서 파일을 수집하세요.</p>
        </div>
        <table v-else class="w-full text-xs">
          <thead>
            <tr class="text-left text-[11px] text-gray-400 font-normal border-b border-gray-100">
              <th class="py-2 px-2 font-normal" style="width: 70px;">버전</th>
              <th class="py-2 px-2 font-normal" style="width: 100px;">수집일</th>
              <th class="py-2 px-2 font-normal" style="width: 70px;">크기</th>
              <th class="py-2 px-2 font-normal" style="width: 60px;">방식</th>
              <th class="py-2 px-2 font-normal">파일명</th>
              <th class="py-2 px-2 font-normal" style="width: 90px;"></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(file, idx) in files"
              :key="file.id"
              class="border-b border-gray-100 last:border-b-0"
              :class="file.reviewStatus === 'pending' ? 'bg-blue-50/40' : ''">
            <td class="py-2.5 px-2">
              <span
                class="font-mono"
                :class="file.reviewStatus === 'pending'
                  ? 'font-medium text-blue-700'
                  : 'text-gray-500'">
                v{{ file.version }}
              </span>
            </td>
            <td class="py-2.5 px-2 text-gray-600">{{ formatDate(file.collectedAt) }}</td>
            <td class="py-2.5 px-2 text-gray-600">{{ formatFileSize(file.fileSize) }}</td>
            <td class="py-2.5 px-2 text-gray-600">{{ methodLabel(file.collectionMethod) }}</td>
            <td class="py-2.5 px-2">
              <div class="flex items-center gap-1.5 min-w-0">
                <span
                  v-if="reviewStatusBadge(file.reviewStatus)"
                  class="inline-block px-1.5 py-0.5 text-[10px] font-medium rounded shrink-0"
                  :class="reviewStatusBadge(file.reviewStatus)!.cls">
                  {{ reviewStatusBadge(file.reviewStatus)!.label }}
                </span>
                <!-- v18.6a — 공유 자산 배지 (asset 매핑 시) -->
                <span
                  v-if="file.assetId"
                  class="inline-block px-1.5 py-0.5 text-[10px] font-medium rounded shrink-0 bg-blue-50 text-blue-700"
                  title="다른 증빙에서도 사용 중일 수 있는 공유 자산">
                  공유
                </span>
                <span class="font-mono text-[11px] truncate" :title="file.fileName">
                  {{ file.fileName }}
                </span>
              </div>
            </td>
              <td class="py-2.5 px-2 text-right">
                <div class="inline-flex gap-1">
                  <button
                    @click="handleDownload(file.id, file.fileName)"
                    class="w-6 h-6 flex items-center justify-center text-gray-400 hover:text-blue-500"
                    title="다운로드">
                    <i class="pi pi-download text-xs"></i>
                  </button>
                  <!-- 최신(v2 등)을 제외하고 삭제 허용 — 최신은 감사 흔적 유지 -->
                  <button
                    v-if="idx !== 0"
                    @click="handleDeleteFile(file.id, file.fileName)"
                    class="w-6 h-6 flex items-center justify-center text-gray-400 hover:text-red-500"
                    title="삭제">
                    <i class="pi pi-trash text-xs"></i>
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- ======================================
           탭 2: 수동 업로드 (v18.6a 액션 분기)
           ====================================== -->
      <div v-show="activeTab === 'upload'" class="py-5">

        <!-- 모드 1: 액션 분기 menu -->
        <div v-if="uploadMode === 'menu'" class="grid grid-cols-1 md:grid-cols-2 gap-3">
          <!-- [새 파일 등록] -->
          <button
            @click="uploadMode = 'new'"
            class="border-2 border-gray-200 rounded-lg p-5 text-left hover:border-gray-900 hover:bg-gray-50 transition-colors">
            <div class="flex items-center gap-2 mb-1.5">
              <i class="pi pi-cloud-upload text-gray-700 text-base"></i>
              <span class="text-sm font-medium text-gray-900">새 파일 등록</span>
            </div>
            <p class="text-[11px] text-gray-500">
              새 파일을 업로드합니다. 같은 내용이 이미 등록된 경우 사용자에게 선택을 묻습니다.
            </p>
          </button>

          <!-- [기존 파일에서 선택] -->
          <button
            @click="showSearchDialog = true"
            class="border-2 border-gray-200 rounded-lg p-5 text-left hover:border-gray-900 hover:bg-gray-50 transition-colors">
            <div class="flex items-center gap-2 mb-1.5">
              <i class="pi pi-search text-gray-700 text-base"></i>
              <span class="text-sm font-medium text-gray-900">기존 파일에서 선택</span>
            </div>
            <p class="text-[11px] text-gray-500">
              이미 등록된 파일을 검색해 이 증빙 유형에 연결합니다.
            </p>
          </button>
        </div>

        <!-- 모드 2: 새 파일 등록 (드래그앤드롭 + 메모 + 업로드 버튼) -->
        <div v-else-if="uploadMode === 'new'">
          <!-- 뒤로 -->
          <button
            @click="resetUploadState"
            class="inline-flex items-center gap-1.5 text-xs text-gray-500 hover:text-gray-900 mb-3">
            <i class="pi pi-chevron-left text-[10px]"></i>
            업로드 방식 다시 선택
          </button>

          <!-- 드래그앤드롭 영역 -->
          <label
            :for="uploadFile ? undefined : 'file-input'"
            @dragover="handleDragOver"
            @dragleave="handleDragLeave"
            @drop="handleDrop"
            class="block border-2 border-dashed rounded-lg py-10 px-4 text-center cursor-pointer transition-colors"
            :class="[
              isDragging
                ? 'border-blue-400 bg-blue-50'
                : uploadFile
                  ? 'border-green-300 bg-green-50'
                  : 'border-gray-300 bg-gray-50 hover:border-gray-400 hover:bg-gray-100'
            ]">
            <i class="pi mb-2 text-2xl"
               :class="uploadFile ? 'pi-check-circle text-green-500' : 'pi-cloud-upload text-gray-400'"></i>
            <template v-if="uploadFile">
              <p class="text-sm text-gray-900 font-medium mb-1">{{ uploadFile.name }}</p>
              <p class="text-[11px] text-gray-500">
                {{ formatFileSize(uploadFile.size) }} · 업로드 준비 완료
              </p>
              <button
                @click.prevent="clearUploadFile"
                class="mt-2 text-[11px] text-gray-500 hover:text-red-600 underline">
                파일 변경 / 삭제
              </button>
            </template>
            <template v-else>
              <p class="text-sm text-gray-600 mb-1">파일을 드래그하거나 클릭해서 업로드</p>
              <p class="text-[11px] text-gray-400">PDF, DOCX, XLSX, PNG, JPG · 최대 50 MB</p>
            </template>
            <input
              v-if="!uploadFile"
              id="file-input"
              ref="fileInputRef"
              type="file"
              class="hidden"
              @change="handleFileSelect" />
          </label>

          <!-- 제출 메모 -->
          <div class="mt-4">
            <label class="block text-xs font-medium text-gray-700 mb-1.5">제출 메모 (선택)</label>
            <textarea
              v-model="uploadSubmitNote"
              rows="2"
              placeholder="이 파일에 대한 설명·변경사항을 간단히 적어주세요"
              class="w-full px-3 py-2 text-sm border border-gray-200 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none resize-none"></textarea>
          </div>

          <!-- 업로드 버튼 -->
          <div class="mt-4">
            <button
              @click="handleUpload(false)"
              :disabled="!uploadFile || uploading"
              class="h-9 px-4 text-sm bg-gray-900 text-white rounded-md hover:bg-gray-800 disabled:bg-gray-300 disabled:cursor-not-allowed inline-flex items-center gap-1.5">
              <i v-if="uploading" class="pi pi-spin pi-spinner text-xs"></i>
              {{ uploading ? '업로드 중…' : '업로드' }}
            </button>
          </div>

          <!-- 안내 -->
          <div class="mt-4 p-3 bg-green-50 border border-green-100 rounded-md text-[11px] text-green-800 flex gap-2 items-start">
            <i class="pi pi-check-circle shrink-0 mt-0.5 text-[11px]"></i>
            <span>
              관리자 업로드는 자동 승인됩니다. 같은 내용의 파일이 이미 등록된 경우 사용자에게 확인을 묻습니다.
            </span>
          </div>
        </div>
      </div>

      <!-- ======================================
           탭 3: 자동 수집
           ====================================== -->
      <div v-show="activeTab === 'auto'" class="py-5">
        <div v-if="jobs.length > 0" class="space-y-2">
          <div
            v-for="job in jobs"
            :key="job.id"
            class="grid gap-3 items-center p-3 bg-white border border-gray-200 rounded-md hover:border-gray-300"
            style="grid-template-columns: 1fr auto auto;">
            <div>
              <div class="text-sm font-medium flex items-center gap-2">
                {{ job.name }}
                <span
                  v-if="!job.isActive"
                  class="inline-block px-1.5 py-0.5 text-[10px] font-medium rounded bg-gray-100 text-gray-500">
                  비활성
                </span>
              </div>
              <div class="text-[11px] text-gray-500 mt-0.5 flex items-center gap-1 flex-wrap">
                <span>{{ job.jobType }}</span>
                <template v-if="job.scheduleCron">· {{ job.scheduleCron }}</template>
                <template v-if="job.lastExecution">
                  · <span class="inline-flex items-center gap-1">
                    <span :class="['inline-block w-1.5 h-1.5 rounded-full', executionDotCls(job.lastExecution.status)]"></span>
                    {{ formatDate(job.lastExecution.createdAt) }}
                    {{ job.lastExecution.status === 'success' ? '성공' :
                       job.lastExecution.status === 'failed' ? '실패' :
                       job.lastExecution.status === 'running' ? '실행 중' : '' }}
                  </span>
                </template>
              </div>
            </div>
            <button
              @click="handleExecuteJob(job.id)"
              class="h-7 px-3 text-[11px] border border-gray-200 bg-white rounded-md text-gray-700 hover:bg-gray-50">
              즉시 실행
            </button>
            <div class="flex gap-1">
              <button
                @click="handleToggleJob(job.id)"
                class="w-6 h-6 flex items-center justify-center border border-gray-200 rounded-md text-gray-500 hover:bg-gray-50"
                :title="job.isActive ? '비활성화' : '활성화'">
                <i class="pi text-xs" :class="job.isActive ? 'pi-pause' : 'pi-play'"></i>
              </button>
              <button
                @click="handleDeleteJob(job.id, job.name)"
                class="w-6 h-6 flex items-center justify-center border border-gray-200 rounded-md text-gray-500 hover:bg-gray-50 hover:text-red-500"
                title="삭제">
                <i class="pi pi-trash text-xs"></i>
              </button>
            </div>
          </div>
        </div>
        <div v-else class="text-center py-8 text-sm text-gray-400 border border-dashed border-gray-200 rounded-lg mb-2">
          <i class="pi pi-clock text-2xl text-gray-300 mb-2"></i>
          <p>연결된 수집 작업이 없습니다</p>
        </div>

        <!-- 수집 작업 추가 버튼 -->
        <button
          @click="openAddJobDialog"
          class="w-full mt-2 h-9 border border-dashed border-gray-300 rounded-md text-sm text-gray-500 hover:bg-gray-50 hover:border-gray-400 inline-flex items-center justify-center gap-1.5">
          <i class="pi pi-plus text-xs"></i>
          수집 작업 추가
        </button>

        <!-- 양방향 동기화 안내 -->
        <div class="mt-4 p-3 bg-blue-50 border border-blue-100 rounded-md text-[11px] text-blue-800 flex gap-2 items-start">
          <i class="pi pi-info-circle shrink-0 mt-0.5 text-[11px]"></i>
          <span>
            여기서 추가한 수집 작업은
            <button
              @click="router.push({ name: 'jobs' })"
              class="font-medium underline hover:text-blue-900">수집 작업</button>
            페이지에서도 조회·수정됩니다 (양방향 동기화).
          </span>
        </div>
      </div>
    </div>

    <!-- 찾을 수 없음 -->
    <div v-else class="bg-white border border-gray-200 rounded-xl p-10 text-center">
      <i class="pi pi-exclamation-circle text-3xl text-gray-300 mb-2"></i>
      <p class="text-sm text-gray-500">증빙 유형을 찾을 수 없습니다.</p>
      <button @click="goBack" class="mt-3 text-sm text-blue-600 hover:underline">
        관리 항목 목록으로 돌아가기
      </button>
    </div>

    <!-- ======================================
         다이얼로그: 수집 작업 추가
         ====================================== -->
    <div
      v-if="showAddJobDialog"
      class="fixed inset-0 bg-black/40 flex items-center justify-center z-50"
      @click.self="showAddJobDialog = false">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-gray-900">수집 작업 추가</h3>
          <button
            @click="showAddJobDialog = false"
            class="p-1 text-gray-400 hover:text-gray-600">
            <i class="pi pi-times"></i>
          </button>
        </div>
        <div class="space-y-3">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">
              작업명 <span class="text-red-500">*</span>
            </label>
            <input
              v-model="newJob.name"
              placeholder="정책서 웹 크롤링"
              class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">설명</label>
            <input
              v-model="newJob.description"
              placeholder="간단한 설명 (선택)"
              class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">
              작업 유형 <span class="text-red-500">*</span>
            </label>
            <select
              v-model="newJob.jobType"
              class="w-full px-3 py-2 border rounded-lg text-sm bg-white focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none">
              <option value="web_scraping">웹 스크래핑</option>
              <option value="excel_extract">엑셀 추출</option>
              <option value="log_extract">로그 추출</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">스크립트</label>

            <!-- v18.8.2 — scriptId 있으면 "작성됨" 표기 + [수정] / [해제] -->
            <div v-if="newJob.scriptId" class="flex items-center gap-2 px-3 py-2 bg-green-50 border border-green-200 rounded-lg">
              <i class="pi pi-check-circle text-green-700"></i>
              <span class="text-sm text-green-800">스크립트 #{{ newJob.scriptId }} 작성됨</span>
              <div class="flex gap-1 ml-auto">
                <button type="button" @click="openScriptEditDialog(newJob.scriptId!)"
                  class="px-2 py-1 text-xs bg-white border border-stone-300 text-stone-700 rounded hover:bg-stone-50">
                  수정
                </button>
                <button type="button" @click="newJob.scriptId = null"
                  class="px-2 py-1 text-xs bg-white border border-red-300 text-red-700 rounded hover:bg-red-50">
                  해제
                </button>
              </div>
            </div>

            <!-- 스크립트 미설정 시 — "작성" 버튼만 -->
            <div v-else>
              <button type="button" @click="openScriptCreateDialog"
                class="w-full px-3 py-2 text-sm bg-white border border-blue-300 text-blue-700 rounded-lg hover:bg-blue-50 inline-flex items-center justify-center gap-1.5">
                <i class="pi pi-pencil text-xs"></i> 스크립트 작성
              </button>
              <p class="text-[11px] text-gray-400 mt-1">
                "작성" 버튼으로 Python 스크립트를 등록하세요. 파일명은 자동 부여됩니다.
              </p>
            </div>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">스케줄 (Cron)</label>
            <input
              v-model="newJob.scheduleCron"
              placeholder="0 9 1 * * (매월 1일 09:00)"
              class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none font-mono" />
            <p class="text-[11px] text-gray-400 mt-1">비워두면 수동 실행 전용 작업이 됩니다.</p>
          </div>
        </div>
        <div class="flex justify-end gap-2 mt-5">
          <button
            @click="showAddJobDialog = false"
            :disabled="addJobSubmitting"
            class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-lg disabled:opacity-50">
            취소
          </button>
          <button
            @click="handleCreateJob"
            :disabled="addJobSubmitting || !newJob.name.trim()"
            class="px-4 py-2 text-sm bg-gray-900 text-white rounded-lg hover:bg-gray-800 disabled:opacity-50 inline-flex items-center gap-1.5">
            <i v-if="addJobSubmitting" class="pi pi-spin pi-spinner text-xs"></i>
            {{ addJobSubmitting ? '생성 중…' : '생성' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ======================================
         v18.6a — Evidence Asset 검색 다이얼로그
         ====================================== -->
    <EvidenceAssetSearchDialog
      :open="showSearchDialog"
      @select="handleSelectAsset"
      @close="showSearchDialog = false" />

    <!-- ======================================
         v18.6a — 중복 감지 confirm 다이얼로그
         ====================================== -->
    <EvidenceAssetDuplicateConfirmDialog
      :open="showDuplicateDialog"
      :existing-asset="duplicateAsset"
      @use-existing="handleUseExisting"
      @force-new="handleForceNew"
      @cancel="handleCancelDuplicate" />

    <!-- ======================================
         v18.8.2 — 스크립트 작성/편집 dialog (UID 기반)
         ====================================== -->
    <ScriptEditorDialog
      v-if="scriptEditorMode"
      :mode="scriptEditorMode"
      :script-id="editingScriptId ?? undefined"
      @close="closeScriptEditor"
      @saved="onScriptSaved" />
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