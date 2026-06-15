<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { auditLogsApi } from '@/services/auditApi'
import type {
  AuditAction,
  AuditLog,
  AuditLogPage,
  AuditLogSearchParams,
  AuditResult,
} from '@/types/audit'

const ACTION_LABELS: Record<AuditAction, string> = {
  LOGIN_SUCCESS: '로그인 성공',
  LOGIN_FAILURE: '로그인 실패',
  ACL_BLOCKED: 'IP 접근 차단',
  RATE_LIMIT_BLOCKED: '로그인 제한 차단',
  EVIDENCE_APPROVE: '증빙 승인',
  EVIDENCE_REJECT: '증빙 반려',
  SCRIPT_CREATE: '스크립트 생성',
  SCRIPT_UPDATE: '스크립트 수정',
  SCRIPT_ROLLBACK: '스크립트 롤백',
  SCRIPT_DELETE: '스크립트 삭제',
  USER_CREATE: '사용자 생성',
  USER_UPDATE: '사용자 수정',
  USER_DELETE: '사용자 삭제',
  FRAMEWORK_CHANGE: '프레임워크 변경',
  TREE_CHANGE: '통제 트리 변경',
  FILE_UPLOAD: '파일 업로드',
  FILE_DOWNLOAD: '파일 다운로드',
  FILE_DELETE: '파일 삭제',
}

const RESULT_LABELS: Record<AuditResult, string> = {
  SUCCESS: '성공',
  FAILURE: '실패',
  BLOCKED: '차단',
}

const ACTION_OPTIONS = Object.keys(ACTION_LABELS) as AuditAction[]
const RESULT_OPTIONS = Object.keys(RESULT_LABELS) as AuditResult[]
const SIZE_OPTIONS = [10, 20, 50, 100]

const loading = ref(false)
const error = ref('')
const pageData = ref<AuditLogPage | null>(null)
const expandedId = ref<number | null>(null)

const page = ref(0)
const size = ref(20)

const filters = reactive({
  action: '' as AuditAction | '',
  result: '' as AuditResult | '',
  actorUserId: '' as string,
  from: '' as string,
  to: '' as string,
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const params: AuditLogSearchParams = { page: page.value, size: size.value }
    if (filters.action) params.action = filters.action
    if (filters.result) params.result = filters.result
    if (filters.actorUserId.trim() !== '') params.actorUserId = Number(filters.actorUserId)
    if (filters.from) params.from = filters.from
    if (filters.to) params.to = filters.to

    const res = await auditLogsApi.search(params)
    const body = res.data // AxiosResponse → res.data = ApiResponse<AuditLogPage> 봉투
    if (!body.success) {
      error.value = body.message || '조회에 실패했습니다.'
      pageData.value = null
      return
    }
    pageData.value = body.data
  } catch (e: any) {
    error.value = e?.response?.data?.message ?? '조회 중 오류가 발생했습니다.'
    pageData.value = null
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  page.value = 0
  load()
}

function resetFilters() {
  filters.action = ''
  filters.result = ''
  filters.actorUserId = ''
  filters.from = ''
  filters.to = ''
  page.value = 0
  load()
}

function changeSize(next: number) {
  size.value = next
  page.value = 0
  load()
}

function goPrev() {
  if (page.value > 0) {
    page.value -= 1
    load()
  }
}

function goNext() {
  if (pageData.value?.hasNext) {
    page.value += 1
    load()
  }
}

function toggleDetail(id: number) {
  expandedId.value = expandedId.value === id ? null : id
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
    d.getMinutes(),
  )}:${p(d.getSeconds())}`
}

function actionLabel(a: AuditAction): string {
  return ACTION_LABELS[a] ?? a
}

function resultLabel(r: AuditResult): string {
  return RESULT_LABELS[r] ?? r
}

function resultBadgeClass(r: AuditResult): string {
  switch (r) {
    case 'SUCCESS':
      return 'bg-green-100 text-green-700'
    case 'FAILURE':
      return 'bg-red-100 text-red-700'
    case 'BLOCKED':
      return 'bg-amber-100 text-amber-700'
    default:
      return 'bg-gray-100 text-gray-700'
  }
}

function actor(row: AuditLog): string {
  if (row.actorEmail) return row.actorEmail
  if (row.actorUserId != null) return `#${row.actorUserId}`
  return '시스템/익명'
}

