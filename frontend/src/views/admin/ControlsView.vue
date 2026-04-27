<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { frameworksApi, controlsApi, evidenceFilesApi } from '@/services/evidenceApi'
import type {
  Framework, ControlItem, ControlDetail, ExcelImportResult,
  ReviewStatus,
} from '@/types/evidence'

// ========================================
// Props (v11 Phase 5-3: 라우트 /controls/:frameworkId 에서 전달)
// ========================================
const props = defineProps<{
  frameworkId?: number
}>()

// ========================================
// 상태 정의
// ========================================

// 프레임워크
const frameworks = ref<Framework[]>([])
const selectedFrameworkId = ref<number | null>(null)

// 통제항목
const controls = ref<ControlItem[]>([])
const loading = ref(false)

// 확장 & 상세
const expandedControlId = ref<number | null>(null)
const controlDetail = ref<ControlDetail | null>(null)
const detailLoading = ref(false)

// 다이얼로그
const showImportDialog = ref(false)
const showAddControlDialog = ref(false)
const showEditControlDialog = ref(false)
const showAddEtDialog = ref(false)

// Import
const importFile = ref<File | null>(null)
const importLoading = ref(false)
const importResult = ref<ExcelImportResult | null>(null)

// 통제항목 추가
const newControl = ref({ code: '', domain: '', name: '', description: '', evidenceTypes: '' })

// 통제항목 수정
const editControl = ref({ id: 0, code: '', domain: '', name: '', description: '' })

// 증빙 유형 추가
const newEtName = ref('')

// ZIP 다운로드
const zipDownloading = ref(false)

// 검색 / 필터
const searchText = ref('')
const statusFilter = ref('전체')

// 알림 토스트
const toast = ref({ show: false, message: '', type: 'success' as 'success' | 'error' })

// ========================================
// Computed
// ========================================

const filteredControls = computed(() => {
  let result = controls.value

  // 상태 필터
  if (statusFilter.value === '검토 대기') {
    // Phase 5-9: pending 건이 있는 행만
    result = result.filter(ctrl => (ctrl.pendingReviewCount ?? 0) > 0)
  } else if (statusFilter.value !== '전체') {
    result = result.filter(ctrl => ctrl.status === statusFilter.value)
  }

  // 텍스트 검색 (코드, 항목명, 영역)
  if (searchText.value.trim()) {
    const q = searchText.value.trim().toLowerCase()
    result = result.filter(ctrl =>
      ctrl.code.toLowerCase().includes(q) ||
      ctrl.name.toLowerCase().includes(q) ||
      (ctrl.domain || '').toLowerCase().includes(q)
    )
  }

  return result
})

const statusCounts = computed(() => {
  const counts: Record<string, number> = { '전체': controls.value.length, '완료': 0, '진행중': 0, '미수집': 0, '검토 대기': 0 }
  controls.value.forEach(c => {
    if (counts[c.status] !== undefined) counts[c.status]++
    else counts[c.status] = 1
    // Phase 5-9: 검토 대기는 별도 카운트 (다른 상태와 공존 가능)
    if ((c.pendingReviewCount ?? 0) > 0) counts['검토 대기']++
  })
  return counts
})

// Phase 5-9: "검토 대기" 탭 추가
const statusOptions = ['전체', '완료', '진행중', '미수집', '검토 대기']

// ========================================
// 데이터 로드
// ========================================

async function loadFrameworks() {
  try {
    const { data } = await frameworksApi.list()
    if (data.success && data.data.length > 0) {
      frameworks.value = data.data

      // v11 Phase 5-3: URL 의 frameworkId 를 우선. 없으면 첫 번째 Framework.
      const routeFwId = props.frameworkId
      const matched = routeFwId != null
        ? frameworks.value.find(f => f.id === routeFwId)
        : null

      selectedFrameworkId.value = matched ? matched.id : frameworks.value[0].id
      await loadControls()
    }
  } catch (e) { console.error(e) }
}

async function loadControls() {
  if (!selectedFrameworkId.value) return
  loading.value = true
  try {
    const { data } = await controlsApi.listByFramework(selectedFrameworkId.value)
    if (data.success) controls.value = data.data
  } catch (e) { console.error(e) }
  finally { loading.value = false }
}

// ========================================
// 통제항목 확장 → 상세 로드
// ========================================

