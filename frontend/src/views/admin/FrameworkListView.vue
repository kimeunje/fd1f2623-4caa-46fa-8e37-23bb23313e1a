<script setup lang="ts">
/**
 * Framework 목록 페이지 (v11 Phase 5-3 / 5-11).
 *
 * secuhub_unified_prototype.html §stage-list 구조를 채택:
 *  - 상단 "최근 작업 중" 다크 카드 (active Framework 중 최신)
 *  - "전체 Framework" 섹션 h3 + 우측 단일 [+ 새 Framework] 버튼
 *  - Framework 테이블 (Framework명·통제·증빙·작업·상태·관리)
 *  - 각 행에 상속 출처 서브텍스트 ("XX 상속 · 2026-01-03" / "신규 생성 · 2025-01-15")
 *
 * v11 Phase 5-11 변경:
 *  - [상속하여 생성] + [+ 새 Framework] 두 버튼 → [+ 새 Framework] 단일 버튼으로 통합
 *  - 클릭 시 /controls/new wizard 로 이동 (InheritFrameworkDialog / 내장 다이얼로그 제거)
 *
 * v19.22 변경 — 2단계 삭제(보관 → 영구 삭제):
 *  - 각 행 끝에 관리 액션(보관/영구삭제) 추가. 행 전체는 더 이상 <button> 이 아니라
 *    <div role="button"> + 이름 영역 클릭만 상세 진입(중첩 버튼 무효 HTML 회피).
 *  - active → [보관] (PATCH /archive). archived → [영구 삭제](DELETE, BE 가 archived 만 허용).
 *  - 확인 다이얼로그: 상태에 따라 문구 분기(보관 vs 되돌릴 수 없는 영구 삭제).
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { frameworksApi } from '@/services/evidenceApi'
import type { Framework } from '@/types/evidence'

const router = useRouter()

const frameworks = ref<Framework[]>([])
const loading = ref(false)

// 토스트
const toast = ref({ show: false, message: '', type: 'success' as 'success' | 'error' })
function showToast(message: string, type: 'success' | 'error' = 'success') {
  toast.value = { show: true, message, type }
  setTimeout(() => { toast.value.show = false }, 3000)
}

// 확인 다이얼로그 상태 (보관/영구삭제 공용)
type ConfirmKind = 'archive' | 'delete'
const confirmTarget = ref<Framework | null>(null)
const confirmKind = ref<ConfirmKind>('archive')
const processing = ref(false)

const isDeleteConfirm = computed(() => confirmKind.value === 'delete')

async function loadFrameworks() {
  loading.value = true
  try {
    const { data } = await frameworksApi.list()
    if (data.success) {
      frameworks.value = data.data
    }
  } catch (e) {
    console.error(e)
    showToast('Framework 목록을 불러오지 못했습니다.', 'error')
  } finally {
    loading.value = false
  }
}

onMounted(loadFrameworks)

// 최근 작업 중 — active 상태의 최신 생성 Framework
const recentFramework = computed<Framework | null>(() => {
  const actives = frameworks.value.filter(f => f.status !== 'archived')
  if (actives.length === 0) return null
  const sorted = [...actives].sort((a, b) => {
    return (b.createdAt || '').localeCompare(a.createdAt || '')
  })
  return sorted[0]
})

function openFramework(fw: Framework) {
  router.push({ name: 'framework-detail', params: { frameworkId: fw.id } })
}

function openCreateWizard() {
  router.push({ name: 'framework-create-wizard' })
}

// ── 보관/영구삭제 액션 ──
function askArchive(fw: Framework) {
  confirmTarget.value = fw
  confirmKind.value = 'archive'
}

function askDelete(fw: Framework) {
  confirmTarget.value = fw
  confirmKind.value = 'delete'
}

function closeConfirm() {
  if (processing.value) return
  confirmTarget.value = null
}

async function confirmAction() {
  const fw = confirmTarget.value
  if (!fw) return
  processing.value = true
  try {
    if (confirmKind.value === 'archive') {
      await frameworksApi.archive(fw.id)
      showToast(`'${fw.name}' 을(를) 보관했습니다.`)
    } else {
      await frameworksApi.delete(fw.id)
      showToast(`'${fw.name}' 을(를) 영구 삭제했습니다.`)
    }
    confirmTarget.value = null
    await loadFrameworks()
  } catch (e: any) {
    const msg = e?.response?.data?.message
      ?? (confirmKind.value === 'archive'
        ? '보관에 실패했습니다. 잠시 후 다시 시도해주세요.'
        : '삭제에 실패했습니다. 잠시 후 다시 시도해주세요.')
    showToast(msg, 'error')
  } finally {
    processing.value = false
  }
}

// 상태 배지
function statusBadge(fw: Framework): { text: string; cls: string } {
  if (fw.status === 'archived') {
    return { text: '아카이브', cls: 'bg-gray-100 text-gray-500' }
  }
  const progress = fw.controlCount > 0
    ? Math.round((fw.evidenceTypeCount ?? 0) / Math.max(fw.controlCount, 1) * 100)
    : 0
  if (progress >= 100) return { text: '완료', cls: 'bg-green-100 text-green-700' }
  if (progress > 0) return { text: '진행중', cls: 'bg-blue-100 text-blue-700' }
  return { text: '미수집', cls: 'bg-gray-100 text-gray-600' }
}

// createdAt (ISO) → 'YYYY-MM-DD'
function formatCreatedDate(iso?: string): string {
  if (!iso) return ''
  return iso.slice(0, 10)
}

// 서브텍스트: "XX 상속 · 2026-01-03" 또는 "신규 생성 · 2026-01-03"
function originSubText(fw: Framework): string {
  const date = formatCreatedDate(fw.createdAt)
  if (fw.parentFrameworkName) {
    return `${fw.parentFrameworkName} 상속${date ? ` · ${date}` : ''}`
  }
  return `신규 생성${date ? ` · ${date}` : ''}`
}
</script>

<template>
  <div class="p-6 space-y-5 max-w-6xl mx-auto">
    <!-- 토스트 -->
    <Transition name="toast">
      <div v-if="toast.show"
        class="fixed top-6 right-6 z-[60] px-4 py-3 rounded-lg shadow-lg text-sm font-medium flex items-center gap-2"
        :class="toast.type === 'success' ? 'bg-green-600 text-white' : 'bg-red-600 text-white'">
        <i :class="['pi text-sm', toast.type === 'success' ? 'pi-check-circle' : 'pi-times-circle']"></i>
        {{ toast.message }}
      </div>
    </Transition>

    <!-- 최근 작업 중 카드 -->
    <button
      v-if="recentFramework"
      @click="openFramework(recentFramework)"
      class="w-full text-left group block">
      <div class="bg-gradient-to-br from-gray-900 to-gray-800 text-white rounded-xl p-5 hover:shadow-lg transition-shadow">
        <div class="flex items-center justify-between">
          <div>
            <div class="text-[11px] text-gray-400 uppercase tracking-wide mb-1">최근 작업 중</div>
            <div class="text-xl font-medium mb-1 flex items-center gap-2">
              {{ recentFramework.name }}
              <span
                v-if="recentFramework.pendingReviewCount && recentFramework.pendingReviewCount > 0"
                class="inline-block px-2 py-0.5 text-[10px] font-medium rounded bg-blue-600 text-white">
                검토 대기 {{ recentFramework.pendingReviewCount }}건
              </span>
            </div>
            <div class="text-xs text-gray-300">
              통제 {{ recentFramework.controlCount }}개 ·
              증빙 {{ recentFramework.evidenceTypeCount ?? 0 }}개 ·
              수집 작업 {{ recentFramework.jobCount ?? 0 }}개
            </div>
          </div>
          <div class="flex items-center gap-3">
            <i class="pi pi-chevron-right text-gray-400 group-hover:text-white transition-colors"></i>
          </div>
        </div>
      </div>
    </button>

    <div v-else-if="!loading" class="bg-white border border-dashed border-gray-300 rounded-xl p-8 text-center">
      <i class="pi pi-folder-open text-3xl text-gray-300 mb-2"></i>
      <p class="text-sm text-gray-500">아직 Framework 가 없습니다. 아래에서 신규 Framework 를 생성해보세요.</p>
    </div>

    <!-- 전체 Framework 목록 -->
    <div>
      <div class="flex items-center justify-between mb-3">
        <h3 class="text-sm font-medium text-gray-600">전체 Framework</h3>
        <!-- v11 Phase 5-11: 단일 버튼으로 통합. 클릭 시 wizard (/controls/new) 로 이동 -->
        <button
          @click="openCreateWizard"
          class="h-8 px-3 text-xs bg-gray-900 text-white rounded-md hover:bg-gray-800 inline-flex items-center gap-1.5">
          <i class="pi pi-plus text-xs"></i>
          새 Framework
        </button>
      </div>

      <div class="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <div class="grid gap-4 px-5 py-3 text-[11px] font-semibold text-gray-500 border-b border-gray-100"
          style="grid-template-columns: 1fr 70px 70px 64px 90px 84px">
          <div>Framework</div>
          <div>통제</div>
          <div>증빙</div>
          <div>작업</div>
          <div>상태</div>
          <div class="text-right">관리</div>
        </div>

        <div v-if="loading" class="px-5 py-8 text-center text-sm text-gray-400">
          <i class="pi pi-spin pi-spinner mr-1"></i> 불러오는 중…
        </div>

        <!-- v19.22: 행을 <div role=button> 으로 — 끝의 관리 버튼과 중첩 회피 -->
        <div
          v-for="fw in frameworks"
          :key="fw.id"
          class="grid gap-4 px-5 py-4 text-sm items-center border-b border-gray-100 last:border-b-0 hover:bg-gray-50"
          style="grid-template-columns: 1fr 70px 70px 64px 90px 84px">
          <!-- 이름 영역만 클릭 → 상세 진입 -->
          <div
            role="button"
            tabindex="0"
            :title="fw.description || ''"
            class="min-w-0 cursor-pointer"
            @click="openFramework(fw)"
            @keydown.enter="openFramework(fw)"
            @keydown.space.prevent="openFramework(fw)">
            <div class="flex items-center gap-2">
              <span
                class="font-medium truncate"
                :class="fw.status === 'archived' ? 'text-gray-500' : 'text-gray-900'">
                {{ fw.name }}
              </span>
              <span
                v-if="fw.pendingReviewCount && fw.pendingReviewCount > 0"
                class="inline-block px-1.5 py-0.5 text-[10px] font-medium rounded bg-blue-50 text-blue-700">
                ● 검토 {{ fw.pendingReviewCount }}
              </span>
            </div>
            <div class="text-[11px] text-gray-400 mt-0.5">
              {{ originSubText(fw) }}
            </div>
          </div>

          <div :class="fw.status === 'archived' ? 'text-gray-500' : 'text-gray-600'">{{ fw.controlCount }}</div>
          <div :class="fw.status === 'archived' ? 'text-gray-500' : 'text-gray-600'">{{ fw.evidenceTypeCount ?? 0 }}</div>
          <div :class="fw.status === 'archived' ? 'text-gray-500' : 'text-gray-600'">{{ fw.jobCount ?? 0 }}</div>
          <div>
            <span class="inline-block px-2 py-0.5 text-[11px] font-medium rounded" :class="statusBadge(fw).cls">
              {{ statusBadge(fw).text }}
            </span>
          </div>

          <!-- 관리 액션: active → 보관 / archived → 영구 삭제 -->
          <div class="flex items-center justify-end gap-1">
            <button
              v-if="fw.status !== 'archived'"
              class="p-1.5 text-gray-400 hover:text-amber-600"
              title="보관"
              @click="askArchive(fw)">
              <i class="pi pi-inbox text-sm"></i>
            </button>
            <button
              v-else
              class="p-1.5 text-gray-400 hover:text-red-600"
              title="영구 삭제"
              @click="askDelete(fw)">
              <i class="pi pi-trash text-sm"></i>
            </button>
          </div>
        </div>

        <div v-if="!loading && frameworks.length === 0" class="px-5 py-8 text-center text-sm text-gray-400">
          등록된 Framework 가 없습니다.
        </div>
      </div>
    </div>

    <!-- 확인 다이얼로그 (보관/영구삭제 공용) -->
    <div
      v-if="confirmTarget"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      @click.self="closeConfirm">
      <div class="bg-white rounded-2xl shadow-xl w-full max-w-md">
        <div class="px-6 py-5">
          <div class="flex items-start gap-3">
            <div
              class="w-10 h-10 rounded-full flex items-center justify-center shrink-0"
              :class="isDeleteConfirm ? 'bg-red-50 text-red-600' : 'bg-amber-50 text-amber-600'">
              <i :class="['pi', isDeleteConfirm ? 'pi-trash' : 'pi-inbox']"></i>
            </div>
            <div class="min-w-0">
              <h3 class="text-base font-semibold text-gray-900">
                {{ isDeleteConfirm ? '영구 삭제하시겠어요?' : '보관하시겠어요?' }}
              </h3>
              <p class="mt-1 text-sm text-gray-600 break-words">
                <span class="font-medium">{{ confirmTarget.name }}</span>
                <template v-if="isDeleteConfirm">
                  을(를) 영구 삭제합니다. 이 프레임워크의 <b>통제 항목·수집 작업·증빙 파일이 모두 함께
                  삭제</b>되며, <b>되돌릴 수 없습니다.</b>
                </template>
                <template v-else>
                  을(를) 보관합니다. 목록에서 비활성으로 표시되며 신규 작업 대상에서 제외되지만,
                  데이터는 보존되어 이력 조회·상속 원본으로 계속 사용할 수 있습니다.
                  영구 삭제는 보관 후 다시 진행할 수 있습니다.
                </template>
              </p>
            </div>
          </div>
        </div>
        <div class="flex items-center justify-end gap-2 px-6 py-4 border-t border-gray-200">
          <button
            class="px-4 py-2 text-sm font-medium text-gray-600 rounded-lg hover:bg-gray-100"
            :disabled="processing"
            @click="closeConfirm">
            취소
          </button>
          <button
            class="flex items-center gap-2 px-4 py-2 text-white rounded-lg text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed"
            :class="isDeleteConfirm ? 'bg-red-600 hover:bg-red-700' : 'bg-amber-600 hover:bg-amber-700'"
            :disabled="processing"
            @click="confirmAction">
            <i v-if="processing" class="pi pi-spin pi-spinner text-sm"></i>
            {{ isDeleteConfirm ? '영구 삭제' : '보관' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>