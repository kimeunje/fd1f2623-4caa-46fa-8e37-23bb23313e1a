<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { myTasksApi, evidenceFilesApi } from '@/services/evidenceApi'
import type { MyTaskDetail, MyTaskFileHistoryEntry } from '@/types/evidence'

const props = defineProps<{
  evidenceTypeId: number
}>()

const router = useRouter()

const loading = ref(true)
const error = ref<string | null>(null)
const detail = ref<MyTaskDetail | null>(null)

// 업로드 상태
const uploadFile = ref<File | null>(null)
const submitNote = ref('')
const uploading = ref(false)
const uploadSuccess = ref(false)
const uploadError = ref<string | null>(null)
const isDragOver = ref(false)

// 토스트
const toast = ref({ show: false, message: '', type: 'success' as 'success' | 'error' })
function showToast(message: string, type: 'success' | 'error' = 'success') {
  toast.value = { show: true, message, type }
  setTimeout(() => { toast.value.show = false }, 3000)
}

// ========================================
// 데이터 로드
// ========================================

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await myTasksApi.getDetail(props.evidenceTypeId)
    if (res.data.success) {
      detail.value = res.data.data
    } else {
      error.value = res.data.message ?? '조회에 실패했습니다.'
    }
  } catch (e: any) {
    if (e?.response?.status === 403) {
      error.value = '이 증빙에 접근 권한이 없습니다.'
    } else if (e?.response?.status === 404) {
      error.value = '존재하지 않는 증빙입니다.'
    } else {
      error.value = e?.response?.data?.message ?? '상세 조회에 실패했습니다.'
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)

// 라우트 props 가 바뀌면 다시 로드
watch(() => props.evidenceTypeId, (newId) => {
  if (newId != null) {
    uploadFile.value = null
    submitNote.value = ''
    uploadSuccess.value = false
    uploadError.value = null
    load()
  }
})

// ========================================
// 업로드
// ========================================

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  uploadFile.value = input.files?.[0] ?? null
  uploadError.value = null
  uploadSuccess.value = false
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  isDragOver.value = false
  const f = e.dataTransfer?.files?.[0]
  if (f) {
    uploadFile.value = f
    uploadError.value = null
    uploadSuccess.value = false
  }
}

const canSubmit = computed(() => !!uploadFile.value && !uploading.value)

async function handleSubmit() {
  if (!uploadFile.value) return
  uploading.value = true
  uploadError.value = null
  uploadSuccess.value = false
  try {
    await evidenceFilesApi.upload(
      props.evidenceTypeId,
      uploadFile.value,
      submitNote.value.trim() || undefined,
    )
    uploadSuccess.value = true
    showToast('증빙이 제출되었습니다. 관리자 검토를 기다려주세요.', 'success')
    // 폼 리셋 & 상세 재조회
    uploadFile.value = null
    submitNote.value = ''
    await load()
  } catch (e: any) {
    const msg = e?.response?.data?.message ?? '제출에 실패했습니다. 잠시 후 다시 시도해주세요.'
    uploadError.value = msg
    showToast(msg, 'error')
  } finally {
    uploading.value = false
  }
}

async function handleDownload(entry: MyTaskFileHistoryEntry) {
  try {
    await evidenceFilesApi.download(entry.fileId, entry.fileName)
  } catch (e) {
    console.error('다운로드 실패:', e)
    showToast('파일 다운로드에 실패했습니다.', 'error')
  }
}

// ========================================
// 표시 헬퍼
// ========================================

function formatFileSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatDateTime(s?: string) {
  if (!s) return '-'
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${day} ${hh}:${mm}`
}

function formatDate(s?: string) {
  if (!s) return '-'
  const d = new Date(s)
  if (isNaN(d.getTime())) return s.substring(0, 10)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function dDayLabel(days?: number): string | null {
  if (days == null) return null
  if (days < 0) return `${-days}일 지남`
  if (days === 0) return 'D-DAY'
  return `D-${days}`
}

function dDayClass(days?: number): string {
  if (days == null) return 'bg-gray-100 text-gray-600'
  if (days < 0) return 'bg-red-100 text-red-700'
  if (days <= 3) return 'bg-orange-100 text-orange-700'
  return 'bg-amber-100 text-amber-700'
}

function statusBadge(status?: string) {
  switch (status) {
    case 'pending': return { label: '● 검토 대기', cls: 'bg-blue-100 text-blue-700' }
    case 'approved': return { label: '승인', cls: 'bg-green-100 text-green-700' }
    case 'rejected': return { label: '반려', cls: 'bg-red-100 text-red-700' }
    case 'auto_approved': return { label: '자동 승인', cls: 'bg-gray-100 text-gray-600' }
    default: return { label: '미수집', cls: 'bg-gray-100 text-gray-500' }
  }
}

// 메인 상태 요약 텍스트
const statusSummary = computed(() => {
  if (!detail.value) return null
  switch (detail.value.currentStatus) {
    case 'rejected':
      return { label: '반려됨 · 재제출 필요', cls: 'bg-red-50 text-red-700 border-red-200' }
    case 'pending':
      return { label: '검토 중 · 관리자 승인 대기', cls: 'bg-blue-50 text-blue-700 border-blue-200' }
    case 'approved':
    case 'auto_approved':
      return { label: '완료 · 승인됨', cls: 'bg-green-50 text-green-700 border-green-200' }
    case 'not_submitted':
      return { label: '미제출', cls: 'bg-gray-50 text-gray-700 border-gray-200' }
    default:
      return { label: '상태 미확인', cls: 'bg-gray-50 text-gray-600 border-gray-200' }
  }
})
</script>

<template>
  <div class="p-6 max-w-4xl mx-auto space-y-5">
    <!-- 토스트 -->
    <Transition name="toast">
      <div v-if="toast.show"
        class="fixed top-6 right-6 z-[60] px-4 py-3 rounded-lg shadow-lg text-sm font-medium flex items-center gap-2"
        :class="toast.type === 'success' ? 'bg-green-600 text-white' : 'bg-red-600 text-white'">
        <i :class="['pi text-sm', toast.type === 'success' ? 'pi-check-circle' : 'pi-times-circle']"></i>
        {{ toast.message }}
      </div>
    </Transition>

    <!-- 상단 back -->
    <button @click="router.push({ name: 'my-tasks' })"
      class="inline-flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-700">
      <i class="pi pi-arrow-left text-xs"></i>
      내 할 일로 돌아가기
    </button>

    <!-- 로딩 -->
    <div v-if="loading" class="py-16 text-center text-gray-400 text-sm">
      <i class="pi pi-spin pi-spinner text-2xl mb-2"></i>
      <p>불러오는 중...</p>
    </div>

    <!-- 에러 -->
    <div v-else-if="error"
      class="py-12 text-center bg-red-50 border border-red-200 rounded-xl">
      <i class="pi pi-exclamation-triangle text-red-500 text-2xl mb-2"></i>
      <p class="text-sm text-red-700">{{ error }}</p>
      <button @click="router.push({ name: 'my-tasks' })"
        class="mt-3 px-3 py-1.5 text-xs bg-white border border-red-200 text-red-700 rounded-lg hover:bg-red-50">
        내 할 일로 돌아가기
      </button>
    </div>

    <!-- 본문 -->
    <template v-else-if="detail">
      <!-- ========================================
           증빙 헤더
           ======================================== -->
      <div class="bg-white rounded-xl border border-gray-200 p-5">
        <div class="flex items-start justify-between gap-4 flex-wrap">
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2 flex-wrap">
              <span v-if="statusSummary"
                :class="['px-2 py-1 text-xs rounded-md font-medium border', statusSummary.cls]">
                {{ statusSummary.label }}
              </span>
              <span v-if="detail.daysUntilDue != null"
                :class="['px-2 py-0.5 text-xs rounded font-medium', dDayClass(detail.daysUntilDue)]">
                {{ dDayLabel(detail.daysUntilDue) }}
              </span>
            </div>
            <h1 class="text-xl font-bold text-gray-900 mt-2">{{ detail.evidenceTypeName }}</h1>
            <div class="text-sm text-gray-500 mt-1">
              {{ detail.frameworkName }}
              <span class="text-gray-300 mx-1">·</span>
              {{ detail.controlCode }} {{ detail.controlName }}
              <template v-if="detail.dueDate">
                <span class="text-gray-300 mx-1">·</span>
                마감 {{ formatDate(detail.dueDate) }}
              </template>
            </div>
            <p v-if="detail.description" class="text-sm text-gray-600 mt-2">{{ detail.description }}</p>
          </div>
        </div>
      </div>

      <!-- ========================================
           반려 사유 (rejected 일 때만, 크게 강조)
           ======================================== -->
      <div v-if="detail.currentStatus === 'rejected'"
        class="bg-red-50 border-2 border-red-300 rounded-xl p-5">
        <div class="flex items-center gap-2 mb-2">
          <i class="pi pi-times-circle text-red-600"></i>
          <h2 class="text-sm font-bold text-red-900">반려 사유</h2>
          <span v-if="detail.rejectedByName" class="text-xs text-red-600">
            · {{ detail.rejectedByName }}
            <span v-if="detail.rejectedAt" class="text-red-500">
              · {{ formatDateTime(detail.rejectedAt) }}
            </span>
          </span>
        </div>
        <p class="text-[15px] text-red-900 whitespace-pre-wrap leading-relaxed">
          {{ detail.rejectReason || '사유 미기재' }}
        </p>
        <p class="text-xs text-red-600 mt-3 flex items-center gap-1">
          <i class="pi pi-info-circle text-[10px]"></i>
          반려 사유를 반영하여 아래 영역에서 다시 제출해주세요.
        </p>
      </div>

      <!-- ========================================
           업로드 폼
           ======================================== -->
      <div class="bg-white rounded-xl border border-gray-200 p-5 space-y-3">
        <div class="flex items-center gap-2">
          <i class="pi pi-upload text-gray-400"></i>
          <h2 class="text-sm font-bold text-gray-900">
            {{ detail.history.length === 0 ? '증빙 제출' : '재제출' }}
          </h2>
          <span v-if="detail.currentStatus === 'pending'"
            class="ml-auto text-[11px] text-amber-600">
            이미 제출된 증빙이 검토 중입니다. 재제출하면 새 버전으로 교체됩니다.
          </span>
        </div>

        <!-- 드래그 앤 드롭 영역 -->
        <div
          @dragover.prevent="isDragOver = true"
          @dragleave.prevent="isDragOver = false"
          @drop="onDrop"
          :class="['border-2 border-dashed rounded-lg p-6 text-center transition-colors',
            isDragOver
              ? 'border-blue-500 bg-blue-50/50'
              : (uploadFile ? 'border-green-300 bg-green-50/30' : 'border-gray-300 bg-gray-50/40 hover:border-blue-400')]">
          <template v-if="!uploadFile">
            <i class="pi pi-cloud-upload text-3xl text-gray-400 mb-2"></i>
            <p class="text-sm text-gray-600 mb-3">
              파일을 여기로 드래그하거나
              <label class="text-blue-600 hover:underline cursor-pointer font-medium">
                선택하여 업로드
                <input type="file" class="hidden" @change="onFileChange" />
              </label>
            </p>
            <p class="text-[11px] text-gray-400">단일 파일만 선택할 수 있습니다.</p>
          </template>
          <template v-else>
            <i class="pi pi-file text-green-600 text-2xl mb-2"></i>
            <p class="text-sm font-medium text-gray-900">{{ uploadFile.name }}</p>
            <p class="text-xs text-gray-500 mt-0.5">{{ formatFileSize(uploadFile.size) }}</p>
            <button
              @click.stop="uploadFile = null; uploadError = null"
              class="mt-3 text-[11px] text-red-600 hover:underline">
              선택 해제
            </button>
          </template>
        </div>

        <!-- 제출 메모 -->
        <div>
          <label class="block text-xs font-medium text-gray-700 mb-1">
            제출 메모 <span class="text-gray-400 font-normal">(선택)</span>
          </label>
          <textarea
            v-model="submitNote"
            rows="2"
            class="w-full px-3 py-2 text-sm border border-gray-200 rounded-md resize-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
            placeholder="관리자가 검토할 때 참고할 내용이 있으면 입력해주세요. (예: 반려 사유에 따라 수정한 부분 요약)"></textarea>
        </div>

        <!-- 에러 -->
        <p v-if="uploadError" class="text-xs text-red-600 flex items-center gap-1">
          <i class="pi pi-exclamation-circle text-[11px]"></i>
          {{ uploadError }}
        </p>

        <!-- 버튼 -->
        <div class="flex justify-end">
          <button
            @click="handleSubmit"
            :disabled="!canSubmit"
            class="h-9 px-4 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center gap-1.5 font-medium">
            <i v-if="uploading" class="pi pi-spin pi-spinner text-xs"></i>
            <i v-else class="pi pi-send text-xs"></i>
            {{ uploading ? '제출 중...' : '제출' }}
          </button>
        </div>
      </div>

      <!-- ========================================
           제출 이력
           ======================================== -->
      <div v-if="detail.history.length > 0" class="bg-white rounded-xl border border-gray-200 p-5">
        <h2 class="text-sm font-bold text-gray-900 mb-3 flex items-center gap-2">
          <i class="pi pi-history text-gray-400"></i>
          제출 이력
          <span class="text-xs text-gray-400 font-normal">({{ detail.history.length }}건)</span>
        </h2>
        <div class="space-y-2">
          <div
            v-for="(entry, idx) in detail.history"
            :key="entry.fileId"
            :class="['rounded-lg border p-3',
              idx === 0 ? 'border-blue-200 bg-blue-50/30' : 'border-gray-200']">
            <div class="flex items-start justify-between gap-3 flex-wrap">
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2 flex-wrap">
                  <span class="font-mono text-blue-600 text-sm">v{{ entry.version }}</span>
                  <span :class="['px-1.5 py-0.5 text-[10px] rounded font-medium',
                    statusBadge(entry.reviewStatus).cls]">
                    {{ statusBadge(entry.reviewStatus).label }}
                  </span>
                  <span v-if="idx === 0" class="px-1.5 py-0.5 text-[10px] rounded font-medium bg-blue-600 text-white">
                    최신
                  </span>
                  <span class="text-sm text-gray-900 font-medium truncate">{{ entry.fileName }}</span>
                </div>
                <div class="text-xs text-gray-500 mt-1">
                  {{ formatFileSize(entry.fileSize) }}
                  · {{ formatDateTime(entry.collectedAt) }}
                  <template v-if="entry.uploadedByName">· {{ entry.uploadedByName }} 제출</template>
                  <template v-if="entry.reviewStatus === 'approved' && entry.reviewedByName">
                    · {{ entry.reviewedByName }} 승인
                  </template>
                  <template v-if="entry.reviewStatus === 'rejected' && entry.reviewedByName">
                    · {{ entry.reviewedByName }} 반려
                  </template>
                </div>
                <!-- 제출 메모 -->
                <div v-if="entry.submitNote" class="mt-2 text-xs text-gray-600 pl-3 border-l-2 border-gray-200">
                  {{ entry.submitNote }}
                </div>
                <!-- 반려 사유 -->
                <div v-if="entry.reviewStatus === 'rejected' && entry.reviewNote"
                  class="mt-2 text-xs text-red-600 pl-3 border-l-2 border-red-200">
                  반려 사유: {{ entry.reviewNote }}
                </div>
              </div>
              <button @click="handleDownload(entry)"
                class="shrink-0 p-1.5 text-gray-400 hover:text-blue-500" title="다운로드">
                <i class="pi pi-download text-sm"></i>
              </button>
            </div>
          </div>
        </div>
      </div>
    </template>
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