async function toggleExpand(controlId: number) {
  if (expandedControlId.value === controlId) {
    expandedControlId.value = null
    controlDetail.value = null
    return
  }
  expandedControlId.value = controlId
  detailLoading.value = true
  try {
    const { data } = await controlsApi.getDetail(controlId)
    if (data.success) controlDetail.value = data.data
  } catch (e) { console.error(e) }
  finally { detailLoading.value = false }
}

// 상세 새로고침 (데이터 변경 후)
async function refreshDetail() {
  if (!expandedControlId.value) return
  try {
    const { data } = await controlsApi.getDetail(expandedControlId.value)
    if (data.success) controlDetail.value = data.data
  } catch (e) { console.error(e) }
}

// ========================================
// ZIP 다운로드
// ========================================

async function handleZipDownload() {
  if (!expandedControlId.value || !controlDetail.value) return
  zipDownloading.value = true
  try {
    await evidenceFilesApi.downloadZip(expandedControlId.value, controlDetail.value.code)
    showToast('ZIP 다운로드가 완료되었습니다.', 'success')
  } catch (e) {
    console.error('ZIP 다운로드 실패:', e)
    showToast('ZIP 다운로드에 실패했습니다.', 'error')
  } finally {
    zipDownloading.value = false
  }
}

// ========================================
// 엑셀 Import
// ========================================

async function handleImport() {
  if (!importFile.value || !selectedFrameworkId.value) return
  importLoading.value = true
  try {
    const { data } = await frameworksApi.importControls(selectedFrameworkId.value, importFile.value)
    if (data.success) {
      importResult.value = data.data
      await loadControls()
      showToast(`Import 완료: ${data.data.successCount}건 성공`, 'success')
    }
  } catch (e) { console.error(e) }
  finally { importLoading.value = false }
}

// ========================================
// 통제항목 CRUD
// ========================================

async function handleAddControl() {
  if (!selectedFrameworkId.value) return
  const etList = newControl.value.evidenceTypes
    .split(',')
    .map(s => s.trim())
    .filter(Boolean)
    .map(name => ({ name }))

  try {
    await controlsApi.create(selectedFrameworkId.value, {
      code: newControl.value.code,
      domain: newControl.value.domain || undefined,
      name: newControl.value.name,
      description: newControl.value.description || undefined,
      evidenceTypes: etList.length > 0 ? etList : undefined,
    })
    showAddControlDialog.value = false
    newControl.value = { code: '', domain: '', name: '', description: '', evidenceTypes: '' }
    await loadControls()
    showToast('통제항목이 추가되었습니다.', 'success')
  } catch (e) { console.error(e) }
}

function openEditControl(ctrl: ControlItem) {
  editControl.value = {
    id: ctrl.id,
    code: ctrl.code,
    domain: ctrl.domain || '',
    name: ctrl.name,
    description: ctrl.description || '',
  }
  showEditControlDialog.value = true
}

async function handleEditControl() {
  try {
    await controlsApi.update(editControl.value.id, {
      code: editControl.value.code,
      domain: editControl.value.domain || undefined,
      name: editControl.value.name,
      description: editControl.value.description || undefined,
    })
    showEditControlDialog.value = false
    await loadControls()
    if (expandedControlId.value === editControl.value.id) await refreshDetail()
    showToast('통제항목이 수정되었습니다.', 'success')
  } catch (e) { console.error(e) }
}

async function deleteControl(id: number) {
  if (!confirm('이 통제항목을 삭제하시겠습니까?\n관련된 증빙 유형과 파일도 함께 삭제됩니다.')) return
  try {
    await controlsApi.delete(id)
    if (expandedControlId.value === id) {
      expandedControlId.value = null
      controlDetail.value = null
    }
    await loadControls()
    showToast('통제항목이 삭제되었습니다.', 'success')
  } catch (e) { console.error(e) }
}

// ========================================
// 증빙 유형 추가/삭제
// ========================================

async function handleAddEvidenceType() {
  if (!expandedControlId.value || !newEtName.value.trim()) return
  try {
    await controlsApi.addEvidenceType(expandedControlId.value, { name: newEtName.value.trim() })
    newEtName.value = ''
    showAddEtDialog.value = false
    await refreshDetail()
    await loadControls()
    showToast('증빙 유형이 추가되었습니다.', 'success')
  } catch (e) { console.error(e) }
}

