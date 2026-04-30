/**
 * Phase 5-14g + 5-14h — useControlTree composable
 *
 * 5-14g 인터페이스 (`useControlTree(Ref<number|null>)`, load/reload,
 * searchText, statusFilter '전체'/'완료'/'진행중'/'미수집'/'검토 대기',
 * effectiveExpandedIds, isMatched(id), toggleExpand(id) 등) **모두 보존**.
 * 5-14h 추가 기능은 `dialog*` / `dirty*` / `unifiedNodes` / `saveTree()` /
 * 편집 액션 등으로 별도 namespace 분리.
 *
 * 5-14h 결정:
 *   Q1=B 페이지 검색 / 다이얼로그 검색 분리 (`searchText` / `dialogSearch`)
 *   Q2=A AddControlDialog 즉시 삭제 (composable 무영향, ControlsView 측)
 *   Q3=혼합 Tab 은 draft 한정. existing leaf 변환은 'requires_save_first' 반환
 *   Q4=B+헤더 dialog 헤더 메타 (categoryCount/controlCount/dirtyCount) + 푸터 카운트
 *
 * Vue 3.5 SFC 컴파일러 학습: 본 composable 노출 메서드는 컴포넌트 템플릿에서
 * 단일 expression 으로 호출되도록 설계 (`tree.createChildControl(node)` 한 줄).
 *
 * spec §3.3.1.4 (PATCH /tree) + §3.3.1.5 (코드 변경 경고) + §3.3.1.6 (단축키) 정합.
 */

import {
  computed,
  reactive,
  ref,
  type ComputedRef,
  type InjectionKey,
  type Ref,
} from 'vue'
import { treeApi, type AxiosErrorLike } from '@/services/evidenceApi'
import type {
  ImpactSummary,
  TreeFrameworkSummary,
  TreeNode,
  TreePatchErrorResponse,
  TreePatchPayload,
  TreeValidationDetail,
} from '@/types/evidence'

// ============================================================================
// 5-14g 인터페이스 — 페이지 본문용 status 라벨 (한국어)
// ============================================================================

export type LeafStatus = '완료' | '진행중' | '미수집' | '검토 대기'
export type StatusFilterValue = '전체' | LeafStatus

// ============================================================================
// 5-14g — 트리 빌드 결과 (재귀 children 포함)
// ============================================================================

/**
 * children 빌드된 트리 노드 — 5-14g 의 ControlNodeRow 가 props 로 받는 형태.
 * 5-14h 의 unified (서버+dirty) 와 구분 위해 별도 타입으로 보존.
 */
export interface TreeRootNode extends TreeNode {
  children: TreeRootNode[]
  /** 자손 leaf 카운트 (post-order 합) */
  descendantLeafCount: number
  /** 자손 leaf 의 evidenceTypeCount 합 */
  descendantEvidenceTypeCount: number
  /** 자손 leaf 의 collectedCount 합 */
  descendantCollectedCount: number
  /** 자손 leaf 의 pendingReviewCount 합 */
  descendantPendingReviewCount: number
}

// ============================================================================
// 5-14h — dirty state
// ============================================================================

export type DraftParentId = number | string | null

export interface DraftNode {
  tempId: string
  parentId: DraftParentId
  nodeType: 'category' | 'control'
  code: string
  name: string
  description?: string
  displayOrder: number
  depth: number
}

export interface UpdatedFields {
  id: number
  code?: string
  name?: string
  description?: string
}

export interface MovedFields {
  id: number
  newParentId: number | null
  newDisplayOrder: number
  newDepth: number
}

export interface DirtyChanges {
  created: Map<string, DraftNode>
  updated: Map<number, UpdatedFields>
  moved: Map<number, MovedFields>
  deleted: Set<number>
}

// ============================================================================
// 5-14h — 통합 노드 (서버 + dirty 결합) — 다이얼로그 편집 모드 트리용
// ============================================================================

export interface UnifiedNode {
  _kind: 'existing' | 'draft'
  id: number
  tempId?: string
  _key: string

  parentId: number | null
  parentTempId: string | null

  nodeType: 'category' | 'control'
  code: string
  name: string
  description?: string
  displayOrder: number
  depth: number

  evidenceTypeCount?: number
  collectedCount?: number
  pendingReviewCount?: number

  _dirty: 'created' | 'updated' | 'moved' | 'deleted' | null
  _validationErrors: TreeValidationDetail[]

  children: UnifiedNode[]
  descendantLeafCount: number
}

// ============================================================================
// 5-14h — 저장 결과 union
// ============================================================================

export interface SaveSuccess {
  ok: true
  newVersion: number
  mappings: Map<string, number>
}

export interface SaveConflict {
  ok: false
  kind: 'conflict'
  currentVersion: number
}

export interface SaveValidation {
  ok: false
  kind: 'validation'
  details: TreeValidationDetail[]
}

export interface SaveError {
  ok: false
  kind: 'error'
  message: string
}

export type SaveResult = SaveSuccess | SaveConflict | SaveValidation | SaveError

// ============================================================================
// 5-14h — Tab 변환 결과
// ============================================================================

export type ConvertResult =
  | { ok: true; childDraft: DraftNode }
  | { ok: false; reason: 'requires_save_first' | 'depth_exceeded' }

// ============================================================================
// composable
// ============================================================================

/**
 * @param frameworkIdRef — Ref<number|null>. 5-14g 인터페이스 보존. ControlsView 가
 * `await tree.load()` 명시 호출하는 패턴 유지 (composable 내부 watch 안 함).
 */
