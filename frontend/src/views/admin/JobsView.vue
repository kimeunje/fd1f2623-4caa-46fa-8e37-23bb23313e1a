<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { jobsApi } from '@/services/evidenceApi'
import type { CollectionJobItem, CollectionJobDetail, ExecutionSummary } from '@/types/evidence'
import FailureDiagnosisPanel from '@/components/admin/FailureDiagnosisPanel.vue'
import ScriptEditorDialog from '@/components/admin/ScriptEditorDialog.vue'

const jobs = ref<CollectionJobItem[]>([])
const loading = ref(false)
const selectedJob = ref<CollectionJobDetail | null>(null)
const showDetailPanel = ref(false)
const showCreateDialog = ref(false)
const detailLoading = ref(false)
const newJob = ref<{
  name: string
  description: string
  jobType: string
  scriptId: number | null
  scriptPath: string
  scheduleCron: string
}>({ name: '', description: '', jobType: 'web_scraping', scriptId: null, scriptPath: '', scheduleCron: '' })

// v18.7 — 진단 패널 진입 상태 (풀폭 swap)
const selectedExecution = ref<ExecutionSummary | null>(null)

// v18.8.2 — 스크립트 편집 dialog 상태 (UID 기반)
const scriptEditorMode = ref<'create' | 'edit' | null>(null)
const editingScriptId = ref<number | null>(null)

const jobTypeLabels: Record<string, { label: string; color: string }> = {
  web_scraping: { label: '웹 스크래핑', color: 'bg-purple-100 text-purple-700' },
  excel_extract: { label: '엑셀 추출', color: 'bg-blue-100 text-blue-700' },
  log_extract: { label: '로그 추출', color: 'bg-orange-100 text-orange-700' },
}

async function loadJobs() {
  loading.value = true
  try {
    const { data } = await jobsApi.list()
    if (data.success) jobs.value = data.data
  } catch (e) { console.error(e) }
  finally { loading.value = false }
}

async function openDetail(jobId: number) {
  detailLoading.value = true
  showDetailPanel.value = true
  try {
    const { data } = await jobsApi.getDetail(jobId)
    if (data.success) selectedJob.value = data.data
  } catch (e) { console.error(e) }
  finally { detailLoading.value = false }
}

async function handleCreate() {
  try {
    await jobsApi.create({
      name: newJob.value.name,
      description: newJob.value.description || undefined,
      jobType: newJob.value.jobType,
      scriptId: newJob.value.scriptId ?? undefined,         // v18.8.2
      scriptPath: newJob.value.scriptPath || undefined,     // legacy fallback
      scheduleCron: newJob.value.scheduleCron || undefined,
    })
    showCreateDialog.value = false
    newJob.value = { name: '', description: '', jobType: 'web_scraping', scriptId: null, scriptPath: '', scheduleCron: '' }
    await loadJobs()
  } catch (e) { console.error(e) }
}

async function toggleActive(job: CollectionJobItem) {
  try {
    await jobsApi.toggleActive(job.id)
    await loadJobs()
  } catch (e) { console.error(e) }
}

async function executeJob(jobId: number) {
  if (!confirm('이 작업을 즉시 실행하시겠습니까?'))
    return
  try {
    await jobsApi.execute(jobId)
    await loadJobs()
    if (selectedJob.value?.id === jobId) await openDetail(jobId)
  } catch (e) { console.error(e) }
}

async function deleteJob(jobId: number) {
  if (!confirm('이 수집 작업을 삭제하시겠습니까?')) return
  try {
    await jobsApi.delete(jobId)
    if (selectedJob.value?.id === jobId) {
      showDetailPanel.value = false
      selectedJob.value = null
    }
    await loadJobs()
  } catch (e) { console.error(e) }
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ko')
}

