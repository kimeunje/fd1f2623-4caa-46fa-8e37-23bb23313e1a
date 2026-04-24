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

export interface ControlDetail extends ControlItem {
  evidenceTypes: EvidenceTypeResponse[]
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
// v11 Phase 5-5 — 담당자 "내 할 일"
// ========================================

export interface MyTaskItem {
  evidenceTypeId: number
  evidenceTypeName: string

  controlId?: number
  controlCode?: string
  controlName?: string
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

  controlId?: number
  controlCode?: string
  controlName?: string
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