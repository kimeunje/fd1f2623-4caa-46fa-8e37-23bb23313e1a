<script setup lang="ts">
/**
 * v19.25 — 심사원(reviewer) 읽기 전용 뷰.
 *
 * 관리자 이임/감사 대비 심사원 계정이 "관리 항목 트리 + 항목별 최신 승인 파일 + 다운로드"만
 * 열람한다. 인수인계 노트·파일 이력·버전 목록·스크립트·수집 작업·승인 UI 는 없다(BE DTO 단계에서 제외,
 * ReviewController/ReviewDto 참조).
 *
 * layout:'blank' — 관리자 사이드바 없이 자체 헤더(프레임워크 선택 + 로그아웃)를 갖는 자립형.
 * 트리는 reviewApi 가 내려주는 평탄화 nodes[](parentId)로 클라이언트에서 DFS 재구성.
 * 시각 컨벤션(indent 14+18*(depth-1), depth 별 타이포, font-mono 코드, chevron)은 관리자
 * ControlNodeRow 와 맞춰 이질감을 줄였다.
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import {
  reviewApi,
  type ReviewFrameworkSummary,
  type ReviewNode,
} from '@/services/evidenceApi'

const router = useRouter()
const authStore = useAuthStore()

const frameworks = ref<ReviewFrameworkSummary[]>([])
const selectedFrameworkId = ref<number | null>(null)
const frameworkName = ref('')
const nodes = ref<ReviewNode[]>([])

const loadingFrameworks = ref(false)
const loadingTree = ref(false)
const error = ref<string | null>(null)

const expandedIds = ref<Set<number>>(new Set())
const downloadingFileId = ref<number | null>(null)

// ── 트리 재구성 (평탄 nodes[] → DFS pre-order) ──────────────────────────────
const childrenMap = computed(() => {
  const m = new Map<number | null, ReviewNode[]>()
  for (const n of nodes.value) {
    const key = n.parentId ?? null
    if (!m.has(key)) m.set(key, [])
    m.get(key)!.push(n)
  }
  return m
})

const parentMap = computed(() => {
  const m = new Map<number, number | null>()
  for (const n of nodes.value) m.set(n.id, n.parentId ?? null)
  return m
})

/** 자식 유무 (categories + hybrid). */
function hasChildren(id: number): boolean {
  return (childrenMap.value.get(id)?.length ?? 0) > 0
}

/** 자식 또는 증빙이 있으면 펼침 가능. */
function isExpandable(n: ReviewNode): boolean {
  return hasChildren(n.id) || n.evidenceTypes.length > 0
}

/** DFS pre-order — 부모가 자식보다 먼저, 형제는 displayOrder(서버 정렬) 순. */
const orderedNodes = computed<ReviewNode[]>(() => {
  const out: ReviewNode[] = []
  const cm = childrenMap.value
  const walk = (parentId: number | null) => {
    for (const child of cm.get(parentId) ?? []) {
      out.push(child)
      walk(child.id)
    }
  }
  walk(null)
  return out
})

/** 조상이 모두 펼쳐져 있어야 보임. */
function isVisible(n: ReviewNode): boolean {
  let cursor = n.parentId ?? null
  while (cursor != null) {
    if (!expandedIds.value.has(cursor)) return false
    cursor = parentMap.value.get(cursor) ?? null
  }
  return true
}

const visibleNodes = computed<ReviewNode[]>(() => orderedNodes.value.filter(isVisible))

function toggle(n: ReviewNode) {
  if (!isExpandable(n)) return
  const s = new Set(expandedIds.value)
  if (s.has(n.id)) s.delete(n.id)
  else s.add(n.id)
  expandedIds.value = s
}

// ── 시각 helper (ControlNodeRow 정합) ───────────────────────────────────────
function indentPx(depth: number): number {
  return 14 + 18 * (depth - 1)
}