export function useControlTree(frameworkIdRef: Ref<number | null>) {
  // ─────────────────────── 5-14g 서버 상태 ───────────────────────
  const framework = ref<TreeFrameworkSummary | null>(null)
  const flatNodes = ref<TreeNode[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  // ─────────────────────── 5-14g 페이지 본문 검색/필터 ───────────────────────
  const searchText = ref('')
  const statusFilter = ref<StatusFilterValue>('전체')
  const expandedIds = ref<Set<number>>(new Set())

  // ─────────────────────── 5-14h 다이얼로그 분리 (Q1=B) ───────────────────────
  const dialogSearch = ref('')
  const dialogExpandedIds = ref<Set<number>>(new Set())
  const dialogExpandedTempIds = ref<Set<string>>(new Set())

  // ─────────────────────── 5-14h dirty state ───────────────────────
  const dirtyChanges = reactive<DirtyChanges>({
    created: new Map(),
    updated: new Map(),
    moved: new Map(),
    deleted: new Set(),
  })

  /** dirty 변경 시 +1, computed 트리거용 */
  const dirtyVersion = ref(0)
  const bumpDirty = () => {
    dirtyVersion.value++
  }

  const validationErrors = ref<TreeValidationDetail[]>([])
  const isSaving = ref(false)

  // ─────────────────────── tempId 카운터 ───────────────────────
  let tempIdCounter = 0
  function nextTempId(): string {
    tempIdCounter++
    return `temp_${Date.now().toString(36)}_${tempIdCounter}`
  }

  // ─────────────────────── 5-14g 트리 빌드 (children 포함) ───────────────────────
  const rootNodes = computed<TreeRootNode[]>(() => {
    const byId = new Map<number, TreeRootNode>()
    for (const n of flatNodes.value) {
      byId.set(n.id, {
        ...n,
        children: [],
        descendantLeafCount: 0,
        descendantEvidenceTypeCount: 0,
        descendantCollectedCount: 0,
        descendantPendingReviewCount: 0,
      })
    }
    const roots: TreeRootNode[] = []
    for (const n of flatNodes.value) {
      const node = byId.get(n.id)!
      if (n.parentId === null) roots.push(node)
      else byId.get(n.parentId)?.children.push(node)
    }
    // 정렬
    const sortChildren = (n: TreeRootNode) => {
      n.children.sort((a, b) => a.displayOrder - b.displayOrder)
      for (const c of n.children) sortChildren(c)
    }
    roots.sort((a, b) => a.displayOrder - b.displayOrder)
    for (const r of roots) sortChildren(r)
    // post-order 자손 카운트 합
    //
    // v15 Phase 5-15a (hybrid, spec §3.3.1.9):
    //   mutex 폐기. leaf 도 자식 보유 가능 → 자식 재귀를 nodeType 무관 항상 수행.
    //   자체 contribution 만 leaf/category 분기 (leaf=1, category=0).
    //   기존 5-14g 의 leaf early-return 제거.
    const computeCounts = (n: TreeRootNode) => {
      // 자체 contribution
      // - descendantLeafCount: leaf 면 1, category 면 0
      // - 나머지 3 카운트: 자체 evidence/collected/pending (모든 노드 가능, hybrid)
      let leafs = n.nodeType === 'control' ? 1 : 0
      let ets   = n.evidenceTypeCount     ?? 0
      let col   = n.collectedCount        ?? 0
      let pend  = n.pendingReviewCount    ?? 0
      // 자식 재귀 + 합 (hybrid: leaf 도 자식 보유 가능)
      for (const c of n.children) {
        computeCounts(c)
        leafs += c.descendantLeafCount
        ets   += c.descendantEvidenceTypeCount
        col   += c.descendantCollectedCount
        pend  += c.descendantPendingReviewCount
      }
      n.descendantLeafCount = leafs
      n.descendantEvidenceTypeCount = ets
      n.descendantCollectedCount = col
      n.descendantPendingReviewCount = pend
    }
    for (const r of roots) computeCounts(r)
    return roots
  })

  // ─────────────────────── 5-14g 통계 ───────────────────────
  const totalLeafCount = computed(() => {
    let n = 0
    for (const r of rootNodes.value) n += r.descendantLeafCount
    return n
  })

  const totalEvidenceTypeCount = computed(() => {
    let n = 0
    for (const r of rootNodes.value) n += r.descendantEvidenceTypeCount
    return n
  })

  // ─────────────────────── 5-14g leaf status 도출 ───────────────────────
  function leafStatusOf(node: TreeNode): LeafStatus {
    if (node.nodeType !== 'control') return '미수집'
    const m = node.evidenceTypeCount ?? 0
    const n = node.collectedCount ?? 0
    if (m === 0) return '미수집'
    if (n >= m) return '완료'
    if (n > 0) return '진행중'
    return '미수집'
  }

  // ─────────────────────── 5-14g statusCounts ───────────────────────
  const statusCounts = computed<Record<StatusFilterValue, number>>(() => {
    const c: Record<StatusFilterValue, number> = {
      전체: 0, 완료: 0, 진행중: 0, 미수집: 0, '검토 대기': 0,
    }
    for (const n of flatNodes.value) {
      if (n.nodeType !== 'control') continue
      c['전체']++
      c[leafStatusOf(n)]++
      if ((n.pendingReviewCount ?? 0) > 0) c['검토 대기']++
    }
    return c
  })

  // ─────────────────────── 5-14g 검색/필터 매치 ───────────────────────
  /** leaf 매치 = (검색 매치) AND (필터 매치) */
  function nodeMatchesPageSearch(node: TreeNode): boolean {
    if (!searchText.value) return true
    const q = searchText.value.toLowerCase()
    return (
      node.code.toLowerCase().includes(q) ||
      node.name.toLowerCase().includes(q)
    )
  }

  function leafMatchesStatusFilter(node: TreeNode): boolean {
    if (statusFilter.value === '전체') return true
    if (node.nodeType !== 'control') return false
    if (statusFilter.value === '검토 대기') {
      return (node.pendingReviewCount ?? 0) > 0
    }
    return leafStatusOf(node) === statusFilter.value
  }

  const filterActive = computed<boolean>(() => {
    return searchText.value.length > 0 || statusFilter.value !== '전체'
  })

  /**
   * 매치된 leaf 의 모든 조상 ID — 자동 펼침 + dimmed 판정용
   */
  const matchedAncestorIds = computed<Set<number>>(() => {
    const ids = new Set<number>()
    if (!filterActive.value) return ids
    const parentMap = new Map<number, number | null>()
    for (const n of flatNodes.value) parentMap.set(n.id, n.parentId)
    for (const n of flatNodes.value) {
      if (n.nodeType !== 'control') continue
      if (!nodeMatchesPageSearch(n)) continue
      if (!leafMatchesStatusFilter(n)) continue
      // 자기 + 모든 조상 추가
      let cursor: number | null = n.id
      while (cursor !== null) {
        ids.add(cursor)
        cursor = parentMap.get(cursor) ?? null
      }
    }
    return ids
  })

  /** 5-14g — 매치 상태 (페이지 본문 dimmed 판정) */
  function isMatched(id: number): boolean {
    if (!filterActive.value) return true
    return matchedAncestorIds.value.has(id)
  }

  /**
   * 효과 펼침 = 명시 펼침 + 검색 매치 자동 펼침
   */
  const effectiveExpandedIds = computed<Set<number>>(() => {
    if (!filterActive.value) return expandedIds.value
    return new Set([...expandedIds.value, ...matchedAncestorIds.value])
  })

  // ─────────────────────── 5-14g 펼침 토글 ───────────────────────
  function toggleExpand(id: number): void {
    const next = new Set(expandedIds.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    expandedIds.value = next
  }

  // ════════════════════════════════════════════════════════════════════════
  // Phase 5-14i 폴리싱 — 페이지 본문 검색 인터랙션 (P12, P14, P19, P21)
  //
  // 본 영역은 5-14g 인터페이스 (searchText, statusFilter, expandedIds 등) 와
  // 5-14h 의 다이얼로그 분리 (dialogSearch 등) 모두 보존하면서 페이지 본문 검색
  // 의 UX 만 보강합니다. 다이얼로그 검색은 영향 없음.
  // ════════════════════════════════════════════════════════════════════════

  /**
   * P14 (5-14i v3) — 검색 debounce 200ms.
   *
   * `searchText` 는 input 즉시 갱신되지만, 무거운 매치/하이라이트는
   * `debouncedSearchText` 를 watch 한 외부 측 (ControlsView) 이 처리.
   * spec §3.3.1.5 line 547 (debounce 200ms) 정합.
   *
   * 사용 예 (ControlsView):
   *   watch(tree.debouncedSearchText, () => tree.applySearchSideEffects())
   */
  const debouncedSearchText = ref('')
  let _searchDebounceTimer: ReturnType<typeof setTimeout> | null = null
  // searchText 변경 → 200ms 후 debouncedSearchText 갱신
  // (composable 안에서 직접 watch 하기보다 변경시점 hook 으로 단순화)
  function setSearchText(v: string): void {
    searchText.value = v
    if (_searchDebounceTimer) clearTimeout(_searchDebounceTimer)
    _searchDebounceTimer = setTimeout(() => {
      debouncedSearchText.value = v
      applySearchSideEffects()
    }, 200)
  }

  /**
   * P21 (5-14i v5) — 검색 입력 시 펼친 leaf-expand 패널 자동 해제.
   *
   * 페이지 본문에서 사용자가 펼쳐둔 leaf 의 evidence 카드 패널은 검색 결과를
   * 가릴 수 있으므로 검색어 입력 시점에 자동 닫힘. 자식 분기 (effectiveExpandedIds /
   * matchedAncestorIds 자동 펼침) 와 시너지.
   *
   * ControlsView 가 expandedLeafId / controlDetail 을 자체 관리 (composable 외부) 하므로
   * 본 helper 는 콜백 등록 패턴으로 외부에 위임.
   */
  type SearchHook = () => void
  const _searchHooks = new Set<SearchHook>()
  function onSearchApplied(hook: SearchHook): () => void {
    _searchHooks.add(hook)
    return () => _searchHooks.delete(hook)
  }

  /**
   * 검색 적용 사이드 이펙트 — debounce 발화 시점에 호출.
   * P21 의 expandedLeafId 해제, P19 의 focusFirstMatch 실행 trigger.
   */
  function applySearchSideEffects(): void {
    for (const h of _searchHooks) h()
  }

  /**
   * P12 (5-14i v3) — 카테고리별 매치 카운트.
   *
   * 검색어 입력 시 각 카테고리 우측에 `매치 N` 핍 노출용. 자손 leaf 중 검색
   * 매치 + 필터 매치 동시 충족인 leaf 의 누적 카운트.
   *
   * Map<categoryId, count>. 검색어 없거나 매치 0 인 카테고리는 entry 없음.
   */
  const matchCountByCategoryId = computed<Map<number, number>>(() => {
    const map = new Map<number, number>()
    if (!filterActive.value) return map
    const parentMap = new Map<number, number | null>()
    for (const n of flatNodes.value) parentMap.set(n.id, n.parentId)
    for (const n of flatNodes.value) {
      if (n.nodeType !== 'control') continue
      if (!nodeMatchesPageSearch(n)) continue
      if (!leafMatchesStatusFilter(n)) continue
      // 모든 조상 카테고리에 +1
      let cursor = parentMap.get(n.id) ?? null
      while (cursor !== null) {
        map.set(cursor, (map.get(cursor) ?? 0) + 1)
        cursor = parentMap.get(cursor) ?? null
      }
    }
    return map
  })

  /**
   * P14 (5-14i v3) — 검색 매치 substring 하이라이트 헬퍼.
   *
   * 텍스트 안 첫 매치만 amber 하이라이트로 감싼 HTML 반환. 일치 없으면 escape 만.
   * v6 mockup 의 .match-highlight 클래스 사용 (ControlNodeRow.vue 에서 정의).
   *
   * XSS 방지: text 의 `& < >` 만 entity escape, query 는 lower-case 매치만 사용.
   */
  function highlightMatch(text: string, queryRaw?: string): string {
    const query = (queryRaw ?? debouncedSearchText.value).trim().toLowerCase()
    const escape = (s: string) =>
      s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c] ?? c))
    if (!query) return escape(text)
    const lower = text.toLowerCase()
    const idx = lower.indexOf(query)
    if (idx === -1) return escape(text)
    const before = escape(text.slice(0, idx))
    const match = escape(text.slice(idx, idx + query.length))
    const after = escape(text.slice(idx + query.length))
    return `${before}<span class="match-highlight">${match}</span>${after}`
  }

  /**
   * P19 (5-14i v4) — 첫 매치 항목 자동 포커스.
   *
   * ControlsView 가 트리 본문 root 요소를 ref 로 가리키고, debounced 검색
   * 발화 후 호출. 첫 매치 leaf 의 row 를 scrollIntoView({block:'center'}) +
   * 1.4s flash 애니메이션. flash CSS 는 ControlNodeRow.vue scoped style.
   *
   * @param container - 트리 본문 root (typeof Element). null 이면 noop.
   */
  function focusFirstMatchInContainer(container: Element | null): void {
    if (!container) return
    // 이전 flash 제거
    container.querySelectorAll('.focus-flash').forEach((el) => {
      el.classList.remove('focus-flash')
    })
    const firstHighlight = container.querySelector('.match-highlight')
    if (!firstHighlight) return
    const row = firstHighlight.closest('.row-view, .leaf-row, .category-row')
    if (!(row instanceof HTMLElement)) return
    row.scrollIntoView({ behavior: 'smooth', block: 'center' })
    // reflow trigger for animation restart
    void row.offsetWidth
    row.classList.add('focus-flash')
    setTimeout(() => row.classList.remove('focus-flash'), 1500)
  }

  /**
   * 매치 leaf (검색 + 필터 동시 매치) 카운트 — P18 의 no-results 자동 전환 정확도용.
   * matchedAncestorIds 와 다르게 leaf 만 카운트.
   */
  const visibleLeafCount = computed<number>(() => {
    if (!filterActive.value) {
      // 필터 비활성 시 전체 leaf 노출
      let n = 0
      for (const node of flatNodes.value) {
        if (node.nodeType === 'control') n++
      }
      return n
    }
    let n = 0
    for (const node of flatNodes.value) {
      if (node.nodeType !== 'control') continue
      if (!nodeMatchesPageSearch(node)) continue
      if (!leafMatchesStatusFilter(node)) continue
      n++
    }
    return n
  })

  // ─────────────────────── 5-14g 로드 / 재로드 ───────────────────────
  async function load(): Promise<void> {
    const fwId = frameworkIdRef.value
    if (fwId === null) return
    loading.value = true
    error.value = null
    try {
      const response = await treeApi.getTree(fwId)
      const body = response.data
      if (!body.success) {
        error.value = body.message ?? '트리를 불러오지 못했습니다'
        return
      }
      framework.value = body.data.framework
      flatNodes.value = body.data.nodes
      // dirty 자동 리셋 (load 가 처음 호출 + Framework 전환 시점)
      dirtyChanges.created.clear()
      dirtyChanges.updated.clear()
      dirtyChanges.moved.clear()
      dirtyChanges.deleted.clear()
      validationErrors.value = []
      bumpDirty()
      // 기본 펼침 = depth=1 카테고리만 (5-14g Q3=A)
      expandedIds.value = new Set(
        body.data.nodes
          .filter((n) => n.depth === 1 && n.nodeType === 'category')
          .map((n) => n.id),
      )
      dialogExpandedIds.value = new Set(expandedIds.value)
      dialogExpandedTempIds.value.clear()
    } catch (e: unknown) {
      error.value = (e as Error).message ?? '트리를 불러오지 못했습니다'
    } finally {
      loading.value = false
    }
  }

  /** 펼침 상태/검색 보존하면서 트리만 재로드 */
  async function reload(): Promise<void> {
    const fwId = frameworkIdRef.value
    if (fwId === null) return
    loading.value = true
    error.value = null
    try {
      const response = await treeApi.getTree(fwId)
      const body = response.data
      if (!body.success) {
        error.value = body.message ?? '트리를 불러오지 못했습니다'
        return
      }
      framework.value = body.data.framework
      flatNodes.value = body.data.nodes
      // reload 는 dirty 보존 (사용자 작업 중 외부 변경 추적 안 함 — saveTree 가 reload 직전에 dirty clear 호출)
    } catch (e: unknown) {
      error.value = (e as Error).message ?? '트리를 불러오지 못했습니다'
    } finally {
      loading.value = false
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // 이하 5-14h 추가 — 다이얼로그 편집 모드 전용
  // ════════════════════════════════════════════════════════════════════════

  // ─────────────────────── 5-14h unified (서버 + dirty) ───────────────────────
  const unifiedNodes = computed<UnifiedNode[]>(() => {
    void dirtyVersion.value

    const result: UnifiedNode[] = []
    // 1) deleted 자손 cascading 표시
    const deletedIds = new Set<number>(dirtyChanges.deleted)
    if (deletedIds.size > 0) {
      const parentMap = new Map<number, number | null>()
      for (const n of flatNodes.value) parentMap.set(n.id, n.parentId)
      let added = true
      while (added) {
        added = false
        for (const [id, pid] of parentMap) {
          if (pid !== null && deletedIds.has(pid) && !deletedIds.has(id)) {
            deletedIds.add(id)
            added = true
          }
        }
      }
    }
    // 2) 기존 노드 (deleted 제외)
    for (const n of flatNodes.value) {
      if (deletedIds.has(n.id)) continue
      const upd = dirtyChanges.updated.get(n.id)
      const mov = dirtyChanges.moved.get(n.id)
      const errs = validationErrors.value.filter((d) => d.targetId === n.id)
      let dirty: UnifiedNode['_dirty'] = null
      if (upd) dirty = 'updated'
      if (mov) dirty = 'moved'
      result.push({
        _kind: 'existing',
        id: n.id,
        _key: `id-${n.id}`,
        parentId: mov ? mov.newParentId : n.parentId,
        parentTempId: null,
        nodeType: n.nodeType,
        code: upd?.code ?? n.code,
        name: upd?.name ?? n.name,
        description: upd?.description ?? n.description ?? undefined,
        displayOrder: mov ? mov.newDisplayOrder : n.displayOrder,
        depth: mov ? mov.newDepth : n.depth,
        evidenceTypeCount: n.evidenceTypeCount,
        collectedCount: n.collectedCount,
        pendingReviewCount: n.pendingReviewCount,
        _dirty: dirty,
        _validationErrors: errs,
        children: [],
        descendantLeafCount: 0,
      })
    }
    // 3) draft 노드들
    for (const draft of dirtyChanges.created.values()) {
      const parentIdNum = typeof draft.parentId === 'number' ? draft.parentId : null
      const parentTempId = typeof draft.parentId === 'string' ? draft.parentId : null
      const errs = validationErrors.value.filter((d) => d.targetTempId === draft.tempId)
      result.push({
        _kind: 'draft',
        id: -1,
        tempId: draft.tempId,
        _key: `temp-${draft.tempId}`,
        parentId: parentIdNum,
        parentTempId,
        nodeType: draft.nodeType,
        code: draft.code,
        name: draft.name,
        description: draft.description,
        displayOrder: draft.displayOrder,
        depth: draft.depth,
        evidenceTypeCount: draft.nodeType === 'control' ? 0 : undefined,
        collectedCount: draft.nodeType === 'control' ? 0 : undefined,
        pendingReviewCount: draft.nodeType === 'control' ? 0 : undefined,
        _dirty: 'created',
        _validationErrors: errs,
        children: [],
        descendantLeafCount: 0,
      })
    }
    return result
  })

  /**
   * 다이얼로그 트리 — children 빌드 + 자손 leaf 카운트 합.
   */
  const dialogRootNodes = computed<UnifiedNode[]>(() => {
    const all = unifiedNodes.value
    const byId = new Map<number, UnifiedNode>()
    const byTempId = new Map<string, UnifiedNode>()
    for (const n of all) {
      n.children = []
      n.descendantLeafCount = 0
      if (n._kind === 'existing') byId.set(n.id, n)
      if (n._kind === 'draft' && n.tempId) byTempId.set(n.tempId, n)
    }
    const roots: UnifiedNode[] = []
    for (const n of all) {
      let parent: UnifiedNode | undefined
      if (n.parentTempId) parent = byTempId.get(n.parentTempId)
      else if (n.parentId !== null) parent = byId.get(n.parentId)
      if (parent) parent.children.push(n)
      else roots.push(n)
    }
    const sortChildren = (n: UnifiedNode) => {
      n.children.sort((a, b) => {
        if (a.displayOrder !== b.displayOrder) return a.displayOrder - b.displayOrder
        if (a._kind !== b._kind) return a._kind === 'existing' ? -1 : 1
        return a.code.localeCompare(b.code, 'ko')
      })
      for (const c of n.children) sortChildren(c)
    }
    roots.sort((a, b) => a.displayOrder - b.displayOrder)
    for (const r of roots) sortChildren(r)
    // v15 Phase 5-15a (hybrid, spec §3.3.1.9):
    //   mutex 폐기. leaf 도 자식 보유 가능 → 자식 재귀를 nodeType 무관 항상 수행.
    //   자체 contribution 만 leaf/category 분기 (leaf=1, category=0).
    //   기존 5-14h 의 leaf early-return 제거.
    const computeLeaf = (n: UnifiedNode): number => {
      // 자체 contribution: leaf 면 1, category 면 0
      let s = n.nodeType === 'control' ? 1 : 0
      // 자식 재귀 + 합 (hybrid: leaf 도 자식 보유 가능)
      for (const c of n.children) s += computeLeaf(c)
      n.descendantLeafCount = s
      return s
    }
    for (const r of roots) computeLeaf(r)
    return roots
  })

  // ─────────────────────── 5-14h 다이얼로그 통계 ───────────────────────
  const dialogCategoryCount = computed(() => {
    let n = 0
    const visit = (node: UnifiedNode) => {
      if (node.nodeType === 'category') n++
      for (const c of node.children) visit(c)
    }
    for (const r of dialogRootNodes.value) visit(r)
    return n
  })

  const dialogControlCount = computed(() => {
    let n = 0
    const visit = (node: UnifiedNode) => {
      if (node.nodeType === 'control') n++
      for (const c of node.children) visit(c)
    }
    for (const r of dialogRootNodes.value) visit(r)
    return n
  })

  const dirtyCount = computed(() => {
    void dirtyVersion.value
    return (
      dirtyChanges.created.size +
      dirtyChanges.updated.size +
      dirtyChanges.moved.size +
      dirtyChanges.deleted.size
    )
  })

  const hasDirty = computed(() => dirtyCount.value > 0)

  // ─────────────────────── 5-14h 다이얼로그 검색 매치 + 자동 펼침 ───────────────────────
  const dialogMatchedAncestorIds = computed<Set<number>>(() => {
    const ids = new Set<number>()
    if (!dialogSearch.value) return ids
    const q = dialogSearch.value.toLowerCase()
    const visit = (node: UnifiedNode, ancestors: number[]) => {
      const newAncestors =
        node._kind === 'existing' ? [...ancestors, node.id] : ancestors
      const match =
        node.code.toLowerCase().includes(q) || node.name.toLowerCase().includes(q)
      if (match) for (const a of ancestors) ids.add(a)
      for (const c of node.children) visit(c, newAncestors)
    }
    for (const r of dialogRootNodes.value) visit(r, [])
    return ids
  })

  function dialogIsExpanded(node: UnifiedNode): boolean {
    if (node._kind === 'draft' && node.tempId) {
      return dialogExpandedTempIds.value.has(node.tempId)
    }
    if (dialogExpandedIds.value.has(node.id)) return true
    if (dialogMatchedAncestorIds.value.has(node.id)) return true
    return false
  }

  function dialogToggleExpand(node: UnifiedNode): void {
    if (node._kind === 'draft' && node.tempId) {
      const next = new Set(dialogExpandedTempIds.value)
      if (next.has(node.tempId)) next.delete(node.tempId)
      else next.add(node.tempId)
      dialogExpandedTempIds.value = next
      return
    }
    const next = new Set(dialogExpandedIds.value)
    if (next.has(node.id)) next.delete(node.id)
    else next.add(node.id)
    dialogExpandedIds.value = next
  }

  // ─────────────────────── 5-14h 코드 자동 추론 ───────────────────────
  function nextSiblingCode(parent: UnifiedNode | null): string {
    const siblings = parent ? parent.children : dialogRootNodes.value
    let maxNum = 0
    if (parent) {
      const prefix = `${parent.code}.`
      for (const s of siblings) {
        if (!s.code.startsWith(prefix)) continue
        const tail = s.code.slice(prefix.length).split('.')[0]
        const num = Number.parseInt(tail, 10)
        if (!Number.isNaN(num) && num > maxNum) maxNum = num
      }
      return `${prefix}${maxNum + 1}`
    }
    for (const s of siblings) {
      const head = s.code.split('.')[0]
      const num = Number.parseInt(head, 10)
      if (!Number.isNaN(num) && num > maxNum) maxNum = num
    }
    return `${maxNum + 1}`
  }

  function nextSiblingDisplayOrder(parent: UnifiedNode | null): number {
    const siblings = parent ? parent.children : dialogRootNodes.value
    let maxOrder = -1
    for (const s of siblings) {
      if (s.displayOrder > maxOrder) maxOrder = s.displayOrder
    }
    return maxOrder + 1
  }

  // ─────────────────────── 5-14h 액션 — 추가 (v15.1 hybrid) ───────────────
  function createChildControl(parent: UnifiedNode): DraftNode {
    // v15.1 5-15a 후속-2 — hybrid: leaf parent 도 허용 (parent.nodeType 가드 제거).
    // depth 가드는 보존 — createChildCategory 와 정합.
    if (parent.depth >= 10) {
      throw new Error('최대 깊이(10) 를 초과할 수 없습니다')
    }
    const tempId = nextTempId()
    const draft: DraftNode = {
      tempId,
      parentId: parent._kind === 'existing' ? parent.id : (parent.tempId ?? null),
      nodeType: 'control',
      code: nextSiblingCode(parent),
      name: '',
      displayOrder: nextSiblingDisplayOrder(parent),
      depth: parent.depth + 1,
    }
    dirtyChanges.created.set(tempId, draft)
    if (parent._kind === 'existing') {
      const next = new Set(dialogExpandedIds.value)
      next.add(parent.id)
      dialogExpandedIds.value = next
    }
    if (parent._kind === 'draft' && parent.tempId) {
      const next = new Set(dialogExpandedTempIds.value)
      next.add(parent.tempId)
      dialogExpandedTempIds.value = next
    }
    bumpDirty()
    return draft
  }

  function createChildCategory(parent: UnifiedNode): DraftNode {
    // v15.1 5-15a 후속-2 — hybrid: leaf parent 도 허용 (parent.nodeType 가드 제거).
    if (parent.depth >= 10) {
      throw new Error('최대 깊이(10) 를 초과할 수 없습니다')
    }
    const tempId = nextTempId()
    const draft: DraftNode = {
      tempId,
      parentId: parent._kind === 'existing' ? parent.id : (parent.tempId ?? null),
      nodeType: 'category',
      code: nextSiblingCode(parent),
      name: '',
      displayOrder: nextSiblingDisplayOrder(parent),
      depth: parent.depth + 1,
    }
    dirtyChanges.created.set(tempId, draft)
    if (parent._kind === 'existing') {
      const next = new Set(dialogExpandedIds.value)
      next.add(parent.id)
      dialogExpandedIds.value = next
    }
    if (parent._kind === 'draft' && parent.tempId) {
      const next = new Set(dialogExpandedTempIds.value)
      next.add(parent.tempId)
      dialogExpandedTempIds.value = next
    }
    bumpDirty()
    return draft
  }

  function createSiblingControl(sibling: UnifiedNode): DraftNode | null {
    let parent: UnifiedNode | null = null
    if (sibling.parentId !== null) parent = findById(sibling.parentId)
    else if (sibling.parentTempId) parent = findByTempId(sibling.parentTempId)
    if (!parent) return null
    return createChildControl(parent)
  }

  function createRootCategory(): DraftNode {
    const tempId = nextTempId()
    const draft: DraftNode = {
      tempId,
      parentId: null,
      nodeType: 'category',
      code: nextSiblingCode(null),
      name: '',
      displayOrder: nextSiblingDisplayOrder(null),
      depth: 1,
    }
    dirtyChanges.created.set(tempId, draft)
    bumpDirty()
    return draft
  }

  // ─────────────────────── 5-14h 액션 — 편집 ───────────────────────
  function setCode(node: UnifiedNode, code: string): void {
    if (node._kind === 'draft' && node.tempId) {
      const d = dirtyChanges.created.get(node.tempId)
      if (d) {
        d.code = code
        bumpDirty()
      }
      return
    }
    const original = flatNodes.value.find((n) => n.id === node.id)
    if (!original) return
    if (original.code === code) {
      const u = dirtyChanges.updated.get(node.id)
      if (u) {
        delete u.code
        if (u.code === undefined && u.name === undefined && u.description === undefined) {
          dirtyChanges.updated.delete(node.id)
        }
      }
    } else {
      const u = dirtyChanges.updated.get(node.id) ?? { id: node.id }
      u.code = code
      dirtyChanges.updated.set(node.id, u)
    }
    bumpDirty()
  }

  function setName(node: UnifiedNode, name: string): void {
    if (node._kind === 'draft' && node.tempId) {
      const d = dirtyChanges.created.get(node.tempId)
      if (d) {
        d.name = name
        bumpDirty()
      }
      return
    }
    const original = flatNodes.value.find((n) => n.id === node.id)
    if (!original) return
    if (original.name === name) {
      const u = dirtyChanges.updated.get(node.id)
      if (u) {
        delete u.name
        if (u.code === undefined && u.name === undefined && u.description === undefined) {
          dirtyChanges.updated.delete(node.id)
        }
      }
    } else {
      const u = dirtyChanges.updated.get(node.id) ?? { id: node.id }
      u.name = name
      dirtyChanges.updated.set(node.id, u)
    }
    bumpDirty()
  }

  // ─────────────────────── 5-14h 액션 — 삭제 ───────────────────────
  function countDescendants(node: UnifiedNode): {
    total: number
    categories: number
    controls: number
  } {
    let cats = 0
    let ctrls = 0
    const visit = (n: UnifiedNode) => {
      for (const c of n.children) {
        if (c.nodeType === 'category') cats++
        else ctrls++
        visit(c)
      }
    }
    visit(node)
    return { total: cats + ctrls, categories: cats, controls: ctrls }
  }

  function deleteNode(node: UnifiedNode): void {
    if (node._kind === 'draft' && node.tempId) {
      const remove = new Set<string>([node.tempId])
      let added = true
      while (added) {
        added = false
        for (const d of dirtyChanges.created.values()) {
          if (
            typeof d.parentId === 'string' &&
            remove.has(d.parentId) &&
            !remove.has(d.tempId)
          ) {
            remove.add(d.tempId)
            added = true
          }
        }
      }
      for (const t of remove) dirtyChanges.created.delete(t)
      bumpDirty()
      return
    }
    dirtyChanges.deleted.add(node.id)
    dirtyChanges.updated.delete(node.id)
    dirtyChanges.moved.delete(node.id)
    bumpDirty()
  }

  // ─────────────────────── 5-14h 이동 ───────────────────────
  function getMaxDescendantDepth(node: UnifiedNode): number {
    let max = 0
    const visit = (n: UnifiedNode, d: number) => {
      if (d > max) max = d
      for (const c of n.children) visit(c, d + 1)
    }
    visit(node, 0)
    return max
  }

  function getMoveTargets(node: UnifiedNode): UnifiedNode[] {
    if (node._kind === 'draft') return []
    const forbidden = new Set<number>([node.id])
    const collectDesc = (n: UnifiedNode) => {
      for (const c of n.children) {
        if (c._kind === 'existing') forbidden.add(c.id)
        collectDesc(c)
      }
    }
    collectDesc(node)
    const targets: UnifiedNode[] = []
    const visit = (n: UnifiedNode) => {
      // v15.1 5-15a 후속-2 — hybrid: leaf 도 target 허용 (n.nodeType==='category' 가드 제거).
      if (
        n._kind === 'existing' &&
        !forbidden.has(n.id) &&
        n.depth + 1 + getMaxDescendantDepth(node) <= 10
      ) {
        targets.push(n)
      }
      for (const c of n.children) visit(c)
    }
    for (const r of dialogRootNodes.value) visit(r)
    return targets
  }

  function moveNode(node: UnifiedNode, newParent: UnifiedNode): void {
    if (node._kind !== 'existing' || newParent._kind !== 'existing') return
    // v15.1 5-15a 후속-2 — hybrid: leaf 도 newParent 허용 (newParent.nodeType 가드 제거).
    const newDisplayOrder = nextSiblingDisplayOrder(newParent)
    const newDepth = newParent.depth + 1
    if (newDepth + getMaxDescendantDepth(node) > 10) {
      throw new Error('최대 깊이(10) 를 초과할 수 없습니다')
    }
    dirtyChanges.moved.set(node.id, {
      id: node.id,
      newParentId: newParent.id,
      newDisplayOrder,
      newDepth,
    })
    bumpDirty()
  }

  // ─────────────────────── 5-14h Tab → v15.1 의미 단순화 ───────────────
  /**
   * v15.1 5-15a 후속-2 — hybrid 모델 후 변환 개념 무의미.
   * BC: 시그니처 보존, "자식 통제 draft 추가" 로 의미 단순화.
   * 호출처 (`dialogHandleTabKey`) 는 직접 `createChildControl` 호출 권장.
   *
   * @deprecated v15.1 — 본 함수는 호환 layer. 신규 코드는 `createChildControl` 사용.
   */
  function convertNodeType(node: UnifiedNode): ConvertResult {
    if (node.depth >= 10) {
      return { ok: false, reason: 'depth_exceeded' }
    }
    try {
      const childDraft = createChildControl(node)
      return { ok: true, childDraft }
    } catch (_err) {
      return { ok: false, reason: 'depth_exceeded' }
    }
  }

  // ─────────────────────── 5-14h 코드 변경 영향 ───────────────────────
  async function fetchImpactSummary(controlId: number): Promise<ImpactSummary> {
    const response = await treeApi.getImpactSummary(controlId)
    const body = response.data
    if (!body.success) {
      throw new Error(body.message ?? 'impact-summary 호출 실패')
    }
    return body.data
  }

  // ─────────────────────── 5-14h 저장 ───────────────────────
  function buildPatchPayload(): TreePatchPayload {
    if (!framework.value) {
      throw new Error('Framework 가 로드되지 않았습니다')
    }
    return {
      expectedVersion: framework.value.version,
      changes: {
        nodes: {
          created: Array.from(dirtyChanges.created.values()).map((d) => ({
            tempId: d.tempId,
            parentId: d.parentId,
            nodeType: d.nodeType,
            code: d.code,
            name: d.name,
            description: d.description,
            displayOrder: d.displayOrder,
            depth: d.depth,
          })),
          updated: Array.from(dirtyChanges.updated.values()).map((u) => {
            const o: { id: number; code?: string; name?: string; description?: string } = {
              id: u.id,
            }
            if (u.code !== undefined) o.code = u.code
            if (u.name !== undefined) o.name = u.name
            if (u.description !== undefined) o.description = u.description
            return o
          }),
          moved: Array.from(dirtyChanges.moved.values()),
          deleted: Array.from(dirtyChanges.deleted).map((id) => ({ id })),
        },
      },
    }
  }

  async function saveTree(): Promise<SaveResult> {
    if (!framework.value) {
      return { ok: false, kind: 'error', message: 'Framework 가 로드되지 않았습니다' }
    }
    if (dirtyCount.value === 0) {
      return {
        ok: true,
        newVersion: framework.value.version,
        mappings: new Map(),
      }
    }
    isSaving.value = true
    validationErrors.value = []
    try {
      const payload = buildPatchPayload()
      const response = await treeApi.patchTree(framework.value.id, payload)
      const body = response.data
      if (!body.success) {
        return {
          ok: false,
          kind: 'error',
          message: body.message ?? '저장에 실패했습니다',
        }
      }
      const mappings = new Map<string, number>()
      for (const m of body.data.mappings.nodes) mappings.set(m.tempId, m.id)
      // draft 펼침 → realId 펼침으로 옮기기
      const draftExp = new Set(dialogExpandedTempIds.value)
      const newExp = new Set(dialogExpandedIds.value)
      for (const [tempId, realId] of mappings) {
        if (draftExp.has(tempId)) newExp.add(realId)
      }
      const newVersion = body.data.version
      // dirty 제거 + reload (서버 정합)
      dirtyChanges.created.clear()
      dirtyChanges.updated.clear()
      dirtyChanges.moved.clear()
      dirtyChanges.deleted.clear()
      validationErrors.value = []
      bumpDirty()
      await reload()
      // version 직접 갱신 (reload 가 성공하면 framework.version 동일하지만 안전)
      if (framework.value) framework.value.version = newVersion
      dialogExpandedIds.value = newExp
      dialogExpandedTempIds.value = new Set()
      return { ok: true, newVersion, mappings }
    } catch (err) {
      const ax = err as AxiosErrorLike<TreePatchErrorResponse>
      if (ax?.response?.status === 409) {
        const data = ax.response.data
        return {
          ok: false,
          kind: 'conflict',
          currentVersion: data?.currentVersion ?? framework.value.version,
        }
      }
      if (ax?.response?.status === 422) {
        const data = ax.response.data
        const details = data?.details ?? []
        validationErrors.value = details
        return { ok: false, kind: 'validation', details }
      }
      return {
        ok: false,
        kind: 'error',
        message: (err as Error).message ?? '저장에 실패했습니다',
      }
    } finally {
      isSaving.value = false
    }
  }

  function discardAllDirty(): void {
    dirtyChanges.created.clear()
    dirtyChanges.updated.clear()
    dirtyChanges.moved.clear()
    dirtyChanges.deleted.clear()
    validationErrors.value = []
    dialogExpandedTempIds.value = new Set()
    bumpDirty()
  }

  // ─────────────────────── 5-14h 헬퍼 ───────────────────────
  function findById(id: number): UnifiedNode | null {
    const visit = (nodes: UnifiedNode[]): UnifiedNode | null => {
      for (const n of nodes) {
        if (n._kind === 'existing' && n.id === id) return n
        const found = visit(n.children)
        if (found) return found
      }
      return null
    }
    return visit(dialogRootNodes.value)
  }

  function findByTempId(tempId: string): UnifiedNode | null {
    const visit = (nodes: UnifiedNode[]): UnifiedNode | null => {
      for (const n of nodes) {
        if (n._kind === 'draft' && n.tempId === tempId) return n
        const found = visit(n.children)
        if (found) return found
      }
      return null
    }
    return visit(dialogRootNodes.value)
  }

  function findByKey(key: string): UnifiedNode | null {
    const visit = (nodes: UnifiedNode[]): UnifiedNode | null => {
      for (const n of nodes) {
        if (n._key === key) return n
        const found = visit(n.children)
        if (found) return found
      }
      return null
    }
    return visit(dialogRootNodes.value)
  }

  // ─────────────────────── return ───────────────────────
  return {
    // 5-14g 인터페이스 (모두 보존)
    framework,
    flatNodes,
    rootNodes,
    loading,
    error,
    searchText,
    statusFilter,
    expandedIds,
    filterActive,
    effectiveExpandedIds,
    totalLeafCount,
    totalEvidenceTypeCount,
    statusCounts,
    isMatched,
    toggleExpand,
    load,
    reload,
    leafStatusOf,

    // 5-14h 추가
    dialogSearch,
    dialogExpandedIds,
    dialogExpandedTempIds,
    dirtyChanges,
    dirtyVersion,
    validationErrors,
    isSaving,
    unifiedNodes,
    dialogRootNodes,
    dialogCategoryCount,
    dialogControlCount,
    dirtyCount,
    hasDirty,
    dialogIsExpanded,
    dialogToggleExpand,
    nextSiblingCode,
    createChildControl,
    createChildCategory,
    createSiblingControl,
    createRootCategory,
    setCode,
    setName,
    deleteNode,
    countDescendants,
    getMoveTargets,
    moveNode,
    convertNodeType,
    discardAllDirty,
    fetchImpactSummary,
    saveTree,
    findById,
    findByTempId,
    findByKey,

    // 5-14i 추가 — 페이지 본문 검색 인터랙션 (P12, P14, P19, P21)
    debouncedSearchText,
    setSearchText,
    matchCountByCategoryId,
    highlightMatch,
    focusFirstMatchInContainer,
    visibleLeafCount,
    onSearchApplied,
  }
}

export type ControlTreeApi = ReturnType<typeof useControlTree>

/**
 * provide / inject 키 — UnifiedControlsDialog 가 provide(), ControlNodeRow 가 inject().
 *
 * SFC 의 `<script setup>` 안에서는 일반 `export` 가 허용되지 않아 본 모듈에서 export.
 * 양쪽 SFC 가 모두 본 파일에서 import.
 */
export const CONTROL_TREE_INJECTION_KEY = Symbol('controlTree') as InjectionKey<ControlTreeApi>

export type { ComputedRef, Ref }