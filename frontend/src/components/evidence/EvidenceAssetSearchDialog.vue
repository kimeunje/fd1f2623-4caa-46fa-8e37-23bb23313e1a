<script setup lang="ts">
/**
 * v18.6a — Evidence Asset 검색 다이얼로그.
 *
 * <p>화면 mockup 의 [기존 파일에서 선택] 흐름. asset 의 sha256 unique 단위로 검색,
 * 선택 시 emit('select', asset) 으로 부모에 전달 → 부모가 POST /evidence-files/link
 * 호출.</p>
 *
 * <h3>UX</h3>
 * <ul>
 *   <li>검색 input — q 파라미터 (파일명 LIKE prefix, dev/test 환경 기준)</li>
 *   <li>결과 list — 파일명 / 업로더 / 등록일 / 크기 / 사용 중 N 항목 / [선택] 버튼</li>
 *   <li>페이지네이션 — page/size (기본 0/20)</li>
 *   <li>BE 응답 shape — Spring Page 직접 (content / totalElements)</li>
 * </ul>
 */
import { ref, computed, watch, onMounted } from 'vue'
import { evidenceAssetsApi } from '@/services/evidenceAssetApi'
import type { EvidenceAsset } from '@/types/evidence'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  (e: 'select', asset: EvidenceAsset): void
  (e: 'close'): void
}>()

// ========================================
// 상태
// ========================================
const searchQuery = ref('')
const currentPage = ref(0)
const pageSize = 20
const totalElements = ref(0)
const totalPages = ref(0)
const results = ref<EvidenceAsset[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

// debounce 검색
let searchTimer: ReturnType<typeof setTimeout> | null = null

// ========================================
// 데이터 로드
// ========================================
async function loadResults(page = 0) {
  loading.value = true
  error.value = null
  try {
    const { data } = await evidenceAssetsApi.search({
      q: searchQuery.value.trim() || undefined,
      page,
      size: pageSize,
    })
    if (data.success) {
      results.value = data.data.content
      totalElements.value = data.data.totalElements
      totalPages.value = data.data.totalPages
      currentPage.value = data.data.number
    }
  } catch (e: any) {
    error.value = e?.response?.data?.message ?? '검색에 실패했습니다.'
  } finally {
    loading.value = false
  }
}

// 다이얼로그 open 시 reset + 초기 로드
watch(() => props.open, (isOpen) => {
  if (isOpen) {
    searchQuery.value = ''
    currentPage.value = 0
    loadResults(0)
  }
})

// 검색어 변경 시 debounce
watch(searchQuery, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => loadResults(0), 300)
})

onMounted(() => {
  if (props.open) loadResults(0)
})

// ========================================
// 페이지 변경
// ========================================
function goToPage(page: number) {
  if (page < 0 || page >= totalPages.value) return
  loadResults(page)
}

const pageButtons = computed<number[]>(() => {
  const total = totalPages.value
  if (total <= 0) return []
  const cur = currentPage.value
  const start = Math.max(0, cur - 2)
  const end = Math.min(total - 1, cur + 2)
  const buttons: number[] = []
  for (let i = start; i <= end; i++) buttons.push(i)
  return buttons
})

// ========================================
// 액션
// ========================================
function handleSelect(asset: EvidenceAsset) {
  emit('select', asset)
}

function handleClose() {
  emit('close')
}

