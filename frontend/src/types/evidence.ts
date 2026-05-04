// ========================================
// 프레임워크
// ========================================

/**
 * Framework 상태 (v11 Phase 5-1)
 */
export type FrameworkStatus = 'active' | 'archived'

export interface Framework {
  id: number
  name: string
  description?: string
  createdAt: string
  controlCount: number

  // v11 Phase 5-3
  status?: FrameworkStatus
  parentFrameworkId?: number
  parentFrameworkName?: string
  evidenceTypeCount?: number
  jobCount?: number
  pendingReviewCount?: number
}

export interface FrameworkCreatePayload {
  name: string
  description?: string
}

/**
 * v11 Phase 5-6 — Framework 상속 요청 페이로드.
 *
 * 원본 Framework 의 통제 항목 / 증빙 유형(담당자·마감일 포함) / 수집 작업을
 * 스냅샷 복제하고 parent_framework_id 에 원본을 기록한다.
 * 파일과 실행 이력은 복제되지 않는다.
 */
export interface FrameworkInheritPayload {
  sourceFrameworkId: number
  name: string
  description?: string
}

// ========================================
// 통제항목
// ========================================
export interface ControlItem {
  id: number
  frameworkId: number
  code: string
  domain?: string
  name: string
  description?: string
  evidenceTotal: number
  evidenceCollected: number
  status: string
  createdAt: string

  // v11 Phase 5-9
  pendingReviewCount?: number
}

/**
 * v14 Phase 5-14f — leaf 의 ancestor 요약 (spec §8.2).
 * EvidenceTypeDetailView 헤더 서브텍스트 (`detail-context`) 의 N단 경로 표시용.
 * depth=1 부터 leaf 의 직계 부모까지 순서대로. leaf 자기 자신은 미포함.
 */
export interface AncestorSummary {
  id: number
  code: string
  name: string
}

export interface ControlDetail extends ControlItem {
  evidenceTypes: EvidenceTypeResponse[]

  /**
   * v14 Phase 5-14f 신규 (spec §8.2).
   * 빈 배열 (depth=1 leaf 인 경우) 가능 — null 아님.
   */
  ancestors?: AncestorSummary[]
}

export interface ControlCreatePayload {
  code: string
  domain?: string
  name: string
  description?: string
  evidenceTypes?: EvidenceTypePayload[]
}

export interface ControlUpdatePayload {
  code?: string
  domain?: string
  name?: string
  description?: string
}

// ========================================
// 증빙 유형
// ========================================
export interface EvidenceTypePayload {
  name: string
  description?: string
}

export interface EvidenceTypeResponse {
  id: number
  name: string
  description?: string
  collected: boolean
  files: EvidenceFileItem[]

  // v11 Phase 5-12 — 담당자/마감일 정보 (백엔드 ControlDto.EvidenceTypeResponse 보강 시 자동 표시)
  ownerUserId?: number
  ownerUserName?: string
  ownerUserTeam?: string
  dueDate?: string
}

// ========================================
// 증빙 파일
// ========================================

export type ReviewStatus = 'pending' | 'approved' | 'rejected' | 'auto_approved'

export interface EvidenceFileItem {
  id: number
  evidenceTypeId: number
  evidenceTypeName: string
  controlCode: string
  controlName: string
  fileName: string
  filePath: string
  fileSize: number
  version: number
  collectionMethod: 'auto' | 'manual'
  collectedAt: string
  createdAt: string

  uploadedById?: number
  uploadedByName?: string
  submitNote?: string

  reviewStatus?: ReviewStatus
  reviewedById?: number
  reviewedByName?: string
  reviewNote?: string
  reviewedAt?: string
}

export interface EvidenceFileStats {
  totalFiles: number
  quarterFiles: number
  totalSizeBytes: number
  controlCoverage: number
}

export interface ApproveRequest {
  reviewNote?: string
}

export interface RejectRequest {
  reviewNote: string
}

// ========================================
// 수집 작업
// ========================================
export interface CollectionJobItem {
  id: number
  name: string
  description?: string
  jobType: string
  scriptPath?: string
  evidenceTypeId?: number
  evidenceTypeName?: string
  scheduleCron?: string
  isActive: boolean
  lastExecution?: ExecutionSummary
  createdAt: string
}

