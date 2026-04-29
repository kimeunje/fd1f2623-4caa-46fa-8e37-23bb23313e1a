/**
 * useControlTree — Phase 5-14g (β) 의 트리 상태 중심 composable.
 *
 * <h3>책임</h3>
 * <ul>
 *   <li>{@link treeApi.getTree} 호출 + 평탄 nodes[] → 트리 (children 보강) reconstruct</li>
 *   <li>expanded 상태 (Set&lt;number&gt;) 관리 + 펼침 default = depth=1 카테고리만</li>
 *   <li>검색 (코드 / 이름) — 매치 leaf 의 모든 ancestor 자동 펼침, 매치 없는 카테고리는 회색 처리</li>
 *   <li>상태 필터 (전체 / 완료 / 진행중 / 미수집 / 검토 대기) — leaf 만 매치 대상</li>
 *   <li>derive 카운트 — leaf 의 status, 카테고리의 자손 leaf 통계 합계</li>
 * </ul>
 *
 * <h3>데이터 흐름</h3>
 * <pre>
 *   GET /tree → flatNodes (평탄, 정렬됨)
 *      ↓ buildTree (parentId 매칭)
 *   rootNodes (children 채워진 트리)
 *      ↓ leafStatus 부여 + 검색/필터 매치 계산
 *   filteredView (visibleNodes + matchedLeafIds + dimmedNodeIds)
 * </pre>
 *
 * <h3>왜 store 가 아닌 composable 인가</h3>
 * <p>ControlsView 와 UnifiedControlsDialog 가 같은 트리 데이터를 공유한다 — 단,
 * 다이얼로그는 페이지 안에서만 띄워지고 닫힐 때 같이 unmount 된다. 별도 라우트 간
 * 공유가 아니므로 Pinia store 까지 필요 없음. composable 인스턴스를 ControlsView
 * 가 만들어 provide/inject 또는 props 로 다이얼로그에 넘기는 패턴.</p>
 *
 * <p>v15 또는 후속 phase 에서 (1) 다이얼로그가 별도 라우트로 분리되거나 (2) 다중
 * 페이지에서 트리 캐시를 공유하게 되면 useControlTreeStore 로 승격 후보.</p>
 */

import { ref, computed, type Ref } from 'vue'
import { treeApi } from '@/services/evidenceApi'
import type { TreeNode, TreeFrameworkSummary } from '@/types/evidence'

/**
 * 트리 노드 + 클라이언트 derive 필드. 원본 TreeNode 는 immutable, 본 타입이 children
 * 과 derive count 를 추가.
 */
export interface TreeNodeView extends TreeNode {
  children: TreeNodeView[]

  /** category 만 채워짐 — 자손 leaf 의 evidenceTypeCount 합계. */
  descendantEvidenceTypeCount?: number
  /** category 만 채워짐 — 자손 leaf 의 collectedCount 합계. */
  descendantCollectedCount?: number
  /** category 만 채워짐 — 자손 leaf 의 pendingReviewCount 합계. */
  descendantPendingReviewCount?: number
  /** category 만 채워짐 — 자손 leaf 의 수 (즉 controls / N 의 N). */
  descendantLeafCount?: number
}

export type LeafStatus = '완료' | '진행중' | '미수집' | '검토 대기'

/**
 * leaf 의 status derive — `pendingReviewCount > 0` 가 최우선 (검토 대기 N 배지로 노출).
 * 그 외에는 `evidenceTypeCount` / `collectedCount` 비율로 계산.
 */
export function deriveLeafStatus(node: TreeNodeView | TreeNode): LeafStatus {
  const pending = node.pendingReviewCount ?? 0
  const total = node.evidenceTypeCount ?? 0
  const collected = node.collectedCount ?? 0

  if (pending > 0) return '검토 대기'
  if (total === 0) return '미수집'
  if (collected >= total) return '완료'
  if (collected > 0) return '진행중'
  return '미수집'
}

export interface FilterState {
  searchText: string
  status: '전체' | LeafStatus
}