function nameClass(depth: number): string {
  switch (depth) {
    case 1:
      return 'text-[14px] font-bold text-gray-900'
    case 2:
      return 'text-[13px] font-semibold text-gray-900'
    case 3:
      return 'text-[12.5px] font-medium text-gray-700'
    case 4:
      return 'text-[12px] font-medium text-gray-600'
    case 5:
      return 'text-[11.5px] font-medium text-gray-500'
    default:
      return 'text-[11px] font-medium text-gray-500'
  }
}

// ── 데이터 로드 ─────────────────────────────────────────────────────────────
async function loadFrameworks() {
  loadingFrameworks.value = true
  error.value = null
  try {
    const { data } = await reviewApi.listFrameworks()
    if (data.success) {
      frameworks.value = data.data
      if (frameworks.value.length > 0 && selectedFrameworkId.value == null) {
        selectedFrameworkId.value = frameworks.value[0].id
        await loadTree()
      }
    }
  } catch {
    error.value = '프레임워크 목록을 불러오지 못했습니다.'
  } finally {
    loadingFrameworks.value = false
  }
}

async function loadTree() {
  if (selectedFrameworkId.value == null) return
  loadingTree.value = true
  error.value = null
  try {
    const { data } = await reviewApi.getTree(selectedFrameworkId.value)
    if (data.success) {
      frameworkName.value = data.data.framework.name
      nodes.value = data.data.nodes
      // 기본: depth=1 카테고리만 펼침
      const s = new Set<number>()
      for (const n of nodes.value) if (n.depth === 1) s.add(n.id)
      expandedIds.value = s
    }
  } catch {
    error.value = '트리를 불러오지 못했습니다.'
    nodes.value = []
  } finally {
    loadingTree.value = false
  }
}

function onFrameworkChange() {
  loadTree()
}

async function download(fileId: number, fileName: string) {
  downloadingFileId.value = fileId
  try {
    await reviewApi.download(fileId, fileName)
  } catch {
    error.value = '다운로드에 실패했습니다.'
  } finally {
    downloadingFileId.value = null
  }
}

async function logout() {
  // authStore.logout() 은 프로젝트 auth store 의 표준 액션. 시그니처가 다르면 이 한 줄만 조정.
  await authStore.logout()
  router.push('/login')
}

onMounted(loadFrameworks)
</script>