export interface CollectionJobDetail extends Omit<CollectionJobItem, 'lastExecution'> {
  executions: ExecutionSummary[]
}

export interface CollectionJobCreatePayload {
  name: string
  description?: string
  jobType: string
  scriptPath?: string
  evidenceTypeId?: number
  scheduleCron?: string
}

export interface CollectionJobUpdatePayload {
  name?: string
  description?: string
  scriptPath?: string
  scheduleCron?: string
}

export interface ExecutionSummary {
  id: number
  status: 'running' | 'success' | 'failed'
  startedAt?: string
  finishedAt?: string
  errorMessage?: string
  createdAt?: string
}

// ========================================
// 엑셀 Import
// ========================================
export interface ExcelImportResult {
  totalRows: number
  successCount: number
  failCount: number
  errors: string[]
}

// ========================================
// v14 Phase 5-14g — 통제 트리 (control_nodes 자기참조)
//
// spec §3.3.1.4 의 응답 페이로드 정합. nodes 는 평탄화된 배열, parentId 로 클라이언트
// 트리 reconstruction. 정렬 = depth ASC, parent.id ASC NULL FIRST, displayOrder ASC.
// ========================================

export type NodeType = 'category' | 'control'

export interface TreeFrameworkSummary {
  id: number
  name: string
  /** Optimistic lock — 5-14d PATCH /tree 의 expectedVersion 으로 짝맞춤. */
  version: number
}

/**
 * 평탄 노드 표현. category / leaf 가 같은 shape, 카운트 필드 (3개) 노출 여부로 구분.
 *
 * - leaf (`nodeType === 'control'`): evidenceTypeCount / collectedCount /
 *   pendingReviewCount 모두 채워짐 (5-14g β).
 * - category (`nodeType === 'category'`): 세 필드 모두 omit (서버가 NON_NULL 으로
 *   직렬화 안 함). 클라이언트 트리 빌드 후 자손 leaf 합으로 derive.
 */
export interface TreeNode {
  id: number
  parentId: number | null
  nodeType: NodeType
  code: string
  name: string
  description?: string | null
  displayOrder: number
  depth: number

  // leaf only — category 는 응답에 없음
  evidenceTypeCount?: number
  collectedCount?: number       // v14.7 (5-14g β) 신규 — 진행바 + 상태 derive 용
  pendingReviewCount?: number
}

export interface TreeResponse {
  framework: TreeFrameworkSummary
  nodes: TreeNode[]
}

// ========================================
// v14 Phase 5-14d — PATCH /tree 페이로드 (5-14h 사용 예정, 5-14g 는 타입 정의만)
//
// spec §3.3.1.4 의 12 검증 규칙 하에 nodes.{created,updated,moved,deleted} 4 액션을
// 단일 트랜잭션으로 처리. parentId 다형 (number | string | null).
// ========================================

export interface TreeNodeCreatePayload {
  /** 같은 PATCH 내 신규 노드 사이 의존성 표현용 임시 식별자. */
  tempId: string
  /** 기존 노드 id (number) | 같은 요청 tempId (string) | framework 직속 (null). */
  parentId: number | string | null
  nodeType: NodeType
  code: string
  name: string
  description?: string
  displayOrder: number
  depth: number
}

export interface TreeNodeUpdatePayload {
  id: number
  code?: string
  name?: string
  description?: string
  displayOrder?: number
}

export interface TreeNodeMovePayload {
  id: number
  newParentId: number | null
  newDisplayOrder: number
  newDepth: number
}

export interface TreeNodeDeletePayload {
  id: number
}

export interface TreePatchPayload {
  expectedVersion: number
  changes: {
    nodes: {
      created?: TreeNodeCreatePayload[]
      updated?: TreeNodeUpdatePayload[]
      moved?: TreeNodeMovePayload[]
      deleted?: TreeNodeDeletePayload[]
    }
  }
}

export interface TreePatchSuccessResponse {
  version: number
  mappings: {
    nodes: Array<{ tempId: string; id: number }>
  }
}