// ========================================
// 표시 헬퍼
// ========================================
function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatDateTime(s?: string): string {
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
</script>

<template>
  <Transition name="fade">
    <div v-if="open" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40">
      <div
        @click.self="handleClose"
        class="absolute inset-0">
      </div>

      <!-- 다이얼로그 본체 -->
      <div class="relative bg-white border border-gray-200 rounded-xl w-full max-w-3xl max-h-[85vh] flex flex-col shadow-xl">

        <!-- 헤더 -->
        <div class="flex items-center justify-between px-5 py-4 border-b border-gray-200 shrink-0">
          <div>
            <h3 class="text-base font-medium text-gray-900">기존 파일에서 선택</h3>
            <p class="text-[11px] text-gray-500 mt-0.5">
              이미 등록된 파일을 선택해 이 증빙 유형에 연결합니다.
            </p>
          </div>
          <button
            @click="handleClose"
            class="w-7 h-7 flex items-center justify-center text-gray-400 hover:text-gray-700">
            <i class="pi pi-times text-sm"></i>
          </button>
        </div>

        <!-- 검색 input -->
        <div class="px-5 py-3 border-b border-gray-200 shrink-0">
          <div class="relative">
            <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs"></i>
            <input
              v-model="searchQuery"
              type="text"
              placeholder="파일명으로 검색…"
              class="w-full h-9 pl-9 pr-3 text-sm border border-gray-200 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" />
          </div>
        </div>

        <!-- 결과 영역 -->
        <div class="flex-1 overflow-y-auto px-5 py-3">
          <!-- 로딩 -->
          <div v-if="loading" class="text-center py-10 text-sm text-gray-400">
            <i class="pi pi-spin pi-spinner text-xl mb-2"></i>
            <div>검색 중…</div>
          </div>

          <!-- 에러 -->
          <div v-else-if="error" class="text-center py-10 text-sm text-red-600">
            <i class="pi pi-exclamation-circle text-xl mb-2"></i>
            <div>{{ error }}</div>
          </div>

          <!-- 빈 결과 -->
          <div v-else-if="results.length === 0" class="text-center py-10 text-sm text-gray-400">
            <i class="pi pi-inbox text-2xl text-gray-300 mb-2"></i>
            <p>{{ searchQuery ? '검색 결과가 없습니다.' : '등록된 파일이 없습니다.' }}</p>
            <p class="text-[11px] mt-1 text-gray-400">
              먼저 [새 파일 등록] 으로 파일을 업로드하세요.
            </p>
          </div>

          <!-- 결과 list -->
          <table v-else class="w-full text-xs">
            <thead>
              <tr class="text-left text-[11px] text-gray-400 font-normal border-b border-gray-100">
                <th class="py-2 px-2 font-normal">파일명</th>
                <th class="py-2 px-2 font-normal" style="width: 110px;">업로더</th>
                <th class="py-2 px-2 font-normal" style="width: 130px;">등록일</th>
                <th class="py-2 px-2 font-normal" style="width: 70px;">크기</th>
                <th class="py-2 px-2 font-normal" style="width: 90px;">사용 중</th>
                <th class="py-2 px-2 font-normal" style="width: 60px;"></th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="asset in results"
                :key="asset.id"
                class="border-b border-gray-100 last:border-b-0 hover:bg-gray-50">
                <td class="py-2.5 px-2 font-mono text-[11px] truncate max-w-0" :title="asset.originalFileName">
                  {{ asset.originalFileName }}
                </td>
                <td class="py-2.5 px-2 text-gray-600">
                  {{ asset.uploadedByName ?? '미지정' }}
                </td>
                <td class="py-2.5 px-2 text-gray-600">{{ formatDateTime(asset.createdAt) }}</td>
                <td class="py-2.5 px-2 text-gray-600">{{ formatFileSize(asset.fileSize) }}</td>
                <td class="py-2.5 px-2 text-gray-600">
                  <span class="inline-block px-1.5 py-0.5 bg-gray-100 text-gray-700 rounded text-[10px]">
                    {{ asset.usedInCount }} 항목
                  </span>
                </td>
                <td class="py-2.5 px-2 text-right">
                  <button
                    @click="handleSelect(asset)"
                    class="h-7 px-2.5 text-[11px] bg-gray-900 text-white rounded hover:bg-gray-800">
                    선택
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 페이지네이션 + 총 건수 -->
        <div
          v-if="totalElements > 0"
          class="flex items-center justify-between px-5 py-3 border-t border-gray-200 text-[11px] text-gray-500 shrink-0">
          <div>
            총 <span class="font-medium text-gray-700">{{ totalElements }}</span> 건
          </div>
          <div v-if="totalPages > 1" class="inline-flex items-center gap-1">
            <button
              @click="goToPage(currentPage - 1)"
              :disabled="currentPage === 0"
              class="w-7 h-7 inline-flex items-center justify-center rounded border border-gray-200 disabled:opacity-40 disabled:cursor-not-allowed hover:bg-gray-50">
              <i class="pi pi-chevron-left text-[10px]"></i>
            </button>
            <button
              v-for="p in pageButtons"
              :key="p"
              @click="goToPage(p)"
              class="w-7 h-7 inline-flex items-center justify-center rounded text-[11px]"
              :class="p === currentPage
                ? 'bg-gray-900 text-white'
                : 'border border-gray-200 hover:bg-gray-50'">
              {{ p + 1 }}
            </button>
            <button
              @click="goToPage(currentPage + 1)"
              :disabled="currentPage >= totalPages - 1"
              class="w-7 h-7 inline-flex items-center justify-center rounded border border-gray-200 disabled:opacity-40 disabled:cursor-not-allowed hover:bg-gray-50">
              <i class="pi pi-chevron-right text-[10px]"></i>
            </button>
          </div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>