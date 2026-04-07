<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { frameworksApi, controlsApi, evidenceFilesApi } from '@/services/evidenceApi'
import type { Framework, ControlItem, ControlDetail, EvidenceFileItem } from '@/types/evidence'

// ========================================
// State
// ========================================
const frameworks = ref<Framework[]>([])
const selectedFrameworkId = ref<number | null>(null)
const controls = ref<ControlItem[]>([])
const loading = ref(false)
const expandedControlId = ref<number | null>(null)
const controlDetail = ref<ControlDetail | null>(null)
const detailLoading = ref(false)

// 다이얼로그
const showImportDialog = ref(false)
const showAddControlDialog = ref(false)
const showAddEtDialog = ref(false)
const showUploadDialog = ref(false)
const importFile = ref<File | null>(null)
const importLoading = ref(false)
const importResult = ref<{ totalRows: number; successCount: number; failCount: number; errors: string[] } | null>(null)
const newControl = ref({ code: '', domain: '', name: '', description: '', evidenceTypes: '' })
const newEtName = ref('')
const uploadTargetEtId = ref<number | null>(null)
const uploadFile = ref<File | null>(null)
const uploadLoading = ref(false)

// ========================================
// Computed
// ========================================
const statusCounts = computed(() => {
  const counts = { 완료: 0, 진행중: 0, 미수집: 0 }
  controls.value.forEach(c => {
    if (c.status in counts) counts[c.status as keyof typeof counts]++
  })
  return counts
})

