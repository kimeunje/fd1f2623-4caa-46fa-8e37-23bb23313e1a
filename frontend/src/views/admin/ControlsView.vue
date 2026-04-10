<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { frameworksApi, controlsApi, evidenceFilesApi } from '@/services/evidenceApi'
import type { Framework, ControlItem, ControlDetail, ExcelImportResult } from '@/types/evidence'

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

// 이력 토글: 어떤 증빙유형의 이력이 열려있는지
const expandedHistoryEtIds = ref<Set<number>>(new Set())

// 다이얼로그
const showImportDialog = ref(false)
const showAddControlDialog = ref(false)
const showEditControlDialog = ref(false)
const showAddEtDialog = ref(false)
const showUploadDialog = ref(false)

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

// 파일 업로드
const uploadTargetEtId = ref<number | null>(null)
const uploadFile = ref<File | null>(null)
const uploadLoading = ref(false)

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
  if (statusFilter.value !== '전체') {
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
  const counts: Record<string, number> = { '완료': 0, '진행중': 0, '미수집': 0 }
  controls.value.forEach(c => {
    if (counts[c.status] !== undefined) counts[c.status]++
    else counts[c.status] = 1
  })
  return counts
})

const statusOptions = ['전체', '완료', '진행중', '미수집']

// ========================================
// 데이터 로드
// ========================================