// v18.7 — mockup 화면 1 정합 (실행 이력 row 의 시간 mono 표기)
function formatDateMono(dateStr?: string) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${mm}-${dd} ${hh}:${mi}`
}

// v18.7 — 실행 소요 시간 계산 (mockup row 의 "1.4초" 정합)
function formatDuration(exec: ExecutionSummary): string {
  if (!exec.startedAt || !exec.finishedAt) return '-'
  const start = new Date(exec.startedAt).getTime()
  const end = new Date(exec.finishedAt).getTime()
  const sec = (end - start) / 1000
  return `${sec.toFixed(1)}초`
}

function execStatusColor(status: string) {
  switch (status) {
    case 'success': return 'bg-green-100 text-green-700'
    case 'failed': return 'bg-red-100 text-red-700'
    case 'running': return 'bg-blue-100 text-blue-700'
    default: return 'bg-gray-100 text-gray-500'
  }
}

function execStatusLabel(status: string) {
  switch (status) {
    case 'success': return '성공'
    case 'failed': return '실패'
    case 'running': return '실행중'
    default: return status
  }
}

// v18.7+ — 실행 row 클릭 시 진단/상세 패널 풀폭 진입
// v18.8 — 성공도 진입 가능 (단계별 시간 추적). running 만 차단 (진단 JSON 미생성).
function openDiagnosisPanel(exec: ExecutionSummary) {
  if (exec.status === 'running') return
  selectedExecution.value = exec
}

// v18.7 — 풀폭 swap 상태 (목록 + 상세 숨김 여부)
const showDiagnosisPanel = computed(() => selectedExecution.value !== null)

// v18.8.2 — 스크립트 신규 작성 다이얼로그 열기
function openScriptCreateDialog() {
  scriptEditorMode.value = 'create'
  editingScriptId.value = null
}

// v18.8.2 — 진단 패널의 "수정 스크립트 업로드" 버튼 → 스크립트 수정 dialog 열기
function openScriptEditDialog(scriptId: number) {
  editingScriptId.value = scriptId
  scriptEditorMode.value = 'edit'
}

function closeScriptEditor() {
  scriptEditorMode.value = null
  editingScriptId.value = null
}

// v18.8.2 — 스크립트 저장 완료 시 — scriptId 자동 채움 (별도 필드)
async function onScriptSaved(payload: { scriptId: number }) {
  closeScriptEditor()

  // 신규 작성이면 작업 등록 dialog 의 scriptId 자동 채움
  if (showCreateDialog.value && !newJob.value.scriptId) {
    newJob.value.scriptId = payload.scriptId
  }

  // 작업 상세 패널이 열려 있으면 다시 로드 (수정 후 재실행 흐름)
  if (selectedJob.value) {
    await openDetail(selectedJob.value.id)
  }
}

// v18.8.4 — 진단 패널의 "재실행" 버튼 → jobsApi.execute + 패널 close + 작업 상세 reload.
// BE 의 executeManually (v18.8.2) 는 script (UID) 또는 scriptPath (legacy) 둘 다 지원 — 별도 분기 불요.
// 흐름: confirm → API → 새 running execution 생성 → 패널 close → openDetail (새 row 표시).
async function handleRerunFromDiagnosis() {
  if (!selectedJob.value) return
  if (!confirm('이 작업을 즉시 재실행하시겠습니까?')) return
  const jobId = selectedJob.value.id   // selectedExecution null 처리 전 미리 캡처
  try {
    await jobsApi.execute(jobId)
    selectedExecution.value = null      // 진단 패널 close (풀폭 swap 해제)
    await openDetail(jobId)              // 새 running execution 이 실행 이력 최상단에 표시
  } catch (e: any) {
    alert(e?.response?.data?.message ?? '재실행에 실패했습니다.')
  }
}

// v18.8.7 — 스크립트 삭제 완료 시 stale state 정리.
//
// 부모의 책임: ScriptEditorDialog 에서 삭제 emit 받으면
//   1) dialog close
//   2) 작업 등록 dialog 의 newJob.scriptId 가 삭제된 스크립트면 null 리셋
//      (stale 참조로 작업 등록 시 BE 의 ResourceNotFoundException 회피)
//   3) 작업 상세 패널이 열려 있고 그 작업이 삭제된 script 를 가리키면 reload
//      (script_id 가 NULL 로 SET_NULL 됐을 것 — legacy scriptPath fallback 분기로 정합)
async function onScriptDeleted(payload: { scriptId: number }) {
  closeScriptEditor()

  // 작업 등록 dialog 안의 stale scriptId 리셋
  if (newJob.value.scriptId === payload.scriptId) {
    newJob.value.scriptId = null
  }

  // 작업 상세 패널이 열려 있고 그 작업이 삭제된 script 를 가리키면 reload
  if (selectedJob.value?.scriptId === payload.scriptId) {
    await openDetail(selectedJob.value.id)
  }

  // 작업 목록도 reload — script_id 가 NULL 로 SET_NULL 됐을 작업들의 표시 갱신
  await loadJobs()
}

onMounted(loadJobs)
</script>

<template>
  <div class="p-6 space-y-6">
    <!-- 헤더 -->
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-xl font-bold text-gray-900">수집 작업</h1>
        <p class="text-sm text-gray-500 mt-1">증빙 자료 자동 수집 작업을 관리합니다.</p>
      </div>
      <!-- v18.7 — 진단 패널 표시 중이면 작업 등록 버튼 숨김 (mockup 정합) -->
      <button v-if="!showDiagnosisPanel" @click="showCreateDialog = true"
        class="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 flex items-center gap-2">
        <i class="pi pi-plus text-sm"></i> 작업 등록
      </button>
      <!-- v18.7 — 진단 패널 표시 중이면 뒤로가기 표기 -->
      <button v-else @click="selectedExecution = null"
        class="px-4 py-2 bg-white border border-stone-300 text-stone-700 rounded-lg text-sm font-medium hover:bg-stone-50 flex items-center gap-2">
        <i class="pi pi-arrow-left text-sm"></i> 작업 목록으로
      </button>
    </div>

    <!-- ════════════════════════════════════════════════════════════
         v18.7 — 진단 패널 (풀폭 swap, 실패 row 클릭 시)
         v18.8.4 — @rerun 리스너 추가 (재실행 버튼 활성화)
         ════════════════════════════════════════════════════════════ -->
    <div v-if="showDiagnosisPanel && selectedExecution">
      <FailureDiagnosisPanel
        :execution="selectedExecution"
        :job-name="selectedJob?.name"
        :script-id="selectedJob?.scriptId"
        @close="selectedExecution = null"
        @upload-script="openScriptEditDialog"
        @rerun="handleRerunFromDiagnosis"
      />
    </div>

    <!-- ════════════════════════════════════════════════════════════
         기본 화면 — 작업 목록 + 상세 패널 (진단 패널 표시 중이면 숨김)
         ════════════════════════════════════════════════════════════ -->
    <div v-else class="flex gap-6">
      <!-- 작업 목록 -->
      <div :class="['flex-1 bg-white rounded-xl border border-gray-200 overflow-hidden', showDetailPanel ? '' : '']">
        <table class="w-full">
          <thead>
            <tr class="border-b border-gray-200 bg-gray-50">
              <th class="px-4 py-3 text-center text-xs font-semibold text-gray-500 w-16">상태</th>
              <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">작업명</th>
              <th class="px-4 py-3 text-center text-xs font-semibold text-gray-500">유형</th>
              <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">스케줄</th>
              <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">마지막 실행</th>
              <th class="px-4 py-3 text-center text-xs font-semibold text-gray-500 w-32">작업</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="job in jobs" :key="job.id"
              @click="openDetail(job.id)"
              class="border-b border-gray-100 hover:bg-gray-50 cursor-pointer transition-colors">
              <td class="px-4 py-3 text-center">
                <span v-if="job.lastExecution"
                  :class="['inline-block w-2.5 h-2.5 rounded-full', job.lastExecution.status === 'success' ? 'bg-green-500' : job.lastExecution.status === 'failed' ? 'bg-red-500' : 'bg-blue-500']">
                </span>
                <span v-else class="inline-block w-2.5 h-2.5 rounded-full bg-gray-300"></span>
              </td>
              <td class="px-4 py-3">
                <p class="text-sm font-medium text-gray-900">{{ job.name }}</p>
                <p v-if="job.evidenceTypeName" class="text-xs text-gray-400 mt-0.5">{{ job.evidenceTypeName }}</p>
              </td>
              <td class="px-4 py-3 text-center">
                <span :class="['px-2 py-1 rounded text-xs font-medium', jobTypeLabels[job.jobType]?.color || 'bg-gray-100 text-gray-500']">
                  {{ jobTypeLabels[job.jobType]?.label || job.jobType }}
                </span>
              </td>
              <td class="px-4 py-3 text-sm text-gray-600 font-mono">{{ job.scheduleCron || '-' }}</td>
              <td class="px-4 py-3 text-sm text-gray-500">
                {{ job.lastExecution ? formatDate(job.lastExecution.finishedAt || job.lastExecution.startedAt) : '-' }}
              </td>
              <td class="px-4 py-3 text-center" @click.stop>
                <div class="flex items-center justify-center gap-1">
                  <button @click="executeJob(job.id)" class="p-1.5 text-gray-400 hover:text-green-600" title="실행">
                    <i class="pi pi-play text-sm"></i>
                  </button>
                  <button @click="toggleActive(job)" class="p-1.5 text-gray-400 hover:text-blue-500"
                    :title="job.isActive ? '비활성화' : '활성화'">
                    <i :class="['pi text-sm', job.isActive ? 'pi-pause' : 'pi-play-circle']"></i>
                  </button>
                  <button @click="deleteJob(job.id)" class="p-1.5 text-gray-400 hover:text-red-500" title="삭제">
                    <i class="pi pi-trash text-sm"></i>
                  </button>
                </div>
              </td>
            </tr>

            <tr v-if="jobs.length === 0 && !loading">
              <td colspan="6" class="px-4 py-12 text-center text-gray-400 text-sm">
                등록된 수집 작업이 없습니다.
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 상세 패널 -->
      <div v-if="showDetailPanel" class="w-96 bg-white rounded-xl border border-gray-200 p-5 shrink-0">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-sm font-bold text-gray-900">작업 상세</h3>
          <button @click="showDetailPanel = false; selectedJob = null" class="p-1 text-gray-400 hover:text-gray-600">
            <i class="pi pi-times text-sm"></i>
          </button>
        </div>

        <div v-if="detailLoading" class="text-center py-8 text-gray-400 text-sm">
          <i class="pi pi-spin pi-spinner mr-2"></i>로딩 중...
        </div>

        <div v-else-if="selectedJob" class="space-y-4">
          <div>
            <p class="text-xs text-gray-400 mb-1">작업명</p>
            <p class="text-sm font-medium text-gray-900">{{ selectedJob.name }}</p>
          </div>
          <div v-if="selectedJob.description">
            <p class="text-xs text-gray-400 mb-1">설명</p>
            <p class="text-sm text-gray-600">{{ selectedJob.description }}</p>
          </div>
          <div class="flex gap-4">
            <div>
              <p class="text-xs text-gray-400 mb-1">유형</p>
              <span :class="['px-2 py-1 rounded text-xs font-medium', jobTypeLabels[selectedJob.jobType]?.color]">
                {{ jobTypeLabels[selectedJob.jobType]?.label }}
              </span>
            </div>
            <div>
              <p class="text-xs text-gray-400 mb-1">상태</p>
              <span :class="['px-2 py-1 rounded text-xs font-medium', selectedJob.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500']">
                {{ selectedJob.isActive ? '활성' : '비활성' }}
              </span>
            </div>
          </div>
          <div v-if="selectedJob.scriptPath">
            <p class="text-xs text-gray-400 mb-1">스크립트</p>
            <p class="text-xs font-mono text-gray-600 bg-gray-50 px-2 py-1 rounded">{{ selectedJob.scriptPath }}</p>
          </div>

          <!--
            v18.7 — 실행 이력 (mockup 화면 1 정합).
            row layout: 4 column grid (시간 mono | 상태+추가정보 | 소요시간 | 진단 보기 →).
            실패 row = 빨간 배경 + border + cursor-pointer + 클릭 시 진단 패널 풀폭 진입.
          -->
          <div>
            <p class="text-xs text-gray-400 mb-2">실행 이력</p>
            <div v-if="selectedJob.executions.length === 0" class="text-xs text-gray-400">실행 이력이 없습니다.</div>
            <div v-else class="space-y-1 max-h-80 overflow-y-auto">

              <!--
                v18.7 — 좁은 폭 (w-96 작업 상세 패널) 정합 3 줄 layout.
                  1줄: 시간 mono (좌) + 상태 배지 (우)
                  2줄: 소요시간 (shrink-0) + errorMessage truncate (실패 시만)
                  3줄: 진단/상세 보기 → (실행중 외 모두, 우측 정렬, 별도 줄)
                v18.8 — 성공 row 도 클릭 → 풀폭 패널 진입 (단계별 시간 추적).
              -->
              <div v-for="exec in selectedJob.executions" :key="exec.id"
                :class="[
                  'px-2.5 py-2 rounded text-[11px]',
                  exec.status === 'failed'
                    ? 'bg-red-50 border border-red-300 cursor-pointer hover:bg-red-100 transition-colors'
                    : exec.status === 'success'
                    ? 'bg-stone-100 cursor-pointer hover:bg-stone-200 transition-colors'
                    : 'bg-stone-100'
                ]"
                @click="openDiagnosisPanel(exec)">

                <!-- 1줄: 시간 mono + 상태 배지 -->
                <div class="flex items-center justify-between mb-1 gap-2">
                  <span :class="['font-mono text-[11px] whitespace-nowrap',
                    exec.status === 'failed' ? 'text-red-700' : 'text-stone-600'
                  ]">
                    {{ formatDateMono(exec.startedAt) }}
                  </span>
                  <span class="flex items-center gap-1 whitespace-nowrap shrink-0">
                    <i v-if="exec.status === 'success'" class="pi pi-check-circle text-green-700 text-[12px]"></i>
                    <i v-else-if="exec.status === 'failed'" class="pi pi-times-circle text-red-700 text-[12px]"></i>
                    <i v-else class="pi pi-spin pi-spinner text-blue-700 text-[12px]"></i>
                    <span :class="['font-medium',
                      exec.status === 'success' ? 'text-green-700' :
                      exec.status === 'failed' ? 'text-red-700' :
                      'text-blue-700'
                    ]">
                      {{ execStatusLabel(exec.status) }}
                    </span>
                  </span>
                </div>

                <!-- 2줄: 소요시간 (shrink-0) + 메시지 (실패만, truncate) -->
                <div class="flex items-center gap-2 min-w-0">
                  <span :class="['text-[10px] whitespace-nowrap shrink-0',
                    exec.status === 'failed' ? 'text-red-700' : 'text-stone-500'
                  ]">
                    {{ formatDuration(exec) }}
                  </span>
                  <span v-if="exec.status === 'failed' && exec.errorMessage"
                    class="text-red-700 text-[10px] truncate min-w-0 flex-1"
                    :title="exec.errorMessage">
                    — {{ exec.errorMessage.split('\n')[0] }}
                  </span>
                </div>

                <!-- 3줄: 진단/상세 보기 → (실행중 외 모두, 우측 정렬, 별도 줄) -->
                <div v-if="exec.status !== 'running'"
                  :class="['mt-1 text-right text-[10px] font-medium',
                    exec.status === 'failed' ? 'text-red-700' : 'text-stone-500'
                  ]">
                  {{ exec.status === 'failed' ? '진단 보기' : '상세 보기' }} →
                </div>
              </div>

            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ========================================
         다이얼로그: 작업 등록
         ======================================== -->
    <div v-if="showCreateDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6">
        <h3 class="text-lg font-bold text-gray-900 mb-4">수집 작업 등록</h3>
        <div class="space-y-3">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">작업명 *</label>
            <input v-model="newJob.name" class="w-full px-3 py-2 border rounded-lg text-sm" placeholder="접근권한 현황 추출" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">설명</label>
            <textarea v-model="newJob.description" rows="2" class="w-full px-3 py-2 border rounded-lg text-sm"></textarea>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">유형 *</label>
            <select v-model="newJob.jobType" class="w-full px-3 py-2 border rounded-lg text-sm bg-white">
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

            <!-- 스크립트 미설정 시 — "작성" 버튼만 (UID 기반, 파일명 입력 없음) -->
            <div v-else>
              <button type="button" @click="openScriptCreateDialog"
                class="w-full px-3 py-2 text-sm bg-white border border-blue-300 text-blue-700 rounded-lg hover:bg-blue-50 inline-flex items-center justify-center gap-1.5">
                <i class="pi pi-pencil text-xs"></i> 스크립트 작성
              </button>
              <p class="mt-1 text-xs text-gray-400">
                "작성" 버튼으로 Python 스크립트를 등록하세요. 파일명은 자동 부여됩니다.
              </p>
            </div>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">스케줄 (Cron)</label>
            <input v-model="newJob.scheduleCron" class="w-full px-3 py-2 border rounded-lg text-sm font-mono"
              placeholder="0 0 18 * * ?" />
          </div>
        </div>
        <div class="flex justify-end gap-2 mt-4">
          <button @click="showCreateDialog = false" class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-lg">취소</button>
          <button @click="handleCreate" :disabled="!newJob.name"
            class="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50">등록</button>
        </div>
      </div>
    </div>

    <!-- ════════════════════════════════════════════════════════════
         v18.8.2 — 스크립트 작성/편집 dialog (UID 기반)
         v18.8.7 — @deleted 리스너 추가 (편집 모드 [삭제] 버튼 정합)
         ════════════════════════════════════════════════════════════ -->
    <ScriptEditorDialog
      v-if="scriptEditorMode"
      :mode="scriptEditorMode"
      :script-id="editingScriptId ?? undefined"
      @close="closeScriptEditor"
      @saved="onScriptSaved"
      @deleted="onScriptDeleted"
    />
  </div>
</template>