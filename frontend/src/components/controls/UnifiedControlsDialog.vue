<script setup lang="ts">
/**
 * UnifiedControlsDialog — 통제 관리 통합 다이얼로그 (Phase 5-14g shell + 5-14h 편집).
 *
 * <p>spec §3.3.1 / D_single_tree.html 정합. ControlsView 본문 헤더의 단일
 * `[통제 관리]` 버튼이 본 다이얼로그를 띄움. 모든 분류/통제 추가·편집·삭제 동선의
 * 단일 진입점.</p>
 *
 * <h3>5-14g 범위 (현재)</h3>
 * <ul>
 *   <li>read-only 트리 표시 — 카테고리/leaf 구조 + 카운트 (Q6=B 구조 편집 집중)</li>
 *   <li>검색 input — 코드 / 이름</li>
 *   <li>우상단 아이콘 3개 — [↑ Import] (기존 ImportControlsDialog 재사용),
 *       [↓ Export] (treeApi.exportFramework), [× 닫기]</li>
 *   <li>푸터 — [닫기] 단일 버튼</li>
 * </ul>
 *
 * <h3>5-14h 추가 예정 (다음 phase)</h3>
 * <ul>
 *   <li>인라인 편집 — 코드/이름 직접 입력, [+ 통제 추가] / [+ 분류 추가] 액션</li>
 *   <li>dirty 상태 추적 + [변경 저장] 버튼 활성화</li>
 *   <li>treeApi.patchTree (5-14d) 호출 + 409/422 에러 처리</li>
 *   <li>leaf 코드 변경 시 impact-summary 사전 호출 + 경고 다이얼로그</li>
 * </ul>
 */
import { ref, computed, watch } from 'vue'
import ControlNodeRow from './ControlNodeRow.vue'
import { treeApi } from '@/services/evidenceApi'
import type { ControlTreeState } from '@/composables/useControlTree'

const props = defineProps<{
  open: boolean
  /** ControlsView 가 보유한 useControlTree 인스턴스를 그대로 공유. */
  treeState: ControlTreeState
  /** Import/Export 라벨 컨텍스트용 — Framework 이름. */
  frameworkName: string
}>()

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void
  /** Import 완료 후 호출 — ControlsView 가 트리 재로드 + (필요 시) 토스트. */
  (e: 'imported', successCount: number, failCount: number): void
  /** Import 다이얼로그 열기 요청 — 부모(ControlsView)가 ImportControlsDialog 보유. */
  (e: 'request-import'): void
}>()

// ====================================================================
// 다이얼로그 자체 검색 (트리 composable 의 searchText 와 분리, 다이얼로그 닫기 시 reset)
// ====================================================================
// 디자인 결정: 다이얼로그는 ControlsView 와 같은 트리 데이터를 공유하지만, 검색/필터/펼침
//   은 본문 페이지 와 다이얼로그가 각각 독립적이어야 자연스럽다 (Q6=B 의 "구조 편집 vs
//   운영 현황 분리" 원칙). 그러나 5-14g 시점에는 단순화를 위해 다이얼로그도 같은
//   composable.searchText 사용. 5-14h 에서 dirty 추적과 함께 본격 분리.
// ====================================================================
const internalSearch = ref('')

// 다이얼로그 열릴 때마다 search reset
watch(
  () => props.open,
  (next) => {
    if (next) internalSearch.value = ''
  },
)

// composable 의 searchText 와 양방향 sync — 본 다이얼로그 안에서만 활성, 닫으면 reset
watch(internalSearch, (q) => {
  props.treeState.searchText.value = q
})

// 다이얼로그 닫힐 때 composable 의 검색 reset (페이지 본문이 다른 검색을 했어도 영향 없음)
watch(
  () => props.open,
  (next, prev) => {
    if (prev && !next) {
      props.treeState.searchText.value = ''
    }
  },
)

// ====================================================================
// 액션
// ====================================================================
function close() {
  emit('update:open', false)
}

function backdropClick(e: MouseEvent) {
  if (e.target === e.currentTarget) close()
}

function onImportClick() {
  emit('request-import')
}

const exporting = ref(false)
async function onExportClick() {
  if (props.treeState.framework.value == null) return
  exporting.value = true
  try {
    await treeApi.exportFramework(
      props.treeState.framework.value.id,
      `${props.frameworkName}_통제구조.xlsx`,
    )
  } catch (e: any) {
    console.error('[UnifiedControlsDialog.onExportClick]', e)
    alert(e?.response?.data?.message ?? 'Export 에 실패했습니다.')
  } finally {
    exporting.value = false
  }
}

// ====================================================================
// 다이얼로그 트리에서 leaf 클릭 — 5-14g 는 no-op (편집 모드 미진입). 5-14h 에서
// 인라인 편집 활성화 또는 leaf 행 expanded 표시.
// ====================================================================
function onTreeToggle(_nodeId: number, _nodeType: 'category' | 'control') {
  // 카테고리는 펼침/접힘 그대로
  if (_nodeType === 'category') {
    props.treeState.toggleExpand(_nodeId)
  }
  // leaf 는 5-14g 에서는 액션 없음 (편집은 5-14h)
}

// ====================================================================
// 푸터 — 5-14g 는 [닫기] 만, 5-14h 에서 [취소] / [변경 저장] 추가
// ====================================================================
const dirtyCount = computed(() => 0) // 5-14h placeholder