async function deleteEvidenceType(etId: number, etName: string) {
  if (!confirm(`"${etName}" 증빙 유형을 삭제하시겠습니까?\n관련된 파일 이력도 함께 삭제됩니다.`)) return
  try {
    await controlsApi.deleteEvidenceType(etId)
    await refreshDetail()
    await loadControls()
    showToast('증빙 유형이 삭제되었습니다.', 'success')
  } catch (e) { console.error(e) }
}

// ========================================
// v11 Phase 5-9 — 배지 헬퍼
// ========================================

/**
 * 최신 파일의 reviewStatus 기반으로 증빙 유형 카드 배지 결정.
 * 파일이 하나도 없으면 null 리턴 (호출측에서 "미수집" 처리).
 */
function reviewStatusBadge(status?: ReviewStatus): { label: string; cls: string } | null {
  switch (status) {
    case 'pending':
      return { label: '● 검토 대기', cls: 'bg-blue-100 text-blue-700' }
    case 'approved':
      return { label: '승인', cls: 'bg-green-100 text-green-700' }
    case 'rejected':
      return { label: '반려', cls: 'bg-red-100 text-red-700' }
    case 'auto_approved':
      return { label: '자동 승인', cls: 'bg-gray-100 text-gray-600' }
    default:
      return null
  }
}

/**
 * 통제 항목 행의 상태 배지.
 * pendingReviewCount > 0 이면 "● 검토 대기 N" 로 오버라이드 (v11 기획서 §3.1.2).
 */
function rowStatusBadge(ctrl: ControlItem): { label: string; cls: string } {
  if ((ctrl.pendingReviewCount ?? 0) > 0) {
    return { label: `● 검토 대기 ${ctrl.pendingReviewCount}`, cls: 'bg-blue-100 text-blue-700' }
  }
  return { label: ctrl.status, cls: statusColor(ctrl.status) }
}

// ========================================
// 유틸리티
// ========================================

function formatFileSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function formatDateTime(dateStr?: string) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${day} ${hh}:${mm}`
}

function statusColor(status: string) {
  if (status === '완료') return 'bg-green-100 text-green-700'
  if (status === '진행중') return 'bg-amber-100 text-amber-700'
  return 'bg-gray-100 text-gray-500'
}

function showToast(message: string, type: 'success' | 'error' = 'success') {
  toast.value = { show: true, message, type }
  setTimeout(() => { toast.value.show = false }, 3000)
}

// ========================================
// 초기화 & 라우트 prop 변경 대응
// ========================================

const router = useRouter()

// ========================================
// v11 Phase 5-12 — 증빙 유형 상세 페이지 이동
// ========================================
function goToEvidenceTypeDetail(evidenceTypeId: number) {
  if (!expandedControlId.value || !selectedFrameworkId.value) return
  router.push({
    name: 'evidence-type-detail',
    params: {
      frameworkId: selectedFrameworkId.value,
      controlId: expandedControlId.value,
      evidenceTypeId,
    },
  })
}

onMounted(() => {
  loadFrameworks()
})

// v11 Phase 5-3: URL 의 frameworkId 가 바뀌면 해당 Framework 로 전환
watch(() => props.frameworkId, (newId) => {
  if (newId != null && frameworks.value.length > 0) {
    const match = frameworks.value.find(f => f.id === newId)
    if (match && match.id !== selectedFrameworkId.value) {
      selectedFrameworkId.value = match.id
      expandedControlId.value = null
      controlDetail.value = null
      loadControls()
    }
  }
})
</script>

<template>
  <div class="p-6 space-y-4">
    <!-- 토스트 -->
    <Transition name="toast">
      <div v-if="toast.show"
        class="fixed top-6 right-6 z-[60] px-4 py-3 rounded-lg shadow-lg text-sm font-medium flex items-center gap-2"
        :class="toast.type === 'success' ? 'bg-green-600 text-white' : 'bg-red-600 text-white'">
        <i :class="['pi text-sm', toast.type === 'success' ? 'pi-check-circle' : 'pi-times-circle']"></i>
        {{ toast.message }}
      </div>
    </Transition>

    <!-- ========================================
         검색/필터 + 액션 (Phase 5-13d: 헤더 통합)
         좌측 = 보기 컨트롤 (검색 / 상태 필터, 탭에 카운트 포함)
         우측 = 페이지 액션 (Import / 통제항목 추가)
         AppHeader 브레드크럼이 Framework 이름·드롭다운을 흡수.
         ======================================== -->
    <div class="flex items-center justify-between flex-wrap gap-3">
      <div class="flex items-center gap-3 flex-wrap">
        <!-- 검색 -->
        <div class="relative">
          <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs"></i>
          <input v-model="searchText" type="text" placeholder="코드, 항목명 검색..."
            class="pl-8 pr-3 h-9 border border-gray-300 rounded-lg text-sm w-56 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" />
        </div>

        <!-- 상태 필터 (Phase 5-9: "검토 대기" 탭 추가) -->
        <div class="flex bg-gray-100 rounded-lg p-0.5">
          <button v-for="status in statusOptions" :key="status"
            @click="statusFilter = status"
            :class="['px-3 py-1.5 rounded-md text-xs font-medium transition-colors',
              statusFilter === status
                ? (status === '검토 대기' ? 'bg-white text-blue-700 shadow-sm' : 'bg-white text-gray-900 shadow-sm')
                : (status === '검토 대기' ? 'text-blue-600 hover:text-blue-700' : 'text-gray-500 hover:text-gray-700')]">
            {{ status }}
            <span class="ml-1"
              :class="status === '검토 대기' ? 'text-blue-400' : 'text-gray-400'">
              {{ statusCounts[status] || 0 }}
            </span>
          </button>
        </div>
      </div>

      <!-- 액션 버튼 (Phase 5-13d: 헤더에서 이동) -->
      <div class="flex items-center gap-2">
        <button @click="showImportDialog = true"
          class="h-9 px-3 bg-white border border-gray-300 text-gray-700 rounded-lg text-sm font-medium hover:bg-gray-50 flex items-center gap-1.5">
          <i class="pi pi-upload text-xs"></i> 엑셀 Import
        </button>
        <button @click="showAddControlDialog = true"
          class="h-9 px-3 bg-gray-900 text-white rounded-lg text-sm font-medium hover:bg-gray-800 flex items-center gap-1.5">
          <i class="pi pi-plus text-xs"></i> 통제항목 추가
        </button>
      </div>
    </div>

    <!-- ========================================
         테이블
         ======================================== -->
    <div class="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <!-- 로딩 -->
      <div v-if="loading" class="p-12 text-center text-gray-400">
        <i class="pi pi-spin pi-spinner text-2xl mb-2"></i>
        <p class="text-sm">로딩 중...</p>
      </div>

      <table v-else class="w-full">
        <thead>
          <tr class="border-b border-gray-200 bg-gray-50">
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500 w-10"></th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">코드</th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">영역</th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">항목명</th>
            <th class="px-4 py-3 text-center text-xs font-semibold text-gray-500">수집현황</th>
            <th class="px-4 py-3 text-center text-xs font-semibold text-gray-500">상태</th>
            <th class="px-4 py-3 text-center text-xs font-semibold text-gray-500 w-24">관리</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="ctrl in filteredControls" :key="ctrl.id">
            <!-- 통제항목 행 (Phase 5-9: pendingReviewCount > 0 면 파란 배경 강조) -->
            <tr @click="toggleExpand(ctrl.id)"
              class="border-b border-gray-100 cursor-pointer transition-colors"
              :class="[
                expandedControlId === ctrl.id
                  ? 'bg-blue-50/60'
                  : ((ctrl.pendingReviewCount ?? 0) > 0 ? 'bg-blue-50/30 hover:bg-blue-50/50' : 'hover:bg-gray-50')
              ]">
              <td class="px-4 py-3 text-center">
                <i :class="['pi text-xs text-gray-400 transition-transform duration-200',
                  expandedControlId === ctrl.id ? 'pi-chevron-down' : 'pi-chevron-right']"></i>
              </td>
              <td class="px-4 py-3 text-sm font-mono text-blue-600">{{ ctrl.code }}</td>
              <td class="px-4 py-3 text-sm text-gray-600">{{ ctrl.domain || '-' }}</td>
              <td class="px-4 py-3 text-sm text-gray-900">{{ ctrl.name }}</td>
              <td class="px-4 py-3 text-center">
                <div class="flex items-center justify-center gap-2">
                  <span class="text-sm text-gray-600">{{ ctrl.evidenceCollected }}/{{ ctrl.evidenceTotal }}</span>
                  <div class="w-16 h-1.5 bg-gray-200 rounded-full overflow-hidden">
                    <div class="h-full rounded-full transition-all"
                      :class="ctrl.evidenceCollected >= ctrl.evidenceTotal && ctrl.evidenceTotal > 0
                        ? 'bg-green-500' : 'bg-blue-500'"
                      :style="{ width: ctrl.evidenceTotal > 0
                        ? (ctrl.evidenceCollected / ctrl.evidenceTotal * 100) + '%'
                        : '0%' }"></div>
                  </div>
                </div>
              </td>
              <td class="px-4 py-3 text-center">
                <!-- Phase 5-9: rowStatusBadge 가 pending 존재 시 "● 검토 대기 N" 로 오버라이드 -->
                <span :class="['px-2 py-1 rounded text-xs font-medium', rowStatusBadge(ctrl).cls]">
                  {{ rowStatusBadge(ctrl).label }}
                </span>
              </td>
              <td class="px-4 py-3 text-center" @click.stop>
                <div class="flex items-center justify-center gap-1">
                  <button @click="openEditControl(ctrl)" class="p-1.5 text-gray-400 hover:text-blue-500 rounded" title="수정">
                    <i class="pi pi-pencil text-xs"></i>
                  </button>
                  <button @click="deleteControl(ctrl.id)" class="p-1.5 text-gray-400 hover:text-red-500 rounded" title="삭제">
                    <i class="pi pi-trash text-xs"></i>
                  </button>
                </div>
              </td>
            </tr>

            <!-- ========================================
                 증빙 상세 (확장 영역)
                 ======================================== -->
            <tr v-if="expandedControlId === ctrl.id">
              <td colspan="7" class="px-6 py-4 bg-blue-50/30 border-b border-gray-200">
                <!-- 로딩 -->
                <div v-if="detailLoading" class="text-center py-6 text-gray-400 text-sm">
                  <i class="pi pi-spin pi-spinner mr-2"></i>로딩 중...
                </div>

                <div v-else-if="controlDetail">
                  <!-- 상세 헤더: 수집 현황 + 검토 대기 + 액션 버튼 -->
                  <div class="flex items-center justify-between mb-4">
                    <h4 class="text-sm font-bold text-gray-700">
                      수집된 증빙 파일
                      <span class="text-gray-400 font-normal ml-2">
                        {{ controlDetail.evidenceCollected }}/{{ controlDetail.evidenceTotal }}
                      </span>
                      <!-- Phase 5-9: 검토 대기 N건 배지 -->
                      <span v-if="(ctrl.pendingReviewCount ?? 0) > 0"
                        class="ml-2 px-2 py-0.5 text-xs font-medium rounded bg-blue-100 text-blue-700">
                        · 검토 대기 {{ ctrl.pendingReviewCount }}건
                      </span>
                    </h4>
                    <div class="flex items-center gap-2">
                      <button @click="handleZipDownload"
                        :disabled="zipDownloading || controlDetail.evidenceCollected === 0"
                        class="px-3 py-1.5 text-xs bg-white border border-gray-300 rounded-lg hover:bg-gray-50 flex items-center gap-1.5 disabled:opacity-40 disabled:cursor-not-allowed transition-colors">
                        <i :class="['pi text-xs', zipDownloading ? 'pi-spin pi-spinner' : 'pi-download']"></i>
                        {{ zipDownloading ? '다운로드 중...' : '전체 다운로드 (ZIP)' }}
                      </button>
                      <button @click="showAddEtDialog = true"
                        class="px-3 py-1.5 text-xs bg-white border border-gray-300 rounded-lg hover:bg-gray-50 flex items-center gap-1.5 transition-colors">
                        <i class="pi pi-plus text-xs"></i> 증빙 유형 추가
                      </button>
                    </div>
                  </div>

                  <!-- 증빙 유형별 목록 -->
                  <div class="space-y-2">
                    <div v-for="et in controlDetail.evidenceTypes" :key="et.id"
                      class="bg-white rounded-lg border overflow-hidden transition-shadow hover:shadow-sm"
                      :class="(et.files && et.files.length > 0 && et.files[0].reviewStatus === 'pending')
                        ? 'border-blue-200'
                        : 'border-gray-200'">

                      <!-- v11 Phase 5-12b — 단순 카드: 전체 클릭 → 상세 페이지, 우측에 삭제만 유지 -->
                      <div class="flex items-center p-3 gap-2 group transition-colors"
                        :class="(et.files && et.files.length > 0 && et.files[0].reviewStatus === 'pending')
                          ? 'bg-blue-50/40'
                          : ''">
                        <button
                          @click="goToEvidenceTypeDetail(et.id)"
                          class="flex items-center gap-2 flex-1 min-w-0 text-left"
                          :title="`${et.name} 상세 보기`">
                          <i class="pi pi-file text-gray-400 shrink-0 group-hover:text-blue-500 transition-colors"></i>
                          <span class="text-sm font-medium text-gray-900 group-hover:text-blue-600 transition-colors shrink-0">{{ et.name }}</span>

                          <!-- Phase 5-9: 상태 배지 (파일 존재 여부 + reviewStatus 기반) -->
                          <template v-if="et.files && et.files.length > 0">
                            <span v-if="reviewStatusBadge(et.files[0].reviewStatus)"
                              class="px-1.5 py-0.5 text-xs rounded font-medium shrink-0"
                              :class="reviewStatusBadge(et.files[0].reviewStatus)!.cls">
                              {{ reviewStatusBadge(et.files[0].reviewStatus)!.label }}
                            </span>
                          </template>
                          <span v-else
                            class="px-1.5 py-0.5 bg-red-100 text-red-600 text-xs rounded font-medium shrink-0">
                            미수집
                          </span>

                          <!-- 최신 파일 메타 (업로더 + 시각) -->
                          <span v-if="et.files && et.files.length > 0"
                            class="text-xs text-gray-500 truncate">
                            v{{ et.files[0].version }} · {{ formatFileSize(et.files[0].fileSize) }}
                            <template v-if="et.files[0].uploadedByName">
                              · {{ et.files[0].uploadedByName }}
                            </template>
                            · {{ formatDateTime(et.files[0].createdAt) }}
                          </span>

                          <i class="pi pi-chevron-right text-xs text-gray-300 group-hover:text-gray-500 ml-auto shrink-0 transition-colors"></i>
                        </button>

                        <!-- 증빙 유형 자체 삭제 (이벤트 전파 차단) -->
                        <button
                          @click.stop="deleteEvidenceType(et.id, et.name)"
                          class="p-1.5 text-gray-400 hover:text-red-500 transition-colors shrink-0"
                          title="증빙 유형 삭제">
                          <i class="pi pi-trash text-sm"></i>
                        </button>
                      </div>

                    </div>

                    <!-- 증빙 유형이 하나도 없는 경우 -->
                    <div v-if="controlDetail.evidenceTypes.length === 0"
                      class="text-center py-8 text-sm text-gray-400 border border-dashed border-gray-200 rounded-lg">
                      등록된 증빙 유형이 없습니다. 위 [증빙 유형 추가] 버튼으로 추가해주세요.
                    </div>
                  </div>
                </div>
              </td>
            </tr>
          </template>

          <!-- 데이터 없음 -->
          <tr v-if="!loading && filteredControls.length === 0">
            <td colspan="7" class="px-4 py-12 text-center text-gray-400 text-sm">
              <template v-if="controls.length === 0">
                등록된 통제항목이 없습니다. 엑셀 Import 또는 직접 추가해주세요.
              </template>
              <template v-else>
                검색 조건에 맞는 통제항목이 없습니다.
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ========================================
         다이얼로그: 엑셀 Import
         ======================================== -->
    <div v-if="showImportDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50" @click.self="showImportDialog = false">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-gray-900">통제항목 엑셀 Import</h3>
          <button @click="showImportDialog = false; importResult = null" class="p-1 text-gray-400 hover:text-gray-600">
            <i class="pi pi-times"></i>
          </button>
        </div>
        <p class="text-sm text-gray-500 mb-4">
          엑셀 파일 컬럼: 코드 | 영역 | 항목명 | 설명 | 필요 증빙 (쉼표 구분)
        </p>
        <input type="file" accept=".xlsx,.xls" @change="(e: any) => importFile = e.target.files?.[0]"
          class="w-full text-sm mb-4" />

        <div v-if="importResult" class="mb-4 p-3 bg-gray-50 rounded-lg text-sm">
          <p>전체 {{ importResult.totalRows }}행 / 성공 <strong class="text-green-600">{{ importResult.successCount }}</strong> / 실패 <strong class="text-red-600">{{ importResult.failCount }}</strong></p>
          <ul v-if="importResult.errors.length > 0" class="mt-2 text-xs text-red-500 space-y-0.5">
            <li v-for="(err, i) in importResult.errors" :key="i">{{ err }}</li>
          </ul>
        </div>

        <div class="flex justify-end gap-2">
          <button @click="showImportDialog = false; importResult = null"
            class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-lg">취소</button>
          <button @click="handleImport" :disabled="importLoading || !importFile"
            class="px-4 py-2 text-sm bg-gray-900 text-white rounded-lg hover:bg-gray-800 disabled:opacity-50">
            {{ importLoading ? 'Import 중...' : 'Import' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ========================================
         다이얼로그: 통제항목 추가
         ======================================== -->
    <div v-if="showAddControlDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50" @click.self="showAddControlDialog = false">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-gray-900">통제항목 추가</h3>
          <button @click="showAddControlDialog = false" class="p-1 text-gray-400 hover:text-gray-600">
            <i class="pi pi-times"></i>
          </button>
        </div>
        <div class="space-y-3">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">코드 *</label>
            <input v-model="newControl.code" class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" placeholder="1.1.1" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">영역</label>
            <input v-model="newControl.domain" class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" placeholder="관리체계 수립" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">항목명 *</label>
            <input v-model="newControl.name" class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" placeholder="정보보호 정책 수립" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">설명</label>
            <textarea v-model="newControl.description" rows="2" class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"></textarea>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">필요 증빙 (쉼표 구분)</label>
            <input v-model="newControl.evidenceTypes" class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              placeholder="정보보호 정책서, 개인정보 처리방침" />
          </div>
        </div>
        <div class="flex justify-end gap-2 mt-4">
          <button @click="showAddControlDialog = false" class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-lg">취소</button>
          <button @click="handleAddControl" :disabled="!newControl.code || !newControl.name"
            class="px-4 py-2 text-sm bg-gray-900 text-white rounded-lg hover:bg-gray-800 disabled:opacity-50">추가</button>
        </div>
      </div>
    </div>

    <!-- ========================================
         다이얼로그: 통제항목 수정
         ======================================== -->
    <div v-if="showEditControlDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50" @click.self="showEditControlDialog = false">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-gray-900">통제항목 수정</h3>
          <button @click="showEditControlDialog = false" class="p-1 text-gray-400 hover:text-gray-600">
            <i class="pi pi-times"></i>
          </button>
        </div>
        <div class="space-y-3">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">코드 *</label>
            <input v-model="editControl.code" class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">영역</label>
            <input v-model="editControl.domain" class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">항목명 *</label>
            <input v-model="editControl.name" class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">설명</label>
            <textarea v-model="editControl.description" rows="2" class="w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"></textarea>
          </div>
        </div>
        <div class="flex justify-end gap-2 mt-4">
          <button @click="showEditControlDialog = false" class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-lg">취소</button>
          <button @click="handleEditControl" :disabled="!editControl.code || !editControl.name"
            class="px-4 py-2 text-sm bg-gray-900 text-white rounded-lg hover:bg-gray-800 disabled:opacity-50">저장</button>
        </div>
      </div>
    </div>

    <!-- ========================================
         다이얼로그: 증빙 유형 추가
         ======================================== -->
    <div v-if="showAddEtDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50" @click.self="showAddEtDialog = false">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-sm p-6">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-gray-900">증빙 유형 추가</h3>
          <button @click="showAddEtDialog = false" class="p-1 text-gray-400 hover:text-gray-600">
            <i class="pi pi-times"></i>
          </button>
        </div>
        <input v-model="newEtName" class="w-full px-3 py-2 border rounded-lg text-sm mb-4 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
          placeholder="증빙 유형 이름" @keyup.enter="handleAddEvidenceType" />
        <div class="flex justify-end gap-2">
          <button @click="showAddEtDialog = false" class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-lg">취소</button>
          <button @click="handleAddEvidenceType" :disabled="!newEtName.trim()"
            class="px-4 py-2 text-sm bg-gray-900 text-white rounded-lg hover:bg-gray-800 disabled:opacity-50">추가</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 토스트 애니메이션 */
.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}

/* 슬라이드 애니메이션 (이력 / 검토 패널 공용) */
.slide-enter-active,
.slide-leave-active {
  transition: all 0.2s ease;
}
.slide-enter-from,
.slide-leave-to {
  opacity: 0;
  max-height: 0;
  overflow: hidden;
}
.slide-enter-to,
.slide-leave-from {
  opacity: 1;
  max-height: 1200px;
}
</style>