/**
 * 422 검증 실패 details 항목 (5-14d, 5-14h 명명 export).
 *
 * 5-14h 의 useControlTree.validationErrors[] 와 ControlNodeRow.props.node._validationErrors
 * 가 본 타입을 참조한다. 5-14g 까지는 TreePatchErrorResponse 안 인라인 타입이었으나
 * 5-14h 에서 행 단위 표시 위해 명명 export 로 분리.
 */
export interface TreeValidationDetail {
  target: string
  targetId?: number
  targetTempId?: string
  field: string
  code: string
  message: string
}

/** 422 검증 실패 응답 (5-14d TreeUpdateErrorResponse). */
export interface TreePatchErrorResponse {
  success: false
  error: 'validation_failed' | 'version_mismatch'
  /** 409 (version_mismatch) 시에만 — 5-14d Q1=B 로 lastEditedBy/At 은 현재 omit. */
  currentVersion?: number
  /** 422 (validation_failed) 시에만. */
  details?: TreeValidationDetail[]
  message?: string
}

// ========================================
// v14 Phase 5-14e — 통제 코드 변경 영향 카운트
//
// 5-14h 의 인라인 코드 편집 시 사전 호출하여 합산 > 0 이면 경고 다이얼로그.
//
// v15 Phase 5-15a — Hybrid 분리 카운트 (own + descendant 6 필드)
// v15 Phase 5-15c (v15.7) — legacy alias 3 필드 (evidenceFileCount / jobCount /
//                           reviewCount) BE 측 일괄 제거 (Q2=A). FE type 도 정합.
//                           호출 측 (5-14h FE) 은 own + descendant 합으로 임계값 판정.
// ========================================

export interface ImpactSummary {
  ownEvidenceFileCount: number
  ownJobCount: number
  ownReviewCount: number
  descendantEvidenceFileCount: number
  descendantJobCount: number
  descendantReviewCount: number
}

// ========================================
// v11 Phase 5-5 — 담당자 "내 할 일"
//
// v15 Phase 5-15c (v15.7) — controlId / controlCode / controlName →
//                           nodeId / nodeCode / nodeName (Q4 + Q7-narrow).
//                           BE MyTasksDto.Item / DetailResponse 정합. 외부 통합 0 가정.
// ========================================

export interface MyTaskItem {
  evidenceTypeId: number
  evidenceTypeName: string

  // v15.7: controlId/Code/Name → nodeId/Code/Name (Q4 + Q7-narrow)
  nodeId?: number
  nodeCode?: string
  nodeName?: string
  frameworkId?: number
  frameworkName?: string

  dueDate?: string
  daysUntilDue?: number

  latestFileId?: number
  latestFileName?: string
  latestVersion?: number
  latestReviewStatus?: ReviewStatus
  submittedAt?: string
  reviewedAt?: string

  rejectReason?: string
  rejectedByName?: string

  approvedByName?: string
}

export interface MyTasksCounts {
  rejected: number
  dueSoon: number
  notSubmitted: number
  inReview: number
  completed: number
}

export interface MyTasksResponse {
  rejected: MyTaskItem[]
  dueSoon: MyTaskItem[]
  notSubmitted: MyTaskItem[]
  inReview: MyTaskItem[]
  completed: MyTaskItem[]
  counts: MyTasksCounts
}

export type MyTaskSectionKey = 'rejected' | 'dueSoon' | 'notSubmitted' | 'inReview' | 'completed'

export interface MyTaskDetail {
  evidenceTypeId: number
  evidenceTypeName: string
  description?: string

  // v15.7: controlId/Code/Name → nodeId/Code/Name (Item 정합)
  nodeId?: number
  nodeCode?: string
  nodeName?: string
  frameworkId?: number
  frameworkName?: string

  dueDate?: string
  daysUntilDue?: number

  currentStatus: string

  rejectReason?: string
  rejectedByName?: string
  rejectedAt?: string

  history: MyTaskFileHistoryEntry[]
}

export interface MyTaskFileHistoryEntry {
  fileId: number
  fileName: string
  fileSize: number
  version: number
  collectionMethod: 'auto' | 'manual'
  collectedAt: string
  uploadedByName?: string
  submitNote?: string
  reviewStatus?: ReviewStatus
  reviewedByName?: string
  reviewNote?: string
  reviewedAt?: string
}