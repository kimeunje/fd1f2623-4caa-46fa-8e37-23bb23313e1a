<script setup lang="ts">
/**
 * ControlNodeRow — 재귀 트리 행 컴포넌트 (Phase 5-14g).
 *
 * <p>spec §3.3 의 N단 재귀 트리 정합. category 와 leaf 가 같은 컴포넌트 안에서
 * 분기 렌더링되며, children 이 있으면 자기 자신을 재귀 호출 (Vue 3 의 self-import
 * 우회를 위해 컴포넌트 등록).</p>
 *
 * <h3>props</h3>
 * <ul>
 *   <li>{@code node} — TreeNodeView (children 채워진 표현)</li>
 *   <li>{@code mode} — 'view' (ControlsView 페이지) | 'dialog' (UnifiedControlsDialog).
 *       view 는 6컬럼 grid + leaf 클릭 시 증빙 카드 펼침, dialog 는 카운트만 (Q6=B)</li>
 *   <li>{@code expandedIds} — 펼친 카테고리 id 집합 (composable 의 effectiveExpandedIds)</li>
 *   <li>{@code expandedLeafId} — 펼친 leaf id (view 모드에서만 의미, evidence cards 표시)</li>
 *   <li>{@code controlDetail} — expandedLeafId 의 상세 (evidence_types). view 모드에서만</li>
 *   <li>{@code detailLoading} — 상세 로딩 중 여부 (view 모드)</li>
 *   <li>{@code dimmed} — 검색/필터 매치 안 된 경우 회색 처리 (composable.isMatched)</li>
 * </ul>
 *
 * <h3>emits</h3>
 * <ul>
 *   <li>{@code toggle-expand} — 카테고리 또는 leaf 토글. parent 가 expandedIds 또는
 *       expandedLeafId 갱신</li>
 *   <li>{@code go-evidence-type} — leaf 의 evidence card 클릭. detail page 라우팅</li>
 *   <li>{@code add-evidence-type} — leaf 의 [+ 증빙 유형] 클릭 (view 모드)</li>
 *   <li>{@code zip-download} — leaf 의 [ZIP 다운로드] 클릭 (view 모드)</li>
 *   <li>{@code delete-evidence-type} — evidence card 의 우측 🗑 클릭</li>
 * </ul>
 */
import { computed } from 'vue'
import type { TreeNodeView, LeafStatus } from '@/composables/useControlTree'
import type { ControlDetail, EvidenceTypeResponse, ReviewStatus } from '@/types/evidence'

const props = withDefaults(
  defineProps<{
    node: TreeNodeView
    mode?: 'view' | 'dialog'
    expandedIds: Set<number>
    expandedLeafId?: number | null
    controlDetail?: ControlDetail | null
    detailLoading?: boolean
    dimmed?: boolean
    /** depth 시각 보정용. 실제 들여쓰기는 node.depth 기반이지만 root 여부 캡처. */
    isRoot?: boolean
  }>(),
  {
    mode: 'view',
    expandedLeafId: null,
    controlDetail: null,
    detailLoading: false,
    dimmed: false,
    isRoot: false,
  },
)

const emit = defineEmits<{
  (e: 'toggle-expand', nodeId: number, nodeType: 'category' | 'control'): void
  (e: 'go-evidence-type', evidenceTypeId: number, controlId: number): void
  (e: 'add-evidence-type', controlId: number): void
  (e: 'zip-download', controlId: number, controlCode: string): void
  (e: 'delete-evidence-type', evidenceTypeId: number, evidenceTypeName: string): void
}>()

const isCategory = computed(() => props.node.nodeType === 'category')
const isLeaf = computed(() => props.node.nodeType === 'control')
const isExpanded = computed(() => props.expandedIds.has(props.node.id))
const isLeafExpanded = computed(
  () => props.mode === 'view' && props.expandedLeafId === props.node.id,
)

/** depth 별 들여쓰기 (px). spec §3.3: 1=16, 이후 +20. */
const indentPx = computed(() => 16 + (props.node.depth - 1) * 20)

/** category 의 자손 leaf 수 (e.g. "통제 16"). */
const descendantLeafCount = computed(() => props.node.descendantLeafCount ?? 0)

