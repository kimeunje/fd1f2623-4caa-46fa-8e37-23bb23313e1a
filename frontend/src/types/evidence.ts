// ========================================
// 프레임워크
// ========================================
export interface Framework {
  id: number
  name: string
  description?: string
  controlCount: number
  createdAt: string
}

export interface FrameworkCreatePayload {
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
  status: string // 완료/진행중/미수집
  createdAt: string
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

/**
 * 증빙 파일 검토 상태 (v11 Phase 5-4)
 * - pending       담당자 업로드 후 관리자 검토 대기
 * - approved      관리자 승인 완료
 * - rejected      관리자 반려 (review_note 필수)
 * - auto_approved 관리자 직접 업로드 또는 자동수집 결과 (검토 생략)
 */
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

  // v11 Phase 5-4: 업로더 정보
  uploadedById?: number
  uploadedByName?: string
  submitNote?: string

  // v11 Phase 5-4: 검토 상태
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

/**
 * 승인 요청 페이로드 (v11 Phase 5-4)
 * reviewNote 는 optional.
 */
export interface ApproveRequest {
  reviewNote?: string
}

/**
 * 반려 요청 페이로드 (v11 Phase 5-4)
 * reviewNote 필수. 빈 값 보내면 백엔드가 400 응답.
 */
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