<template>
  <div class="min-h-screen bg-gray-50">
    <!-- 헤더 (자립형 — blank 레이아웃) -->
    <header class="sticky top-0 z-10 bg-white border-b border-gray-200">
      <div class="max-w-5xl mx-auto px-6 h-14 flex items-center gap-4">
        <div class="flex items-center gap-2">
          <i class="pi pi-verified text-blue-600"></i>
          <span class="text-[15px] font-bold text-gray-900">증빙 심사</span>
        </div>

        <div class="flex-1"></div>

        <select
          v-if="frameworks.length > 0"
          v-model="selectedFrameworkId"
          @change="onFrameworkChange"
          class="h-8 text-[13px] border border-gray-300 rounded-md px-2 bg-white text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-100">
          <option v-for="fw in frameworks" :key="fw.id" :value="fw.id">{{ fw.name }}</option>
        </select>

        <span class="text-[12px] text-gray-500">{{ authStore.user?.name }}</span>
        <button
          type="button"
          class="h-8 px-3 text-[12px] text-gray-600 border border-gray-300 rounded-md hover:bg-gray-50"
          @click="logout">
          로그아웃
        </button>
      </div>
    </header>

    <main class="max-w-5xl mx-auto px-6 py-6">
      <!-- 에러 -->
      <div
        v-if="error"
        class="mb-4 flex items-center gap-2 text-[13px] text-red-700 bg-red-50 border border-red-100 rounded-lg px-3 py-2">
        <i class="pi pi-exclamation-triangle"></i>{{ error }}
      </div>

      <!-- 로딩 -->
      <div v-if="loadingFrameworks || loadingTree" class="text-center text-gray-400 py-16">
        <i class="pi pi-spin pi-spinner text-2xl"></i>
        <p class="text-[13px] mt-2">불러오는 중...</p>
      </div>

      <!-- 빈 상태 -->
      <div
        v-else-if="frameworks.length === 0"
        class="text-center text-gray-400 bg-white border border-gray-200 rounded-xl py-16">
        <i class="pi pi-inbox text-3xl mb-3"></i>
        <p class="text-[14px] text-gray-500">열람 가능한 관리 항목이 없습니다.</p>
      </div>

      <!-- 트리 -->
      <div v-else class="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <div class="px-4 py-3 border-b border-gray-100">
          <h1 class="text-[14px] font-bold text-gray-900">{{ frameworkName }}</h1>
          <p class="text-[11px] text-gray-400 mt-0.5">관리 항목별 최신 승인 증빙을 열람·다운로드합니다.</p>
        </div>

        <div class="py-1">
          <template v-for="node in visibleNodes" :key="node.id">
            <!-- ── 카테고리 행 (depth 1-2) ── -->
            <div
              v-if="node.depth <= 2"
              class="flex items-center py-1.5 pr-4 cursor-pointer hover:bg-gray-50"
              :style="{ paddingLeft: `${indentPx(node.depth)}px` }"
              @click="toggle(node)">
              <i
                v-if="isExpandable(node)"
                :class="[
                  'pi text-[11px] text-gray-400 mr-1.5 shrink-0 transition-transform',
                  expandedIds.has(node.id) ? 'pi-chevron-down' : 'pi-chevron-right',
                ]"></i>
              <span v-else class="w-[18px] shrink-0"></span>
              <span class="font-mono text-[11px] text-gray-400 tabular-nums mr-2 shrink-0">{{ node.code }}</span>
              <span :class="['flex-1 truncate', nameClass(node.depth)]">{{ node.name }}</span>
            </div>

            <!-- ── leaf 행 (depth 3+) ── -->
            <div
              v-else
              class="flex items-center gap-2 py-1.5 pr-4 cursor-pointer hover:bg-blue-50/40 border-t border-gray-50"
              :style="{ paddingLeft: `${indentPx(node.depth) + 16}px` }"
              @click="toggle(node)">
              <span class="font-mono text-[11px] text-blue-600 tabular-nums shrink-0 w-16">{{ node.code }}</span>
              <span :class="['flex-1 truncate', nameClass(node.depth)]">{{ node.name }}</span>
              <i
                v-if="isExpandable(node)"
                :class="[
                  'pi text-[11px] text-gray-300 shrink-0',
                  expandedIds.has(node.id) ? 'pi-chevron-down' : 'pi-chevron-right',
                ]"></i>
              <span v-else class="w-[11px] shrink-0"></span>
            </div>

            <!-- ── 증빙 패널 (펼쳐졌고 증빙 유형이 있을 때) ── -->
            <div
              v-if="expandedIds.has(node.id) && node.evidenceTypes.length > 0"
              class="bg-gray-50/60 border-t border-gray-100"
              :style="{ paddingLeft: `${indentPx(node.depth) + 32}px` }">
              <div class="py-2 pr-4 space-y-1.5">
                <div
                  v-for="et in node.evidenceTypes"
                  :key="et.id"
                  class="flex items-center gap-3 bg-white border border-gray-200 rounded-lg px-3 py-2">
                  <i class="pi pi-file text-gray-400 text-[13px] shrink-0"></i>
                  <span class="text-[12.5px] text-gray-800 font-medium truncate">{{ et.name }}</span>
                  <div class="flex-1"></div>
                  <button
                    v-if="et.latestFile"
                    type="button"
                    class="h-7 px-2.5 text-[11px] text-blue-700 border border-blue-200 rounded-md hover:bg-blue-50 shrink-0 disabled:opacity-50"
                    :disabled="downloadingFileId === et.latestFile.id"
                    @click.stop="download(et.latestFile.id, et.latestFile.fileName)">
                    <i
                      :class="downloadingFileId === et.latestFile.id ? 'pi pi-spin pi-spinner' : 'pi pi-download'"
                      class="text-[11px] mr-1"></i>
                    다운로드
                  </button>
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>
    </main>
  </div>
</template>