/** leaf status derive (composable 과 동일 로직, 본 컴포넌트에서 직접 사용). */
const leafStatus = computed<LeafStatus | null>(() => {
  if (!isLeaf.value) return null
  const pending = props.node.pendingReviewCount ?? 0
  const total = props.node.evidenceTypeCount ?? 0
  const collected = props.node.collectedCount ?? 0
  if (pending > 0) return '검토 대기'
  if (total === 0) return '미수집'
  if (collected >= total) return '완료'
  if (collected > 0) return '진행중'
  return '미수집'
})

const hasPending = computed(() => (props.node.pendingReviewCount ?? 0) > 0)

/** 진행률 (0~1). evidenceTypeCount=0 이면 0. */
const progressRatio = computed(() => {
  const total = props.node.evidenceTypeCount ?? 0
  if (total === 0) return 0
  return Math.min(1, (props.node.collectedCount ?? 0) / total)
})

/** spec §3.3 의 status badge 클래스. */
const statusBadgeClass = computed(() => {
  switch (leafStatus.value) {
    case '완료':
      return 'bg-green-100 text-green-700'
    case '진행중':
      return 'bg-blue-100 text-blue-700'
    case '검토 대기':
      return 'bg-blue-100 text-blue-700 font-semibold'
    case '미수집':
    default:
      return 'bg-gray-100 text-gray-500'
  }
})

const statusBadgeLabel = computed(() => {
  if (leafStatus.value === '검토 대기') {
    return `● 검토 대기 ${props.node.pendingReviewCount}`
  }
  return leafStatus.value ?? ''
})