// 트리 본문 카운트 (헤더 서브텍스트)
const subText = computed(() => {
  const s = props.treeState
  return `통제 ${s.totalLeafCount.value}개 · 증빙 ${s.totalEvidenceTypeCount.value}개`
})
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div
        v-if="open"
        class="fixed inset-0 bg-black/40 flex items-center justify-center z-[55]"
        @click="backdropClick">
        <div
          class="bg-white rounded-xl shadow-xl flex flex-col w-full max-w-[720px] max-h-[85vh]"
          @click.stop>
          <!-- ────────────────────────────── 헤더 ────────────────────────────── -->
          <div class="px-5 py-4 border-b border-gray-200 flex items-center gap-3">
            <div class="flex-1 min-w-0">
              <h2 class="text-[15px] font-semibold text-gray-900 truncate">통제 관리</h2>
              <p class="text-[11px] text-gray-400 mt-0.5 truncate">
                {{ frameworkName }} · {{ subText }}
              </p>
            </div>
            <div class="flex items-center gap-1 shrink-0">
              <button
                type="button"
                @click="onImportClick"
                class="h-8 w-8 inline-flex items-center justify-center text-gray-400 hover:text-gray-700 hover:bg-gray-50 rounded-md transition-colors"
                title="엑셀 Import">
                <i class="pi pi-upload text-[13px]"></i>
              </button>
              <button
                type="button"
                :disabled="exporting"
                @click="onExportClick"
                class="h-8 w-8 inline-flex items-center justify-center text-gray-400 hover:text-gray-700 hover:bg-gray-50 rounded-md transition-colors disabled:opacity-50"
                title="엑셀 Export">
                <i
                  class="pi text-[13px]"
                  :class="exporting ? 'pi-spin pi-spinner' : 'pi-download'"
                ></i>
              </button>
              <button
                type="button"
                @click="close"
                class="h-8 w-8 inline-flex items-center justify-center text-gray-400 hover:text-gray-700 hover:bg-gray-50 rounded-md transition-colors"
                title="닫기">
                <i class="pi pi-times text-[13px]"></i>
              </button>
            </div>
          </div>

          <!-- ────────────────────────────── 검색 바 ────────────────────────────── -->
          <div class="px-5 py-3 border-b border-gray-100">
            <div class="relative">
              <i class="pi pi-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-[12px]"></i>
              <input
                v-model="internalSearch"
                type="text"
                placeholder="코드, 분류, 통제명 검색..."
                class="w-full h-9 pl-9 pr-3 border border-gray-200 rounded-md text-[13px] focus:ring-1 focus:ring-blue-500 focus:border-blue-500 outline-none transition-colors"
              />
            </div>
          </div>

          <!-- ────────────────────────────── 트리 본문 ────────────────────────────── -->
          <div class="flex-1 overflow-y-auto py-2">
            <!-- 로딩 -->
            <div
              v-if="treeState.loading.value"
              class="text-center py-12 text-gray-400 text-[13px]">
              <i class="pi pi-spin pi-spinner text-xl mb-2"></i>
              <div>불러오는 중...</div>
            </div>

            <!-- 에러 -->
            <div
              v-else-if="treeState.error.value"
              class="mx-5 my-3 p-3 bg-red-50 border border-red-200 rounded-md text-[12px] text-red-700">
              {{ treeState.error.value }}
            </div>

            <!-- 빈 트리 -->
            <div
              v-else-if="treeState.flatNodes.value.length === 0"
              class="text-center py-12 text-gray-400 text-[13px]">
              <i class="pi pi-list text-2xl mb-3 text-gray-300"></i>
              <div class="mb-2">아직 등록된 통제가 없습니다.</div>
              <div class="text-[11px] text-gray-300">
                상단 [↑ Import] 로 엑셀 파일을 업로드하거나,<br />
                Phase 5-14h 에서 분류 / 통제를 직접 추가할 수 있습니다.
              </div>
            </div>

            <!-- 트리 -->
            <ControlNodeRow
              v-for="root in treeState.rootNodes.value"
              v-else
              :key="root.id"
              :node="root"
              mode="dialog"
              :expanded-ids="treeState.effectiveExpandedIds.value"
              :dimmed="treeState.filterActive.value && !treeState.isMatched(root.id)"
              :is-root="true"
              @toggle-expand="onTreeToggle"
            />
          </div>

          <!-- ────────────────────────────── 푸터 ────────────────────────────── -->
          <div class="px-5 py-3 border-t border-gray-200 flex items-center justify-between">
            <div class="text-[11px] text-gray-400">
              <!-- 5-14g 안내: 편집은 5-14h 에서 -->
              <span v-if="dirtyCount === 0">
                💡 이 단계는 읽기 전용입니다. 분류 / 통제 편집은 다음 업데이트에서 제공됩니다.
              </span>
              <span v-else class="text-blue-600 font-medium">
                {{ dirtyCount }}건 미저장
              </span>
            </div>
            <div class="flex items-center gap-2">
              <button
                type="button"
                @click="close"
                class="h-8 px-3 text-[12px] bg-white border border-gray-200 text-gray-700 rounded-md hover:bg-gray-50 transition-colors">
                닫기
              </button>
              <!-- 5-14h 진입 시 활성화:
              <button
                type="button"
                :disabled="dirtyCount === 0"
                class="h-8 px-3 text-[12px] bg-gray-900 text-white rounded-md hover:bg-gray-800 disabled:opacity-40 transition-colors">
                변경 저장
              </button>
              -->
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.dialog-enter-active,
.dialog-leave-active {
  transition: opacity 0.15s ease;
}
.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}
</style>