function target(row: AuditLog): string {
  if (!row.targetType && !row.targetId) return '-'
  if (row.targetType && row.targetId) return `${row.targetType} #${row.targetId}`
  return row.targetType ?? row.targetId ?? '-'
}

/** IPv6 루프백/IPv4-mapped 를 보기 좋게 정규화. */
function formatIp(ip: string | null): string {
  if (!ip) return '-'
  if (ip === '::1' || ip === '0:0:0:0:0:0:0:1') return '127.0.0.1'
  const mapped = ip.match(/^::ffff:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/i)
  return mapped ? mapped[1] : ip
}

/** detail 이 JSON 이면 보기 좋게 pretty-print, 아니면 원문. */
function formatDetail(detail: string): string {
  try {
    return JSON.stringify(JSON.parse(detail), null, 2)
  } catch {
    return detail
  }
}

onMounted(load)
</script>

<template>
  <section class="p-6 max-w-screen-xl mx-auto">
    <!-- 헤더 -->
    <header class="mb-5">
      <h1 class="text-xl font-bold text-gray-900 flex items-center gap-2">
        <i class="pi pi-history text-blue-500"></i>
        감사 로그
      </h1>
      <p class="mt-1 text-sm text-gray-500">
        로그인 · 접근 차단 · 증빙/스크립트/사용자 변경 등 보안 이벤트 추적
      </p>
    </header>

    <!-- 필터 -->
    <div class="flex flex-wrap items-end gap-3 p-4 bg-gray-50 border border-gray-200 rounded-lg mb-4">
      <label class="flex flex-col gap-1 text-xs text-gray-600">
        <span>활동</span>
        <select v-model="filters.action" class="border border-gray-300 rounded-md px-2 py-1.5 text-sm bg-white">
          <option value="">전체</option>
          <option v-for="a in ACTION_OPTIONS" :key="a" :value="a">{{ actionLabel(a) }}</option>
        </select>
      </label>

      <label class="flex flex-col gap-1 text-xs text-gray-600">
        <span>결과</span>
        <select v-model="filters.result" class="border border-gray-300 rounded-md px-2 py-1.5 text-sm bg-white">
          <option value="">전체</option>
          <option v-for="r in RESULT_OPTIONS" :key="r" :value="r">{{ resultLabel(r) }}</option>
        </select>
      </label>

      <label class="flex flex-col gap-1 text-xs text-gray-600">
        <span>사용자 ID</span>
        <input
          v-model="filters.actorUserId"
          type="number"
          min="1"
          placeholder="users.id"
          class="border border-gray-300 rounded-md px-2 py-1.5 text-sm bg-white w-28"
        />
      </label>

      <label class="flex flex-col gap-1 text-xs text-gray-600">
        <span>시작</span>
        <input v-model="filters.from" type="datetime-local" class="border border-gray-300 rounded-md px-2 py-1.5 text-sm bg-white" />
      </label>

      <label class="flex flex-col gap-1 text-xs text-gray-600">
        <span>종료</span>
        <input v-model="filters.to" type="datetime-local" class="border border-gray-300 rounded-md px-2 py-1.5 text-sm bg-white" />
      </label>

      <div class="flex gap-2 ml-auto">
        <button
          :disabled="loading"
          @click="applyFilters"
          class="px-4 py-2 bg-blue-600 text-white rounded-md text-sm hover:bg-blue-700 disabled:opacity-50"
        >
          조회
        </button>
        <button
          :disabled="loading"
          @click="resetFilters"
          class="px-4 py-2 bg-white border border-gray-300 text-gray-700 rounded-md text-sm hover:bg-gray-50 disabled:opacity-50"
        >
          초기화
        </button>
      </div>
    </div>

    <!-- 상태 -->
    <p v-if="error" class="py-8 text-center text-red-600">{{ error }}</p>
    <p v-else-if="loading" class="py-8 text-center text-gray-500">불러오는 중…</p>
    <p v-else-if="pageData && pageData.content.length === 0" class="py-8 text-center text-gray-500">
      조건에 맞는 감사 로그가 없습니다.
    </p>

    <!-- 표 -->
    <div v-else-if="pageData" class="overflow-x-auto border border-gray-200 rounded-lg">
      <table class="w-full text-sm">
        <thead>
          <tr class="bg-gray-50 text-gray-500">
            <th class="px-3 py-2.5 text-left font-semibold whitespace-nowrap">사용자 · 일시</th>
            <th class="px-3 py-2.5 text-left font-semibold">활동</th>
            <th class="px-3 py-2.5 text-left font-semibold">결과</th>
            <th class="px-3 py-2.5 text-left font-semibold">대상</th>
            <th class="px-3 py-2.5 text-left font-semibold whitespace-nowrap">접속 IP</th>
            <th class="px-3 py-2.5 text-left font-semibold">상세 내용</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in pageData.content" :key="row.id" class="border-t border-gray-100 align-top">
            <td class="px-3 py-2.5">
              <div class="text-gray-800">{{ actor(row) }}</div>
              <div class="text-xs text-gray-400 whitespace-nowrap">{{ formatDateTime(row.createdAt) }}</div>
            </td>
            <td class="px-3 py-2.5 text-gray-800">{{ actionLabel(row.action) }}</td>
            <td class="px-3 py-2.5">
              <span :class="['inline-block px-2 py-0.5 rounded-full text-xs font-semibold', resultBadgeClass(row.result)]">
                {{ resultLabel(row.result) }}
              </span>
            </td>
            <td class="px-3 py-2.5 text-gray-700">{{ target(row) }}</td>
            <td class="px-3 py-2.5 whitespace-nowrap text-gray-700 tabular-nums">{{ formatIp(row.clientIp) }}</td>
            <td class="px-3 py-2.5">
              <template v-if="row.detail">
                <button class="text-blue-600 text-xs hover:underline" @click="toggleDetail(row.id)">
                  {{ expandedId === row.id ? '접기' : '보기' }}
                </button>
                <pre
                  v-if="expandedId === row.id"
                  class="mt-1.5 p-2 bg-gray-900 text-gray-100 rounded-md text-xs whitespace-pre-wrap break-all max-w-xs"
                >{{ formatDetail(row.detail) }}</pre>
              </template>
              <span v-else class="text-gray-400">-</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 페이지네이션 -->
    <div v-if="pageData && pageData.totalElements > 0" class="flex items-center justify-between mt-4 flex-wrap gap-3">
      <div class="text-sm text-gray-500">
        총 {{ pageData.totalElements }}건 · {{ pageData.page + 1 }} / {{ pageData.totalPages }} 페이지
      </div>
      <div class="flex items-center gap-2">
        <label class="flex items-center gap-1.5 text-xs text-gray-600">
          <span>페이지당</span>
          <select
            :value="size"
            @change="changeSize(Number(($event.target as HTMLSelectElement).value))"
            class="border border-gray-300 rounded-md px-2 py-1 text-sm bg-white"
          >
            <option v-for="s in SIZE_OPTIONS" :key="s" :value="s">{{ s }}</option>
          </select>
        </label>
        <button
          :disabled="page === 0 || loading"
          @click="goPrev"
          class="px-3 py-1.5 bg-white border border-gray-300 text-gray-700 rounded-md text-sm hover:bg-gray-50 disabled:opacity-50"
        >
          이전
        </button>
        <button
          :disabled="!pageData.hasNext || loading"
          @click="goNext"
          class="px-3 py-1.5 bg-white border border-gray-300 text-gray-700 rounded-md text-sm hover:bg-gray-50 disabled:opacity-50"
        >
          다음
        </button>
      </div>
    </div>
  </section>
</template>