export function useControlTree(frameworkId: Ref<number | null>) {
  // ====================================================================
  // 원본 응답 / 빌드된 트리
  // ====================================================================

  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 서버 응답의 framework 메타 (id / name / version). */
  const framework = ref<TreeFrameworkSummary | null>(null)
  /** 서버 응답의 평탄 nodes 배열 — 본 composable 안에서만 보존. */
  const flatNodes = ref<TreeNode[]>([])

  /**
   * 트리 reconstruct.
   *
   * 서버가 (depth, parentId NULL FIRST, displayOrder) 정렬을 보장하므로 단일 패스로
   * 빌드 가능 — 부모가 자식보다 먼저 등장.
   */
  const rootNodes = computed<TreeNodeView[]>(() => {
    const byId = new Map<number, TreeNodeView>()
    const roots: TreeNodeView[] = []

    for (const n of flatNodes.value) {
      const view: TreeNodeView = { ...n, children: [] }
      byId.set(n.id, view)
      if (n.parentId == null) {
        roots.push(view)
      } else {
        const parent = byId.get(n.parentId)
        if (parent) parent.children.push(view)
        else roots.push(view)   // 고아 — 정렬 보장 깨졌을 때 안전장치
      }
    }

    // 카테고리에 descendant 합계 채움 (post-order traversal)
    function fillDescendantCounts(node: TreeNodeView): {
      evidence: number
      collected: number
      pending: number
      leafCount: number
    } {
      if (node.nodeType === 'control') {
        return {
          evidence: node.evidenceTypeCount ?? 0,
          collected: node.collectedCount ?? 0,
          pending: node.pendingReviewCount ?? 0,
          leafCount: 1,
        }
      }
      let evidence = 0, collected = 0, pending = 0, leafCount = 0
      for (const c of node.children) {
        const sub = fillDescendantCounts(c)
        evidence += sub.evidence
        collected += sub.collected
        pending += sub.pending
        leafCount += sub.leafCount
      }
      node.descendantEvidenceTypeCount = evidence
      node.descendantCollectedCount = collected
      node.descendantPendingReviewCount = pending
      node.descendantLeafCount = leafCount
      return { evidence, collected, pending, leafCount }
    }
    for (const root of roots) fillDescendantCounts(root)

    return roots
  })

  /** id → TreeNodeView 룩업 (rootNodes 재계산 시 자연 갱신). */
  const nodeById = computed<Map<number, TreeNodeView>>(() => {
    const m = new Map<number, TreeNodeView>()
    function walk(node: TreeNodeView) {
      m.set(node.id, node)
      for (const c of node.children) walk(c)
    }
    for (const r of rootNodes.value) walk(r)
    return m
  })

  /** id → leaf status derive (검색/필터 카운트 계산용). */
  const leafStatusById = computed<Map<number, LeafStatus>>(() => {
    const m = new Map<number, LeafStatus>()
    for (const n of flatNodes.value) {
      if (n.nodeType === 'control') {
        m.set(n.id, deriveLeafStatus(n))
      }
    }
    return m
  })

  // ====================================================================
  // 펼침 상태
  // ====================================================================

  /** 펼쳐진 노드 id 집합 — 카테고리만 의미 있음. leaf 클릭은 별도 expandedLeafId 로 관리. */
  const expandedNodeIds = ref<Set<number>>(new Set())

  /** depth=1 카테고리만 펼친 default 상태로 초기화. */
  function applyDefaultExpansion() {
    const next = new Set<number>()
    for (const r of rootNodes.value) {
      if (r.nodeType === 'category') next.add(r.id)
    }
    expandedNodeIds.value = next
  }

  function toggleExpand(nodeId: number) {
    const next = new Set(expandedNodeIds.value)
    if (next.has(nodeId)) next.delete(nodeId)
    else next.add(nodeId)
    expandedNodeIds.value = next
  }

  function expandAll() {
    const next = new Set<number>()
    for (const n of flatNodes.value) {
      if (n.nodeType === 'category') next.add(n.id)
    }
    expandedNodeIds.value = next
  }

  function collapseAll() {
    expandedNodeIds.value = new Set()
  }

  // ====================================================================
  // 검색 / 필터
  // ====================================================================

  const searchText = ref('')
  const statusFilter = ref<'전체' | LeafStatus>('전체')

  /** 매치된 leaf id 집합. 검색 또는 상태 필터 모두 leaf 단위 매칭. */
  const matchedLeafIds = computed<Set<number>>(() => {
    const q = searchText.value.trim().toLowerCase()
    const status = statusFilter.value
    const matched = new Set<number>()

    for (const n of flatNodes.value) {
      if (n.nodeType !== 'control') continue

      // 상태 필터
      const leafStatus = leafStatusById.value.get(n.id)
      if (status !== '전체' && leafStatus !== status) continue

      // 텍스트 검색 — 코드 / 이름. 카테고리명은 leaf 매치를 통해 ancestor 펼침으로 흐름
      if (q) {
        const codeMatch = n.code.toLowerCase().includes(q)
        const nameMatch = n.name.toLowerCase().includes(q)
        // 추가로 ancestor 카테고리명에 매치되는 경우도 포함 — 사용자가 "관리체계" 검색하면
        // 그 분류 안의 모든 leaf 가 보이는 게 자연스러움
        let ancestorMatch = false
        let cur = nodeById.value.get(n.id)?.parentId
        while (cur != null && !ancestorMatch) {
          const ancestor = nodeById.value.get(cur)
          if (!ancestor) break
          if (ancestor.code.toLowerCase().includes(q) || ancestor.name.toLowerCase().includes(q)) {
            ancestorMatch = true
          }
          cur = ancestor.parentId
        }
        if (!codeMatch && !nameMatch && !ancestorMatch) continue
      }

      matched.add(n.id)
    }
    return matched
  })

  /**
   * 매치된 leaf 의 모든 ancestor id 집합. 검색/필터 활성 시 자동 펼침 대상 +
   * 매치된 카테고리 강조 표시 (UI 가 hasMatch 로 활용).
   */
  const matchedAncestorIds = computed<Set<number>>(() => {
    const ancestors = new Set<number>()
    for (const leafId of matchedLeafIds.value) {
      let cur = nodeById.value.get(leafId)?.parentId
      while (cur != null) {
        ancestors.add(cur)
        cur = nodeById.value.get(cur)?.parentId
      }
    }
    return ancestors
  })

  /** 검색/필터 활성 여부 — UI 가 dimmed 처리 결정 + auto-expand 결정용. */
  const filterActive = computed(
    () => searchText.value.trim().length > 0 || statusFilter.value !== '전체'
  )

  /**
   * 카테고리 / leaf 가 화면에 visible 한지 (회색 처리 또는 정상 표시).
   * 정책: 매치 없는 카테고리는 회색 처리(완전 숨김 X). 매치 없는 leaf 도 회색 처리.
   */
  function isMatched(nodeId: number): boolean {
    if (!filterActive.value) return true
    if (matchedLeafIds.value.has(nodeId)) return true
    if (matchedAncestorIds.value.has(nodeId)) return true
    return false
  }

  /**
   * 검색/필터 활성 시 자동 펼침 — matched ancestors + matched leaves 자체. 사용자
   * 토글한 expandedNodeIds 와 union.
   */
  const effectiveExpandedIds = computed<Set<number>>(() => {
    if (!filterActive.value) return expandedNodeIds.value
    const u = new Set(expandedNodeIds.value)
    for (const a of matchedAncestorIds.value) u.add(a)
    return u
  })

  // ====================================================================
  // 상태 필터 카운트 (탭 옆에 표시)
  // ====================================================================

  const statusCounts = computed<Record<'전체' | LeafStatus, number>>(() => {
    const counts: Record<'전체' | LeafStatus, number> = {
      '전체': 0, '완료': 0, '진행중': 0, '미수집': 0, '검토 대기': 0,
    }
    for (const [, st] of leafStatusById.value) {
      counts['전체']++
      counts[st]++
    }
    return counts
  })

  // ====================================================================
  // Framework 카운트 (본문 헤더 서브텍스트)
  // ====================================================================

  /** 전체 leaf 수 (Framework 의 "통제 N개"). */
  const totalLeafCount = computed(
    () => flatNodes.value.filter((n) => n.nodeType === 'control').length
  )

  /** 전체 evidence_types 수 (자손 합계). */
  const totalEvidenceTypeCount = computed(() =>
    flatNodes.value
      .filter((n) => n.nodeType === 'control')
      .reduce((sum, n) => sum + (n.evidenceTypeCount ?? 0), 0)
  )

  /** 전체 검토 대기 leaf 수 (배지 카운트 등). */
  const totalPendingLeafCount = computed(
    () =>
      Array.from(leafStatusById.value.values()).filter((s) => s === '검토 대기').length
  )

  // ====================================================================
  // 데이터 로드
  // ====================================================================

  async function load() {
    if (frameworkId.value == null) return
    loading.value = true
    error.value = null
    try {
      const { data } = await treeApi.getTree(frameworkId.value)
      if (data.success) {
        framework.value = data.data.framework
        flatNodes.value = data.data.nodes
        applyDefaultExpansion()
      } else {
        error.value = data.message ?? '트리 조회에 실패했습니다.'
      }
    } catch (e: any) {
      error.value = e?.response?.data?.message ?? '트리 조회 중 오류가 발생했습니다.'
      console.error('[useControlTree.load]', e)
    } finally {
      loading.value = false
    }
  }

  /** 외부에서 변경 후 재로드 (e.g. ImportControlsDialog 완료 후, PATCH /tree 후). */
  async function reload() {
    await load()
  }

  return {
    // state
    loading,
    error,
    framework,
    flatNodes,

    // tree
    rootNodes,
    nodeById,
    leafStatusById,

    // expansion
    expandedNodeIds,
    effectiveExpandedIds,
    toggleExpand,
    expandAll,
    collapseAll,
    applyDefaultExpansion,

    // search / filter
    searchText,
    statusFilter,
    filterActive,
    matchedLeafIds,
    matchedAncestorIds,
    isMatched,
    statusCounts,

    // framework-level counts
    totalLeafCount,
    totalEvidenceTypeCount,
    totalPendingLeafCount,

    // actions
    load,
    reload,
  }
}

export type ControlTreeState = ReturnType<typeof useControlTree>