/** category depth 별 텍스트 크기 / 굵기 (spec §3.3). */
const categoryNameClass = computed(() => {
  switch (props.node.depth) {
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
})

function onToggle() {
  emit('toggle-expand', props.node.id, props.node.nodeType)
}

function onEvidenceTypeClick(et: EvidenceTypeResponse) {
  emit('go-evidence-type', et.id, props.node.id)
}

function onAddEvidenceType() {
  emit('add-evidence-type', props.node.id)
}

function onZipDownload() {
  emit('zip-download', props.node.id, props.node.code)
}

function onDeleteEvidenceType(et: EvidenceTypeResponse) {
  emit('delete-evidence-type', et.id, et.name)
}

// ─── 증빙 카드 헬퍼 (view 모드 leaf 펼침에서 사용) ───
function reviewStatusBadge(status?: ReviewStatus): { label: string; cls: string } | null {
  switch (status) {
    case 'pending':
      return { label: '● 검토 대기', cls: 'bg-blue-100 text-blue-700' }
    case 'approved':
      return { label: '승인', cls: 'bg-green-100 text-green-700' }
    case 'rejected':
      return { label: '반려', cls: 'bg-red-100 text-red-700' }
    case 'auto_approved':
      return { label: '자동 승인', cls: 'bg-gray-100 text-gray-600' }
    default:
      return null
  }
}

function formatFileSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatDateTime(s?: string) {
  if (!s) return '-'
  const d = new Date(s)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${day} ${hh}:${mm}`
}
</script>

<template>
  <!-- ────────────────────── 카테고리 ────────────────────── -->
  <template v-if="isCategory">
    <div
      class="flex items-center gap-2 py-2 cursor-pointer hover:bg-gray-50 transition-colors"
      :class="[dimmed ? 'opacity-40' : '']"
      :style="{ paddingLeft: indentPx + 'px', paddingRight: '16px' }"
      @click="onToggle">
      <i
        class="pi pi-chevron-down text-[10px] text-gray-400 transition-transform shrink-0"
        :class="isExpanded ? '' : '-rotate-90'"
      />
      <span class="flex-1 truncate" :class="categoryNameClass">
        {{ node.code === node.name ? node.name : (node.code + ' ' + node.name) }}
      </span>
      <span class="text-[11px] text-gray-400 tabular-nums shrink-0">
        통제 {{ descendantLeafCount }}
      </span>
    </div>

    <!-- 자식 — 펼침 시만 -->
    <div v-if="isExpanded" class="border-l border-gray-100">
      <ControlNodeRow
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :mode="mode"
        :expanded-ids="expandedIds"
        :expanded-leaf-id="expandedLeafId"
        :control-detail="controlDetail"
        :detail-loading="detailLoading"
        :dimmed="dimmed"
        @toggle-expand="(id, t) => emit('toggle-expand', id, t)"
        @go-evidence-type="(etId, cId) => emit('go-evidence-type', etId, cId)"
        @add-evidence-type="(cId) => emit('add-evidence-type', cId)"
        @zip-download="(cId, code) => emit('zip-download', cId, code)"
        @delete-evidence-type="(etId, name) => emit('delete-evidence-type', etId, name)"
      />
    </div>
  </template>

  <!-- ────────────────────── leaf (control) ────────────────────── -->
  <template v-else-if="isLeaf">
    <!-- view 모드: 6컬럼 grid -->
    <div
      v-if="mode === 'view'"
      class="grid items-center gap-3 py-2 cursor-pointer transition-colors border-b border-gray-50"
      :class="[
        isLeafExpanded ? 'bg-blue-50' : (hasPending ? 'bg-blue-50/40 hover:bg-blue-50/60' : 'hover:bg-gray-50'),
        dimmed ? 'opacity-40' : '',
      ]"
      :style="{
        gridTemplateColumns: '64px 1fr 60px 90px 100px 24px',
        paddingLeft: indentPx + 'px',
        paddingRight: '16px',
      }"
      @click="onToggle">
      <span class="font-mono text-[12px] text-blue-600 font-medium tabular-nums truncate">
        {{ node.code }}
      </span>
      <span class="text-[13px] text-gray-900 truncate">{{ node.name }}</span>
      <span class="text-[12px] text-gray-600 text-right tabular-nums">
        증빙 {{ node.evidenceTypeCount ?? 0 }}
      </span>
      <span class="flex items-center gap-1.5 text-[11px] text-gray-600">
        <span class="tabular-nums shrink-0">
          {{ node.collectedCount ?? 0 }}/{{ node.evidenceTypeCount ?? 0 }}
        </span>
        <span class="w-10 h-[3px] bg-gray-200 rounded-full overflow-hidden">
          <span
            class="block h-full transition-all"
            :class="leafStatus === '완료' ? 'bg-green-500' : 'bg-blue-500'"
            :style="{ width: progressRatio * 100 + '%' }"
          />
        </span>
      </span>
      <span>
        <span
          v-if="leafStatus"
          class="inline-block px-2 py-0.5 rounded text-[11px] leading-tight"
          :class="statusBadgeClass">
          {{ statusBadgeLabel }}
        </span>
      </span>
      <i
        class="pi pi-chevron-right text-[12px] text-gray-300 transition-transform"
        :class="isLeafExpanded ? 'rotate-90 text-gray-500' : ''"
      />
    </div>

    <!-- view 모드: 펼쳐진 leaf 의 증빙 카드 패널 -->
    <div
      v-if="mode === 'view' && isLeafExpanded"
      class="bg-blue-50/50 border-b border-blue-100"
      :style="{ paddingLeft: (indentPx + 12) + 'px', paddingRight: '16px', paddingTop: '12px', paddingBottom: '16px' }">
      <!-- 로딩 -->
      <div v-if="detailLoading" class="text-center py-6 text-gray-400 text-[13px]">
        <i class="pi pi-spin pi-spinner mr-2"></i>로딩 중...
      </div>

      <div v-else-if="controlDetail">
        <!-- 헤더 -->
        <div class="flex items-center justify-between mb-3">
          <h4 class="text-[13px] font-semibold text-gray-700">
            수집된 증빙 파일
            <span class="text-gray-400 font-normal ml-2">
              {{ controlDetail.evidenceCollected }}/{{ controlDetail.evidenceTotal }}
            </span>
            <span
              v-if="hasPending"
              class="ml-2 inline-block px-1.5 py-0.5 rounded text-[10px] bg-blue-100 text-blue-700 font-semibold">
              검토 대기 {{ node.pendingReviewCount }}
            </span>
          </h4>
          <div class="flex items-center gap-1.5">
            <button
              @click.stop="onZipDownload"
              class="h-7 px-2.5 text-[11px] bg-white border border-gray-200 text-gray-700 rounded hover:bg-gray-50 inline-flex items-center gap-1">
              <i class="pi pi-download text-[9px]"></i>
              ZIP 다운로드
            </button>
            <button
              @click.stop="onAddEvidenceType"
              class="h-7 px-2.5 text-[11px] bg-gray-900 text-white rounded hover:bg-gray-800 inline-flex items-center gap-1">
              <i class="pi pi-plus text-[9px]"></i>
              증빙 유형
            </button>
          </div>
        </div>

        <!-- 증빙 유형 카드 (v12 5-12b 단순화 — 본 컴포넌트에 흡수) -->
        <div
          v-if="controlDetail.evidenceTypes.length > 0"
          class="space-y-1.5">
          <div
            v-for="et in controlDetail.evidenceTypes"
            :key="et.id"
            class="bg-white border rounded-lg overflow-hidden"
            :class="(et.files && et.files.length > 0 && et.files[0].reviewStatus === 'pending')
              ? 'border-blue-200'
              : 'border-gray-200'">
            <div
              class="flex items-center p-3 gap-2 group transition-colors"
              :class="(et.files && et.files.length > 0 && et.files[0].reviewStatus === 'pending')
                ? 'bg-blue-50/40'
                : ''">
              <button
                @click.stop="onEvidenceTypeClick(et)"
                class="flex items-center gap-2 flex-1 min-w-0 text-left"
                :title="`${et.name} 상세 보기`">
                <i class="pi pi-file text-gray-400 shrink-0 group-hover:text-blue-500 transition-colors" />
                <span class="text-[13px] font-medium text-gray-900 group-hover:text-blue-600 transition-colors shrink-0">
                  {{ et.name }}
                </span>

                <template v-if="et.files && et.files.length > 0">
                  <span
                    v-if="reviewStatusBadge(et.files[0].reviewStatus)"
                    class="px-1.5 py-0.5 text-[11px] rounded font-medium shrink-0"
                    :class="reviewStatusBadge(et.files[0].reviewStatus)!.cls">
                    {{ reviewStatusBadge(et.files[0].reviewStatus)!.label }}
                  </span>
                </template>
                <span
                  v-else
                  class="px-1.5 py-0.5 bg-red-100 text-red-600 text-[11px] rounded font-medium shrink-0">
                  미수집
                </span>

                <span
                  v-if="et.files && et.files.length > 0"
                  class="text-[11px] text-gray-500 truncate">
                  v{{ et.files[0].version }} · {{ formatFileSize(et.files[0].fileSize) }}
                  <template v-if="et.files[0].uploadedByName">
                    · {{ et.files[0].uploadedByName }}
                  </template>
                  · {{ formatDateTime(et.files[0].createdAt) }}
                </span>

                <i class="pi pi-chevron-right text-[11px] text-gray-300 group-hover:text-gray-500 ml-auto shrink-0 transition-colors" />
              </button>
              <button
                @click.stop="onDeleteEvidenceType(et)"
                class="p-1.5 text-gray-400 hover:text-red-500 transition-colors shrink-0"
                title="증빙 유형 삭제">
                <i class="pi pi-trash text-[13px]"></i>
              </button>
            </div>
          </div>
        </div>

        <!-- 빈 상태 -->
        <div
          v-else
          class="text-center py-6 text-[13px] text-gray-400 border border-dashed border-gray-200 rounded-lg">
          등록된 증빙 유형이 없습니다.
          <span class="text-gray-300 mx-1">·</span>
          위 [증빙 유형] 버튼으로 추가해주세요.
        </div>
      </div>
    </div>

    <!-- dialog 모드: 4컬럼 (코드 / 이름 / 증빙 N / chevron) — Q6=B 구조 편집 집중 -->
    <div
      v-else-if="mode === 'dialog'"
      class="grid items-center gap-3 py-1.5 hover:bg-gray-50 transition-colors"
      :class="[dimmed ? 'opacity-40' : '']"
      :style="{
        gridTemplateColumns: '64px 1fr auto 12px',
        paddingLeft: indentPx + 'px',
        paddingRight: '16px',
      }">
      <span class="font-mono text-[11.5px] text-gray-700 tabular-nums truncate">
        {{ node.code }}
      </span>
      <span class="text-[13px] text-gray-900 truncate">{{ node.name }}</span>
      <span class="text-[11px] text-gray-400 tabular-nums">
        증빙 {{ node.evidenceTypeCount ?? 0 }}
      </span>
      <span></span>
    </div>
  </template>
</template>