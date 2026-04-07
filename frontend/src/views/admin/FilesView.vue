<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { evidenceFilesApi } from '@/services/evidenceApi'
import type { EvidenceFileItem, EvidenceFileStats } from '@/types/evidence'

const files = ref<EvidenceFileItem[]>([])
const stats = ref<EvidenceFileStats | null>(null)
const loading = ref(false)
const currentPage = ref(0)
const totalPages = ref(0)
const totalItems = ref(0)
const pageSize = 20
const viewMode = ref<'list' | 'timeline'>('list')
const searchQuery = ref('')

// ========================================
// API
// ========================================
async function loadFiles(page = 0) {
  loading.value = true
  try {
    const { data } = await evidenceFilesApi.list({ page, size: pageSize })
    if (data.success) {
      files.value = data.data.items
      totalPages.value = data.data.totalPages
      totalItems.value = data.data.total
      currentPage.value = data.data.page
    }
  } catch (e) { console.error(e) }
  finally { loading.value = false }
}

async function loadStats() {
  try {
    const { data } = await evidenceFilesApi.getStats()
    if (data.success) stats.value = data.data
  } catch (e) { console.error(e) }
}

async function deleteFile(id: number) {
  if (!confirm('이 파일을 삭제하시겠습니까?')) return
  try {
    await evidenceFilesApi.delete(id)
    await loadFiles(currentPage.value)
    await loadStats()
  } catch (e) { console.error(e) }
}

// ========================================
// Computed
// ========================================
const filteredFiles = computed(() => {
  if (!searchQuery.value.trim()) return files.value
  const q = searchQuery.value.toLowerCase()
  return files.value.filter(f =>
    f.fileName.toLowerCase().includes(q) ||
    f.controlCode.toLowerCase().includes(q) ||
    f.controlName.toLowerCase().includes(q) ||
    f.evidenceTypeName.toLowerCase().includes(q)
  )
})

const timelineGroups = computed(() => {
  const groups: Record<string, EvidenceFileItem[]> = {}
  filteredFiles.value.forEach(f => {
    const date = f.collectedAt ? new Date(f.collectedAt) : new Date()
    const key = `${date.getFullYear()}년 ${date.getMonth() + 1}월`
    if (!groups[key]) groups[key] = []
    groups[key].push(f)
  })
  return groups
})

// ========================================
// Helpers
// ========================================
function formatFileSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatTotalSize(bytes: number) {
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString('ko')
}

function formatDateTime(dateStr?: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ko')
}

onMounted(() => {
  loadFiles()
  loadStats()
})
</script>

