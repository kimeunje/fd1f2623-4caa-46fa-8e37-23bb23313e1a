<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { jobsApi } from '@/services/evidenceApi'
import type { CollectionJobItem, CollectionJobDetail } from '@/types/evidence'

const jobs = ref<CollectionJobItem[]>([])
const loading = ref(false)
const selectedJob = ref<CollectionJobDetail | null>(null)
const showDetailPanel = ref(false)
const showCreateDialog = ref(false)
const detailLoading = ref(false)
const newJob = ref({ name: '', description: '', jobType: 'web_scraping', scriptPath: '', scheduleCron: '' })

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
      scriptPath: newJob.value.scriptPath || undefined,
      scheduleCron: newJob.value.scheduleCron || undefined,
    })
    showCreateDialog.value = false
    newJob.value = { name: '', description: '', jobType: 'web_scraping', scriptPath: '', scheduleCron: '' }
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
  if (!confirm('이 작업을 즉시 실행하시겠습니까?')) return
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
      <button @click="showCreateDialog = true"
        class="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 flex items-center gap-2">
        <i class="pi pi-plus text-sm"></i> 작업 등록
      </button>
    </div>

    <div class="flex gap-6">
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

          <!-- 실행 이력 -->
          <div>
            <p class="text-xs text-gray-400 mb-2">실행 이력</p>
            <div v-if="selectedJob.executions.length === 0" class="text-xs text-gray-400">실행 이력이 없습니다.</div>
            <div v-else class="space-y-2 max-h-60 overflow-y-auto">
              <div v-for="exec in selectedJob.executions" :key="exec.id"
                class="p-2.5 bg-gray-50 rounded-lg">
                <div class="flex items-center justify-between mb-1">
                  <span :class="['px-1.5 py-0.5 rounded text-xs font-medium', execStatusColor(exec.status)]">
                    {{ execStatusLabel(exec.status) }}
                  </span>
                  <span class="text-xs text-gray-400">{{ formatDate(exec.startedAt) }}</span>
                </div>
                <div v-if="exec.finishedAt" class="text-xs text-gray-500">
                  종료: {{ formatDate(exec.finishedAt) }}
                </div>
                <div v-if="exec.errorMessage" class="mt-1 text-xs text-red-500 bg-red-50 p-1.5 rounded">
                  {{ exec.errorMessage }}
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
            <label class="block text-sm font-medium text-gray-700 mb-1">스크립트 경로</label>
            <input v-model="newJob.scriptPath" class="w-full px-3 py-2 border rounded-lg text-sm font-mono"
              placeholder="/scripts/access_rights.py" />
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
  </div>
</template>