async function loadFrameworks() {
  try {
    const { data } = await frameworksApi.list()
    if (data.success && data.data.length > 0) {
      frameworks.value = data.data
      selectedFrameworkId.value = data.data[0].id
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
    expandedHistoryEtIds.value.clear()
    return
  }
  expandedControlId.value = controlId
  detailLoading.value = true
  expandedHistoryEtIds.value.clear()
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
// 버전 이력 토글 (증빙유형별)
// ========================================

function toggleHistory(etId: number) {
  if (expandedHistoryEtIds.value.has(etId)) {
    expandedHistoryEtIds.value.delete(etId)
  } else {
    expandedHistoryEtIds.value.add(etId)
  }
}

function isHistoryExpanded(etId: number) {
  return expandedHistoryEtIds.value.has(etId)
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
    // 상세도 새로고침
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
// 파일 업로드/다운로드/삭제
// ========================================

function openUpload(etId: number) {
  uploadTargetEtId.value = etId
  uploadFile.value = null
  showUploadDialog.value = true
}

async function handleUpload() {
  if (!uploadTargetEtId.value || !uploadFile.value) return
  uploadLoading.value = true
  try {
    await evidenceFilesApi.upload(uploadTargetEtId.value, uploadFile.value)
    showUploadDialog.value = false
    uploadFile.value = null
    await refreshDetail()
    await loadControls()
    showToast('파일이 업로드되었습니다.', 'success')
  } catch (e) { console.error(e) }
  finally { uploadLoading.value = false }
}

async function handleDownload(fileId: number, fileName: string) {
  try {
    await evidenceFilesApi.download(fileId, fileName)
  } catch (e) {
    console.error('다운로드 실패:', e)
    showToast('파일 다운로드에 실패했습니다.', 'error')
  }
}

async function handleDeleteFile(fileId: number, fileName: string) {
  if (!confirm(`"${fileName}" 파일을 삭제하시겠습니까?`)) return
  try {
    await evidenceFilesApi.delete(fileId)
    await refreshDetail()
    await loadControls()
    showToast('파일이 삭제되었습니다.', 'success')
  } catch (e) {
    console.error(e)
    showToast('파일 삭제에 실패했습니다.', 'error')
  }
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
  return new Date(dateStr).toLocaleDateString('ko')
}

function statusColor(status: string) {
  switch (status) {
    case '완료': return 'bg-green-100 text-green-700'
    case '진행중': return 'bg-amber-100 text-amber-700'
    case '미수집': return 'bg-gray-100 text-gray-500'
    default: return 'bg-gray-100 text-gray-500'
  }
}

function showToast(message: string, type: 'success' | 'error') {
  toast.value = { show: true, message, type }
  setTimeout(() => { toast.value.show = false }, 3000)
}

onMounted(loadFrameworks)
</script>

<template>
  <div class="p-6 space-y-6">
    <!-- ========================================
         토스트 알림
         ======================================== -->
    <Transition name="toast">
      <div v-if="toast.show"
        class="fixed top-4 right-4 z-[100] px-4 py-3 rounded-lg shadow-lg text-sm font-medium flex items-center gap-2"
        :class="toast.type === 'success' ? 'bg-green-600 text-white' : 'bg-red-600 text-white'">
        <i :class="['pi text-sm', toast.type === 'success' ? 'pi-check-circle' : 'pi-times-circle']"></i>
        {{ toast.message }}
      </div>
    </Transition>

    <!-- ========================================
         헤더
         ======================================== -->
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-xl font-bold text-gray-900">통제 항목</h1>
        <p class="text-sm text-gray-500 mt-1">프레임워크별 통제항목 및 증빙 수집 현황을 관리합니다.</p>
      </div>
      <div class="flex gap-2">
        <button @click="showImportDialog = true"
          class="px-4 py-2 bg-white border border-gray-300 text-gray-700 rounded-lg text-sm font-medium hover:bg-gray-50 flex items-center gap-2">
          <i class="pi pi-upload text-sm"></i> 엑셀 Import
        </button>
        <button @click="showAddControlDialog = true"
          class="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 flex items-center gap-2">
          <i class="pi pi-plus text-sm"></i> 통제항목 추가
        </button>
      </div>
    </div>

    <!-- ========================================
         프레임워크 선택 + 검색/필터 + 통계
         ======================================== -->
    <div class="flex items-center justify-between flex-wrap gap-3">
      <div class="flex items-center gap-3">
        <!-- 프레임워크 선택 -->
        <select v-model="selectedFrameworkId" @change="loadControls()"
          class="px-3 py-2 border border-gray-300 rounded-lg text-sm bg-white focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none">
          <option v-for="fw in frameworks" :key="fw.id" :value="fw.id">{{ fw.name }}</option>
        </select>

        <!-- 검색 -->
        <div class="relative">
          <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs"></i>
          <input v-model="searchText" type="text" placeholder="코드, 항목명 검색..."
            class="pl-8 pr-3 py-2 border border-gray-300 rounded-lg text-sm w-56 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" />
        </div>

        <!-- 상태 필터 -->
        <div class="flex bg-gray-100 rounded-lg p-0.5">
          <button v-for="status in statusOptions" :key="status"
            @click="statusFilter = status"
            :class="['px-3 py-1.5 rounded-md text-xs font-medium transition-colors',
              statusFilter === status ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700']">
            {{ status }}
            <span v-if="status !== '전체'" class="ml-1 text-gray-400">{{ statusCounts[status] || 0 }}</span>
          </button>
        </div>
      </div>

      <!-- 통계 -->
      <div class="flex gap-4 text-sm">
        <span class="text-gray-500">전체 <strong class="text-gray-900">{{ controls.length }}</strong></span>
        <span class="text-green-600">완료 <strong>{{ statusCounts['완료'] || 0 }}</strong></span>
        <span class="text-amber-600">진행중 <strong>{{ statusCounts['진행중'] || 0 }}</strong></span>
        <span class="text-gray-400">미수집 <strong>{{ statusCounts['미수집'] || 0 }}</strong></span>
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
            <!-- 통제항목 행 -->
            <tr @click="toggleExpand(ctrl.id)"
              class="border-b border-gray-100 hover:bg-gray-50 cursor-pointer transition-colors"
              :class="expandedControlId === ctrl.id ? 'bg-blue-50/50' : ''">
              <td class="px-4 py-3 text-center">
                <i :class="['pi text-xs text-gray-400 transition-transform duration-200',
                  expandedControlId === ctrl.id ? 'pi-chevron-down' : 'pi-chevron-right']"></i>
              </td>
              <td class="px-4 py-3 text-sm font-mono text-blue-600 font-medium">{{ ctrl.code }}</td>
              <td class="px-4 py-3 text-sm text-gray-500">{{ ctrl.domain || '-' }}</td>
              <td class="px-4 py-3 text-sm text-gray-900 font-medium">{{ ctrl.name }}</td>
              <td class="px-4 py-3 text-center text-sm">
                <div class="flex items-center justify-center gap-2">
                  <span class="text-gray-600">{{ ctrl.evidenceCollected }}/{{ ctrl.evidenceTotal }}</span>
                  <!-- 미니 프로그레스바 -->
                  <div v-if="ctrl.evidenceTotal > 0" class="w-12 h-1.5 bg-gray-200 rounded-full overflow-hidden">
                    <div class="h-full rounded-full transition-all duration-300"
                      :class="ctrl.evidenceCollected === ctrl.evidenceTotal ? 'bg-green-500' : 'bg-blue-500'"
                      :style="{ width: (ctrl.evidenceCollected / ctrl.evidenceTotal * 100) + '%' }"></div>
                  </div>
                </div>
              </td>
              <td class="px-4 py-3 text-center">
                <span :class="['px-2 py-1 rounded text-xs font-medium', statusColor(ctrl.status)]">{{ ctrl.status }}</span>
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
                  <!-- 상세 헤더: 수집 현황 + 액션 버튼 -->
                  <div class="flex items-center justify-between mb-4">
                    <h4 class="text-sm font-bold text-gray-700">
                      수집된 증빙 파일
                      <span class="text-gray-400 font-normal ml-2">
                        {{ controlDetail.evidenceCollected }}/{{ controlDetail.evidenceTotal }}
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
                      class="bg-white rounded-lg border border-gray-200 overflow-hidden transition-shadow hover:shadow-sm">

                      <!-- 증빙 유형 헤더 -->
                      <div class="flex items-center justify-between p-3">
                        <div class="flex items-center gap-2">
                          <i class="pi pi-file text-gray-400"></i>
                          <span class="text-sm font-medium text-gray-900">{{ et.name }}</span>
                          <span v-if="et.collected"
                            class="px-1.5 py-0.5 bg-green-100 text-green-700 text-xs rounded font-medium">수집됨</span>
                          <span v-else
                            class="px-1.5 py-0.5 bg-red-100 text-red-600 text-xs rounded font-medium">미수집</span>
                        </div>
                        <div class="flex items-center gap-1">
                          <!-- 이력 토글 버튼 (파일이 2개 이상일 때만) -->
                          <button v-if="et.files && et.files.length > 1"
                            @click="toggleHistory(et.id)"
                            class="px-2 py-1 text-xs text-blue-600 hover:bg-blue-50 rounded flex items-center gap-1 transition-colors">
                            <i :class="['pi text-xs', isHistoryExpanded(et.id) ? 'pi-chevron-up' : 'pi-chevron-down']"></i>
                            이력 ({{ et.files.length }})
                          </button>
                          <!-- 최신 파일 다운로드 -->
                          <button v-if="et.files && et.files.length > 0"
                            @click="handleDownload(et.files[0].id, et.files[0].fileName)"
                            class="p-1.5 text-gray-400 hover:text-blue-500 transition-colors" title="최신 파일 다운로드">
                            <i class="pi pi-download text-sm"></i>
                          </button>
                          <!-- 파일 업로드 -->
                          <button @click="openUpload(et.id)"
                            class="p-1.5 text-gray-400 hover:text-blue-500 transition-colors" title="파일 업로드">
                            <i class="pi pi-upload text-sm"></i>
                          </button>
                          <!-- 증빙 유형 삭제 -->
                          <button @click="deleteEvidenceType(et.id, et.name)"
                            class="p-1.5 text-gray-400 hover:text-red-500 transition-colors" title="증빙 유형 삭제">
                            <i class="pi pi-trash text-sm"></i>
                          </button>
                        </div>
                      </div>

                      <!-- 최신 파일 정보 (항상 표시) -->
                      <div v-if="et.files && et.files.length > 0" class="px-3 pb-2">
                        <div class="flex items-center gap-2 text-xs text-gray-500 ml-6">
                          <span class="text-blue-600 font-medium">v{{ et.files[0].version }}</span>
                          <span class="text-gray-300">·</span>
                          <span>{{ formatFileSize(et.files[0].fileSize) }}</span>
                          <span class="text-gray-300">·</span>
                          <span>{{ formatDate(et.files[0].collectedAt) }}</span>
                          <span class="text-gray-300">·</span>
                          <span :class="et.files[0].collectionMethod === 'auto' ? 'text-green-600' : 'text-gray-400'">
                            {{ et.files[0].collectionMethod === 'auto' ? '자동수집' : '수동업로드' }}
                          </span>
                        </div>
                      </div>

                      <!-- 버전 이력 (토글로 펼치기) -->
                      <Transition name="slide">
                        <div v-if="et.files && et.files.length > 1 && isHistoryExpanded(et.id)"
                          class="border-t border-gray-100 bg-gray-50/50">
                          <div class="px-3 py-2">
                            <p class="text-xs font-semibold text-gray-500 mb-2 ml-6">버전 이력</p>
                            <div class="space-y-1">
                              <div v-for="(file, idx) in et.files" :key="file.id"
                                class="flex items-center justify-between text-xs py-1.5 px-2 ml-4 rounded hover:bg-white transition-colors"
                                :class="idx === 0 ? 'bg-white border border-blue-100' : ''">
                                <div class="flex items-center gap-2 text-gray-500">
                                  <span :class="idx === 0 ? 'text-blue-600 font-semibold' : 'text-gray-600'">
                                    v{{ file.version }}{{ idx === 0 ? ' (최신)' : '' }}
                                  </span>
                                  <span>{{ formatDate(file.collectedAt) }}</span>
                                  <span>{{ formatFileSize(file.fileSize) }}</span>
                                  <span :class="file.collectionMethod === 'auto' ? 'text-green-600' : 'text-gray-400'">
                                    {{ file.collectionMethod === 'auto' ? '자동수집' : '수동업로드' }}
                                  </span>
                                </div>
                                <div class="flex items-center gap-0.5">
                                  <button @click.stop="handleDownload(file.id, file.fileName)"
                                    class="p-1 text-gray-400 hover:text-blue-500 transition-colors" title="다운로드">
                                    <i class="pi pi-download text-xs"></i>
                                  </button>
                                  <button @click.stop="handleDeleteFile(file.id, file.fileName)"
                                    class="p-1 text-gray-400 hover:text-red-500 transition-colors" title="삭제">
                                    <i class="pi pi-trash text-xs"></i>
                                  </button>
                                </div>
                              </div>
                            </div>
                          </div>
                        </div>
                      </Transition>
                    </div>
                  </div>

                  <!-- 증빙유형이 하나도 없을 때 -->
                  <div v-if="controlDetail.evidenceTypes.length === 0"
                    class="text-center py-6 text-gray-400 text-sm">
                    등록된 증빙 유형이 없습니다. 증빙 유형을 추가해주세요.
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
            class="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50">
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
            class="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50">추가</button>
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
            class="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50">저장</button>
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
            class="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50">추가</button>
        </div>
      </div>
    </div>

    <!-- ========================================
         다이얼로그: 파일 업로드
         ======================================== -->
    <div v-if="showUploadDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50" @click.self="showUploadDialog = false">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-sm p-6">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-gray-900">증빙 파일 업로드</h3>
          <button @click="showUploadDialog = false" class="p-1 text-gray-400 hover:text-gray-600">
            <i class="pi pi-times"></i>
          </button>
        </div>
        <div class="border-2 border-dashed border-gray-300 rounded-lg p-6 text-center mb-4 hover:border-blue-400 transition-colors">
          <input type="file" @change="(e: any) => uploadFile = e.target.files?.[0]"
            class="w-full text-sm cursor-pointer" />
          <p v-if="uploadFile" class="text-xs text-gray-500 mt-2">
            {{ uploadFile.name }} ({{ formatFileSize(uploadFile.size) }})
          </p>
        </div>
        <div class="flex justify-end gap-2">
          <button @click="showUploadDialog = false" class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-lg">취소</button>
          <button @click="handleUpload" :disabled="uploadLoading || !uploadFile"
            class="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50">
            {{ uploadLoading ? '업로드 중...' : '업로드' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 토스트 애니메이션 */
.toast-enter-active { animation: slideIn 0.3s ease; }
.toast-leave-active { animation: slideOut 0.3s ease; }
@keyframes slideIn { from { transform: translateX(100%); opacity: 0; } to { transform: translateX(0); opacity: 1; } }
@keyframes slideOut { from { transform: translateX(0); opacity: 1; } to { transform: translateX(100%); opacity: 0; } }

/* 이력 슬라이드 애니메이션 */
.slide-enter-active { transition: all 0.2s ease; max-height: 500px; }
.slide-leave-active { transition: all 0.2s ease; max-height: 0; }
.slide-enter-from, .slide-leave-to { max-height: 0; opacity: 0; overflow: hidden; }
.slide-enter-to, .slide-leave-from { max-height: 500px; opacity: 1; }
</style>