<template>
  <div class="p-6 space-y-6">
    <!-- 헤더 -->
    <div>
      <h1 class="text-xl font-bold text-gray-900">증빙 파일</h1>
      <p class="text-sm text-gray-500 mt-1">수집된 모든 증빙 파일의 전체 이력을 관리합니다.</p>
    </div>

    <!-- 통계 카드 -->
    <div v-if="stats" class="grid grid-cols-4 gap-4">
      <div class="bg-white rounded-xl border border-gray-200 p-4">
        <p class="text-xs text-gray-400 mb-1">전체 파일 수</p>
        <p class="text-2xl font-bold text-gray-900">{{ stats.totalFiles }}</p>
      </div>
      <div class="bg-white rounded-xl border border-gray-200 p-4">
        <p class="text-xs text-gray-400 mb-1">이번 분기</p>
        <p class="text-2xl font-bold text-blue-600">{{ stats.quarterFiles }}</p>
      </div>
      <div class="bg-white rounded-xl border border-gray-200 p-4">
        <p class="text-xs text-gray-400 mb-1">총 용량</p>
        <p class="text-2xl font-bold text-gray-900">{{ formatTotalSize(stats.totalSizeBytes) }}</p>
      </div>
      <div class="bg-white rounded-xl border border-gray-200 p-4">
        <p class="text-xs text-gray-400 mb-1">통제항목 커버리지</p>
        <p class="text-2xl font-bold text-green-600">{{ stats.controlCoverage }}%</p>
      </div>
    </div>

    <!-- 필터/검색 바 -->
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-3">
        <div class="relative">
          <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-sm"></i>
          <input v-model="searchQuery" placeholder="파일명, 통제항목 검색..."
            class="pl-9 pr-4 py-2 border border-gray-300 rounded-lg text-sm w-64" />
        </div>
      </div>
      <div class="flex items-center gap-2 bg-gray-100 rounded-lg p-1">
        <button @click="viewMode = 'list'"
          :class="['px-3 py-1.5 rounded text-xs font-medium transition-colors', viewMode === 'list' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500']">
          <i class="pi pi-list mr-1"></i> 리스트
        </button>
        <button @click="viewMode = 'timeline'"
          :class="['px-3 py-1.5 rounded text-xs font-medium transition-colors', viewMode === 'timeline' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500']">
          <i class="pi pi-calendar mr-1"></i> 타임라인
        </button>
      </div>
    </div>

    <!-- ========================================
         리스트 뷰
         ======================================== -->
    <div v-if="viewMode === 'list'" class="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <table class="w-full">
        <thead>
          <tr class="border-b border-gray-200 bg-gray-50">
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">파일명</th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">통제항목</th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">증빙 유형</th>
            <th class="px-4 py-3 text-center text-xs font-semibold text-gray-500">버전</th>
            <th class="px-4 py-3 text-right text-xs font-semibold text-gray-500">크기</th>
            <th class="px-4 py-3 text-center text-xs font-semibold text-gray-500">수집 방식</th>
            <th class="px-4 py-3 text-left text-xs font-semibold text-gray-500">수집일</th>
            <th class="px-4 py-3 text-center text-xs font-semibold text-gray-500 w-20"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="file in filteredFiles" :key="file.id" class="border-b border-gray-100 hover:bg-gray-50">
            <td class="px-4 py-3">
              <div class="flex items-center gap-2">
                <i class="pi pi-file text-gray-400"></i>
                <span class="text-sm text-gray-900">{{ file.fileName }}</span>
              </div>
            </td>
            <td class="px-4 py-3 text-sm">
              <span class="text-blue-600 font-mono">{{ file.controlCode }}</span>
              <span class="text-gray-400 ml-1">{{ file.controlName }}</span>
            </td>
            <td class="px-4 py-3 text-sm text-gray-600">{{ file.evidenceTypeName }}</td>
            <td class="px-4 py-3 text-center text-sm font-medium text-gray-700">v{{ file.version }}</td>
            <td class="px-4 py-3 text-right text-sm text-gray-500">{{ formatFileSize(file.fileSize) }}</td>
            <td class="px-4 py-3 text-center">
              <span :class="['px-2 py-1 rounded text-xs font-medium',
                file.collectionMethod === 'auto' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500']">
                {{ file.collectionMethod === 'auto' ? '자동' : '수동' }}
              </span>
            </td>
            <td class="px-4 py-3 text-sm text-gray-500">{{ formatDate(file.collectedAt) }}</td>
            <td class="px-4 py-3 text-center">
              <div class="flex items-center justify-center gap-1">
                <a :href="'/api/v1/evidence-files/' + file.id + '/download'"
                  class="p-1 text-gray-400 hover:text-blue-500" title="다운로드">
                  <i class="pi pi-download text-sm"></i>
                </a>
                <button @click="deleteFile(file.id)" class="p-1 text-gray-400 hover:text-red-500" title="삭제">
                  <i class="pi pi-trash text-sm"></i>
                </button>
              </div>
            </td>
          </tr>

          <tr v-if="filteredFiles.length === 0 && !loading">
            <td colspan="8" class="px-4 py-12 text-center text-gray-400 text-sm">
              {{ searchQuery ? '검색 결과가 없습니다.' : '수집된 증빙 파일이 없습니다.' }}
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 페이지네이션 -->
      <div v-if="totalPages > 1" class="px-4 py-3 border-t border-gray-200 flex items-center justify-between">
        <span class="text-sm text-gray-500">총 {{ totalItems }}개</span>
        <div class="flex items-center gap-1">
          <button @click="loadFiles(currentPage - 1)" :disabled="currentPage <= 0"
            class="px-3 py-1.5 text-sm rounded border border-gray-300 disabled:opacity-40">이전</button>
          <span class="px-3 py-1.5 text-sm text-gray-600">{{ currentPage + 1 }} / {{ totalPages }}</span>
          <button @click="loadFiles(currentPage + 1)" :disabled="currentPage >= totalPages - 1"
            class="px-3 py-1.5 text-sm rounded border border-gray-300 disabled:opacity-40">다음</button>
        </div>
      </div>
    </div>

    <!-- ========================================
         타임라인 뷰
         ======================================== -->
    <div v-if="viewMode === 'timeline'" class="space-y-6">
      <div v-for="(groupFiles, monthLabel) in timelineGroups" :key="monthLabel">
        <h3 class="text-sm font-bold text-gray-700 mb-3 flex items-center gap-2">
          <i class="pi pi-calendar text-gray-400"></i>
          {{ monthLabel }}
          <span class="text-gray-400 font-normal">({{ groupFiles.length }}건)</span>
        </h3>
        <div class="space-y-2 ml-4 border-l-2 border-gray-200 pl-4">
          <div v-for="file in groupFiles" :key="file.id"
            class="bg-white rounded-lg border border-gray-200 p-3 flex items-center justify-between">
            <div class="flex items-center gap-3">
              <i class="pi pi-file text-gray-400"></i>
              <div>
                <p class="text-sm font-medium text-gray-900">{{ file.fileName }}</p>
                <p class="text-xs text-gray-400">
                  {{ file.controlCode }} · {{ file.evidenceTypeName }} · v{{ file.version }} ·
                  {{ formatFileSize(file.fileSize) }}
                </p>
              </div>
            </div>
            <div class="flex items-center gap-2">
              <span :class="['px-2 py-1 rounded text-xs font-medium',
                file.collectionMethod === 'auto' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500']">
                {{ file.collectionMethod === 'auto' ? '자동' : '수동' }}
              </span>
              <span class="text-xs text-gray-400">{{ formatDate(file.collectedAt) }}</span>
              <a :href="'/api/v1/evidence-files/' + file.id + '/download'"
                class="p-1 text-gray-400 hover:text-blue-500">
                <i class="pi pi-download text-sm"></i>
              </a>
            </div>
          </div>
        </div>
      </div>

      <div v-if="Object.keys(timelineGroups).length === 0 && !loading"
        class="text-center py-12 text-gray-400 text-sm">
        수집된 증빙 파일이 없습니다.
      </div>
    </div>
  </div>
</template>