// ========================================
// API
// ========================================
async function loadFrameworks() {
  try {
    const { data } = await frameworksApi.list()
    if (data.success) {
      frameworks.value = data.data
      if (frameworks.value.length > 0 && !selectedFrameworkId.value) {
        selectedFrameworkId.value = frameworks.value[0].id
        await loadControls()
      }
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

async function handleImport() {
  if (!importFile.value || !selectedFrameworkId.value) return
  importLoading.value = true
  try {
    const { data } = await frameworksApi.importControls(selectedFrameworkId.value, importFile.value)
    if (data.success) {
      importResult.value = data.data
      await loadControls()
    }
  } catch (e) { console.error(e) }
  finally { importLoading.value = false }
}

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
  } catch (e) { console.error(e) }
}

async function handleAddEvidenceType() {
  if (!expandedControlId.value || !newEtName.value.trim()) return
  try {
    await controlsApi.addEvidenceType(expandedControlId.value, { name: newEtName.value.trim() })
    newEtName.value = ''
    showAddEtDialog.value = false
    await toggleExpand(expandedControlId.value)
  } catch (e) { console.error(e) }
}

async function handleUpload() {
  if (!uploadTargetEtId.value || !uploadFile.value) return
  uploadLoading.value = true
  try {
    await evidenceFilesApi.upload(uploadTargetEtId.value, uploadFile.value)
    showUploadDialog.value = false
    uploadFile.value = null
    if (expandedControlId.value) await toggleExpand(expandedControlId.value)
  } catch (e) { console.error(e) }
  finally { uploadLoading.value = false }
}

async function deleteControl(id: number) {
  if (!confirm('이 통제항목을 삭제하시겠습니까?')) return
  try {
    await controlsApi.delete(id)
    await loadControls()
  } catch (e) { console.error(e) }
}

function openUpload(etId: number) {
  uploadTargetEtId.value = etId
  showUploadDialog.value = true
}

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

onMounted(loadFrameworks)
</script>

<template>
  <div class="p-6 space-y-6">
    <!-- 헤더 -->
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

    <!-- 프레임워크 선택 + 통계 -->
    <div class="flex items-center gap-6">
      <select v-model="selectedFrameworkId" @change="loadControls()"
        class="px-3 py-2 border border-gray-300 rounded-lg text-sm bg-white">
        <option v-for="fw in frameworks" :key="fw.id" :value="fw.id">{{ fw.name }}</option>
      </select>
      <div class="flex gap-4 text-sm">
        <span class="text-gray-500">전체 <strong class="text-gray-900">{{ controls.length }}</strong></span>
        <span class="text-green-600">완료 <strong>{{ statusCounts['완료'] }}</strong></span>
        <span class="text-amber-600">진행중 <strong>{{ statusCounts['진행중'] }}</strong></span>
        <span class="text-gray-400">미수집 <strong>{{ statusCounts['미수집'] }}</strong></span>
      </div>
    </div>

    <!-- 테이블 -->
    <div class="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <table class="w-full">
        <thead>
          <tr class="border-b border-gray-200 bg-gray-50">
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500 w-10"></th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">코드</th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">영역</th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">항목명</th>
            <th class="px-4 py-3 text-center text-xs font-semibold text-gray-500">수집현황</th>
            <th class="px-4 py-3 text-center text-xs font-semibold text-gray-500">상태</th>
            <th class="px-4 py-3 text-center text-xs font-semibold text-gray-500 w-20"></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="ctrl in controls" :key="ctrl.id">
            <!-- 통제항목 행 -->
            <tr @click="toggleExpand(ctrl.id)"
              class="border-b border-gray-100 hover:bg-gray-50 cursor-pointer transition-colors">
              <td class="px-4 py-3 text-center">
                <i :class="['pi text-xs text-gray-400', expandedControlId === ctrl.id ? 'pi-chevron-down' : 'pi-chevron-right']"></i>
              </td>
              <td class="px-4 py-3 text-sm font-mono text-blue-600 font-medium">{{ ctrl.code }}</td>
              <td class="px-4 py-3 text-sm text-gray-500">{{ ctrl.domain || '-' }}</td>
              <td class="px-4 py-3 text-sm text-gray-900 font-medium">{{ ctrl.name }}</td>
              <td class="px-4 py-3 text-center text-sm">
                <span class="text-gray-600">{{ ctrl.evidenceCollected }}/{{ ctrl.evidenceTotal }}</span>
              </td>
              <td class="px-4 py-3 text-center">
                <span :class="['px-2 py-1 rounded text-xs font-medium', statusColor(ctrl.status)]">{{ ctrl.status }}</span>
              </td>
              <td class="px-4 py-3 text-center" @click.stop>
                <button @click="deleteControl(ctrl.id)" class="p-1 text-gray-400 hover:text-red-500">
                  <i class="pi pi-trash text-sm"></i>
                </button>
              </td>
            </tr>

            <!-- 증빙 상세 (확장) -->
            <tr v-if="expandedControlId === ctrl.id">
              <td colspan="7" class="px-6 py-4 bg-blue-50/30 border-b border-gray-200">
                <div v-if="detailLoading" class="text-center py-4 text-gray-400 text-sm">
                  <i class="pi pi-spin pi-spinner mr-2"></i>로딩 중...
                </div>
                <div v-else-if="controlDetail">
                  <div class="flex items-center justify-between mb-3">
                    <h4 class="text-sm font-bold text-gray-700">
                      수집된 증빙 파일
                      <span class="text-gray-400 font-normal ml-2">{{ controlDetail.evidenceCollected }}/{{ controlDetail.evidenceTotal }}</span>
                    </h4>
                    <button @click="showAddEtDialog = true"
                      class="px-3 py-1.5 text-xs bg-white border border-gray-300 rounded-lg hover:bg-gray-50 flex items-center gap-1">
                      <i class="pi pi-plus text-xs"></i> 증빙 유형 추가
                    </button>
                  </div>

                  <!-- 증빙 유형별 목록 -->
                  <div class="space-y-2">
                    <div v-for="et in controlDetail.evidenceTypes" :key="et.id"
                      class="bg-white rounded-lg border border-gray-200 p-3">
                      <div class="flex items-center justify-between">
                        <div class="flex items-center gap-2">
                          <i class="pi pi-file text-gray-400"></i>
                          <span class="text-sm font-medium text-gray-900">{{ et.name }}</span>
                          <span v-if="et.collected"
                            class="px-1.5 py-0.5 bg-green-100 text-green-700 text-xs rounded">수집됨</span>
                          <span v-else
                            class="px-1.5 py-0.5 bg-red-100 text-red-600 text-xs rounded">미수집</span>
                        </div>
                        <div class="flex items-center gap-1">
                          <button @click="openUpload(et.id)"
                            class="p-1.5 text-gray-400 hover:text-blue-500" title="파일 업로드">
                            <i class="pi pi-upload text-sm"></i>
                          </button>
                        </div>
                      </div>

                      <!-- 파일 이력 -->
                      <div v-if="et.files && et.files.length > 0" class="mt-2 ml-6 space-y-1">
                        <div v-for="file in et.files" :key="file.id"
                          class="flex items-center justify-between text-xs text-gray-500 py-1">
                          <div class="flex items-center gap-2">
                            <span :class="file.version === et.files[0].version ? 'text-blue-600 font-medium' : ''">
                              v{{ file.version }}{{ file.version === et.files[0].version ? ' (최신)' : '' }}
                            </span>
                            <span>{{ formatDate(file.collectedAt) }}</span>
                            <span>{{ formatFileSize(file.fileSize) }}</span>
                            <span :class="file.collectionMethod === 'auto' ? 'text-green-600' : 'text-gray-400'">
                              {{ file.collectionMethod === 'auto' ? '자동수집' : '수동업로드' }}
                            </span>
                          </div>
                          <div class="flex items-center gap-1">
                            <a :href="'/api/v1/evidence-files/' + file.id + '/download'"
                              class="p-1 text-gray-400 hover:text-blue-500" title="다운로드">
                              <i class="pi pi-download text-xs"></i>
                            </a>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </td>
            </tr>
          </template>

          <tr v-if="controls.length === 0 && !loading">
            <td colspan="7" class="px-4 py-12 text-center text-gray-400 text-sm">
              등록된 통제항목이 없습니다. 엑셀 Import 또는 직접 추가해주세요.
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ========================================
         다이얼로그: 엑셀 Import
         ======================================== -->
    <div v-if="showImportDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6">
        <h3 class="text-lg font-bold text-gray-900 mb-4">통제항목 엑셀 Import</h3>
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
    <div v-if="showAddControlDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6">
        <h3 class="text-lg font-bold text-gray-900 mb-4">통제항목 추가</h3>
        <div class="space-y-3">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">코드 *</label>
            <input v-model="newControl.code" class="w-full px-3 py-2 border rounded-lg text-sm" placeholder="1.1.1" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">영역</label>
            <input v-model="newControl.domain" class="w-full px-3 py-2 border rounded-lg text-sm" placeholder="관리체계 수립" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">항목명 *</label>
            <input v-model="newControl.name" class="w-full px-3 py-2 border rounded-lg text-sm" placeholder="정보보호 정책 수립" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">설명</label>
            <textarea v-model="newControl.description" rows="2" class="w-full px-3 py-2 border rounded-lg text-sm"></textarea>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">필요 증빙 (쉼표 구분)</label>
            <input v-model="newControl.evidenceTypes" class="w-full px-3 py-2 border rounded-lg text-sm"
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
         다이얼로그: 증빙 유형 추가
         ======================================== -->
    <div v-if="showAddEtDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-sm p-6">
        <h3 class="text-lg font-bold text-gray-900 mb-4">증빙 유형 추가</h3>
        <input v-model="newEtName" class="w-full px-3 py-2 border rounded-lg text-sm mb-4" placeholder="증빙 유형 이름" />
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
    <div v-if="showUploadDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-sm p-6">
        <h3 class="text-lg font-bold text-gray-900 mb-4">증빙 파일 업로드</h3>
        <input type="file" @change="(e: any) => uploadFile = e.target.files?.[0]" class="w-full text-sm mb-4" />
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
