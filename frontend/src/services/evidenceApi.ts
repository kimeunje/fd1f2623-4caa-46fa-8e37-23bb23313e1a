import api from './api'
import type { ApiResponse, PageResponse, ControlNodeNote, ControlNodeNotePayload } from '@/types'
import type {
  Framework,
  FrameworkCreatePayload,
  FrameworkInheritPayload,
  ControlDetail,
  EvidenceTypeResponse,
  EvidenceFileItem,
  EvidenceFileStats,
  ApproveRequest,
  RejectRequest,
  CollectionJobItem,
  CollectionJobDetail,
  CollectionJobCreatePayload,
  CollectionJobUpdatePayload,
  ExecutionSummary,
  MyTasksResponse,
  MyTaskDetail,
  // v14 Phase 5-14g — 통제 트리
  TreeResponse,
  TreePatchPayload,
  TreePatchSuccessResponse,
  ImpactSummary,
  UploadResponse,   // ← v18.6a
  LinkRequest,      // ← v18.6a
  DiagnosisJson,
} from '@/types/evidence'

/**
 * v14 Phase 5-14h — axios 에러를 useControlTree.saveTree() 등에서 try/catch 로
 * 받을 때의 최소 타입. axios 직접 의존성을 composable 에서 분리하기 위함.
 *
 * 실 axios.AxiosError<T> 와 호환되며, status / data 만 사용한다.
 */
export interface AxiosErrorLike<T = unknown> {
  response?: {
    status: number
    data: T
  }
  message?: string
}

// ========================================
// Frameworks API
// ========================================
export const frameworksApi = {
  list() {
    return api.get<ApiResponse<Framework[]>>('/frameworks')
  },
  get(id: number) {
    return api.get<ApiResponse<Framework>>(`/frameworks/${id}`)
  },
  create(data: FrameworkCreatePayload) {
    return api.post<ApiResponse<Framework>>('/frameworks', data)
  },

  /**
   * v11 Phase 5-6 — Framework 상속 생성
   *
   * 원본 Framework 의 통제/증빙 유형(담당자·마감일 포함)/수집 작업 구조를
   * 스냅샷 복제해 신규 Framework 를 만든다. 파일과 실행 이력은 복제되지 않으며,
   * 상속 이후 원본과 독립적으로 관리된다.
   */
  inherit(payload: FrameworkInheritPayload) {
    return api.post<ApiResponse<Framework>>('/frameworks/inherit', payload)
  },

  update(id: number, data: Partial<FrameworkCreatePayload>) {
    return api.put<ApiResponse<Framework>>(`/frameworks/${id}`, data)
  },

  /**
   * v19.22 — 프레임워크 보관(soft delete). 2단계 삭제의 1단계.
   *
   * status 를 archived 로 전환(트리·작업·증빙은 그대로 보존 — 이력 조회·상속 원본으로
   * 계속 사용 가능). 영구 삭제는 보관 후 delete() 로 진행한다.
   * PATCH /api/v1/frameworks/{id}/archive
   */
  archive(id: number) {
    return api.patch<ApiResponse<Framework>>(`/frameworks/${id}/archive`)
  },

  /**
   * v19.22 — 프레임워크 영구 삭제(하드). 2단계 삭제의 2단계.
   *
   * 보관(archived)된 프레임워크만 허용 — active 상태로 호출하면 BE 가 409 로 거부한다.
   * 삭제 시 DB의 ON DELETE CASCADE 로 트리·작업·증빙이 함께 영구 삭제된다(되돌릴 수 없음).
   * DELETE /api/v1/frameworks/{id}
   */
  delete(id: number) {
    return api.delete(`/frameworks/${id}`)
  },
}

// ========================================
// v15 Phase 5-15b Round 3 (v15.6) — Control Nodes API
// v15 Phase 5-15c (v15.7) — wire shape 코멘트 회수만 (downloadZip 위, evidenceFilesApi 안)
//
// v15.3 폐기된 controlsApi (3 메서드: listByFramework / getDetail / deleteEvidenceType)
// 의 후속. 옛 controlsApi 의 처리:
//   - listByFramework  → 제거 (사용처 0, endpoint 폐기 dead code)
//   - getDetail        → 본 객체의 getDetail 로 이전 (새 path /control-nodes/{id})
//   - deleteEvidenceType → evidenceTypesApi.delete 로 분리 (namespace 일치 — 옵션 b)
//
// 본 객체의 getImpactSummary 는 옛 treeApi.getImpactSummary 의 이전 (path namespace
// 일치 — Q2=B). 옛 path /controls/{id}/impact-summary 는 v15.6 에서 일괄 폐기 (Q1=A).
// ========================================
export const controlNodesApi = {
  /**
   * v15.6 신규 — leaf control_node 상세 조회.
   *
   * <p>v15.3 폐기된 옛 GET /api/v1/controls/{id} (controlsApi.getDetail) 의 응답
   * shape 보존. ControlsView 의 leaf 클릭 인라인 펼침에서 사용. v15.5.1 의
   * 워크어라운드 catch 가 본 endpoint 정상화로 회수됨.</p>
   */
  getDetail(id: number) {
    return api.get<ApiResponse<ControlDetail>>(`/control-nodes/${id}`)
  },

  /**
   * v15.6 신규 — leaf 의 evidence-types 만 분리 응답.
   *
   * <p>evidence 운영 화면에서 detail 전체 재로드 회피용.</p>
   */
  getEvidenceTypes(id: number) {
    return api.get<ApiResponse<EvidenceTypeResponse[]>>(`/control-nodes/${id}/evidence-types`)
  },

  /**
   * v14.5 도입 / v15.6 URL 이전 — leaf 코드 변경 사전 경고용 카운트 조회.
   *
   * <p>옛 path: /controls/{id}/impact-summary (v14.5 ~ v15.5).
   * 새 path: /control-nodes/{id}/impact-summary (v15.6 ~).
   * BC layer 0, deprecation 0 — Q1=A 결정 정합.</p>
   */
  getImpactSummary(id: number) {
    return api.get<ApiResponse<ImpactSummary>>(`/control-nodes/${id}/impact-summary`)
  },

  // ────────────────────────────────────────────────────────────────────
  // v19.27 — 관리 항목 인수인계 노트 (누적 로그). 관리자 전용.
  //   경로는 getDetail 과 동일 계열(/control-nodes/{id}/...).
  // ────────────────────────────────────────────────────────────────────
  listNotes(nodeId: number) {
    return api.get<ApiResponse<ControlNodeNote[]>>(`/control-nodes/${nodeId}/notes`)
  },
  createNote(nodeId: number, payload: ControlNodeNotePayload) {
    return api.post<ApiResponse<ControlNodeNote>>(`/control-nodes/${nodeId}/notes`, payload)
  },
  updateNote(nodeId: number, noteId: number, payload: ControlNodeNotePayload) {
    return api.patch<ApiResponse<ControlNodeNote>>(
      `/control-nodes/${nodeId}/notes/${noteId}`,
      payload,
    )
  },
  deleteNote(nodeId: number, noteId: number) {
    return api.delete(`/control-nodes/${nodeId}/notes/${noteId}`)
  },
}

// ========================================
// v15 Phase 5-15b Round 3 (v15.6) — Evidence Types API
//
// 옛 controlsApi.deleteEvidenceType 의 이전. 옵션 §1.7-b (FE 추상화 = path 일치).
// 후속 phase 에서 evidence-types CRUD 추가 시 본 객체에 자연 확장.
// ========================================
export const evidenceTypesApi = {
  /** v14 Phase 5-14f — 증빙 유형 단건 삭제. v15.6 namespace 정리. */
  delete(evidenceTypeId: number) {
    return api.delete(`/evidence-types/${evidenceTypeId}`)
  },

  /** v18 — 증빙 유형 생성 (특정 통제 노드에 추가). */
  create(nodeId: number, name: string, description?: string) {
    return api.post('/evidence-types', { nodeId, name, description })
  },

  /** v18 — 증빙 유형 수정 (이름/설명/담당자/마감일). */
  update(evidenceTypeId: number, payload: {
    name?: string
    description?: string
    ownerUserId?: number | null
    dueDate?: string | null
  }) {
    return api.put(`/evidence-types/${evidenceTypeId}`, payload)
  },
}

// ========================================
// v14 Phase 5-14g — 통제 트리 API (control_nodes 자기참조)
//
// v15.6: getImpactSummary 가 controlNodesApi.getImpactSummary 로 이전됨 (Q2=B).
// 본 객체는 framework 단위 트리 read/write/export 만 담당.
// ========================================
export const treeApi = {
  /**
   * GET /api/v1/frameworks/{id}/tree (5-14c).
   *
   * <p>Framework + 평탄 nodes[] + version 반환. nodes 는 (depth, parent.id NULL FIRST,
   * displayOrder) 정렬되어 부모가 자식보다 먼저 등장 — 클라이언트는 단일 패스로 트리
   * reconstruct 가능. leaf 는 evidenceTypeCount / collectedCount / pendingReviewCount
   * 세 카운트 모두 노출 (5-14g β).</p>
   */
  getTree(frameworkId: number) {
    return api.get<ApiResponse<TreeResponse>>(`/frameworks/${frameworkId}/tree`)
  },

  /**
   * PATCH /api/v1/frameworks/{id}/tree (5-14d).
   *
   * <p>nodes.{created,updated,moved,deleted} 단일 트랜잭션, Optimistic lock, tempId
   * 매핑. 12 검증 규칙. 5-14h 에서 본격 호출.</p>
   *
   * <p>409 (version_mismatch) / 422 (validation_failed) 는 axios 가 reject 하므로
   * 호출 측은 try/catch + e.response.data 로 TreePatchErrorResponse shape 처리.</p>
   */
  patchTree(frameworkId: number, payload: TreePatchPayload) {
    return api.patch<ApiResponse<TreePatchSuccessResponse>>(
      `/frameworks/${frameworkId}/tree`,
      payload
    )
  },

  /**
   * GET /api/v1/frameworks/{id}/export (5-14e).
   *
   * <p>현재 트리를 Excel(XLSX) 로 다운로드. POI 생성 + RFC 5987 한글 파일명. leaf 평탄화 +
   * ancestors path " > " 연결 (예: `1 > 1.1 > 1.1.1`).</p>
   *
   * <p>responseType: 'blob' — Phase 2 의 Blob 다운로드 패턴 그대로 (evidenceFilesApi.download
   * 와 동일 패턴). Content-Disposition 의 filename* 디코딩, 없으면 fallback 이름 사용.</p>
   */
  async exportFramework(frameworkId: number, fallbackName: string) {
    const response = await api.get<Blob>(`/frameworks/${frameworkId}/export`, {
      responseType: 'blob',
    })

    let downloadName = fallbackName
    const disposition = response.headers['content-disposition']
    if (disposition) {
      const utf8Match = disposition.match(/filename\*=UTF-8''(.+?)(?:;|$)/)
      if (utf8Match) {
        downloadName = decodeURIComponent(utf8Match[1])
      } else {
        const basicMatch = disposition.match(/filename="?(.+?)"?(?:;|$)/)
        if (basicMatch) {
          downloadName = basicMatch[1]
        }
      }
    }

    const blob = new Blob([response.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = downloadName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
  },
}

// ========================================
// Evidence Files API
// ========================================
export const evidenceFilesApi = {
  list(params?: { page?: number; size?: number }) {
    return api.get<ApiResponse<PageResponse<EvidenceFileItem>>>('/evidence-files', { params })
  },
  listByType(evidenceTypeId: number) {
    return api.get<ApiResponse<EvidenceFileItem[]>>(`/evidence-files/by-type/${evidenceTypeId}`)
  },
  getStats() {
    return api.get<ApiResponse<EvidenceFileStats>>('/evidence-files/stats')
  },

  /**
   * 증빙 파일 업로드 (Phase 5-2 / 5-4 / v18.6a 확장)
   *
   * admin 업로드 → review_status=auto_approved,
   * 담당자 업로드 → review_status=pending.
   *
   * v18.6a — 응답 shape 변경 ({@link UploadResponse}):
   * - status="created" → 정상 신규 등록 (evidenceFile 필드 사용)
   * - status="duplicate_detected" → sha256 일치, link 미생성, FE 가 confirm dialog
   *   노출 후 사용자 선택 (existingAsset 필드 사용)
   *
   * @param forceUpload true 시 중복 감지 무시하고 별도 asset 생성 (Q9 — 같은 sha
   *                    의 별도 asset)
   */
  upload(evidenceTypeId: number, file: File, submitNote?: string, forceUpload?: boolean) {
    const formData = new FormData()
    formData.append('evidenceTypeId', String(evidenceTypeId))
    formData.append('file', file)
    if (submitNote) {
      formData.append('submitNote', submitNote)
    }
    if (forceUpload) {
      formData.append('forceUpload', 'true')
    }
    return api.post<ApiResponse<UploadResponse>>('/evidence-files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },

  /**
   * v18.6a — 기존 asset 에 link 만 생성 (multipart 없음).
   *
   * 화면 mockup [기존 파일에서 선택] 또는 [중복 감지 → 기존 사용] 결과.
   * 권한 — assertCanAccessEvidenceType (본인 EvidenceType 만 link 가능).
   */
  link(payload: LinkRequest) {
    return api.post<ApiResponse<EvidenceFileItem>>('/evidence-files/link', payload)
  },

  delete(id: number) {
    return api.delete(`/evidence-files/${id}`)
  },

  async download(id: number, fileName?: string) {
    const response = await api.get(`/evidence-files/${id}/download`, {
      responseType: 'blob',
    })

    let downloadName = fileName || 'download'
    const disposition = response.headers['content-disposition']
    if (disposition) {
      const utf8Match = disposition.match(/filename\*=UTF-8''(.+?)(?:;|$)/)
      if (utf8Match) {
        downloadName = decodeURIComponent(utf8Match[1])
      } else {
        const basicMatch = disposition.match(/filename="?(.+?)"?(?:;|$)/)
        if (basicMatch) {
          downloadName = basicMatch[1]
        }
      }
    }

    const blob = new Blob([response.data])
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = downloadName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
  },

  /**
   * v15.6: param 명 controlId → nodeId rename (FE 측).
   * v15.7 Q3=B: BE wire shape 도 동기 변경 — `/evidence-files/zip/{controlId}` →
   *             `/zip/{nodeId}` (BC 0). client-side 의 path 보간은 ID 값만 들어가므로
   *             URL 문자열 자체에 영향 없음. controller @PathVariable 이 nodeId 이름으로
   *             매칭됨.
   */
  async downloadZip(nodeId: number, controlCode: string) {
    const response = await api.get(`/evidence-files/zip/${nodeId}`, {
      responseType: 'blob',
    })

    const blob = new Blob([response.data], { type: 'application/zip' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${controlCode}_증빙자료.zip`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
  },

  // ========================================
  // Phase 5-4: 승인 플로우 (관리자 전용)
  // ========================================

  listPending(params?: { page?: number; size?: number }) {
    return api.get<ApiResponse<PageResponse<EvidenceFileItem>>>('/evidence-files/pending', { params })
  },

  approve(fileId: number, payload?: ApproveRequest) {
    return api.post<ApiResponse<EvidenceFileItem>>(
      `/evidence-files/${fileId}/approve`,
      payload ?? {}
    )
  },

  reject(fileId: number, payload: RejectRequest) {
    return api.post<ApiResponse<EvidenceFileItem>>(
      `/evidence-files/${fileId}/reject`,
      payload
    )
  },
}

// ========================================
// Collection Jobs API
// ========================================
export const jobsApi = {
  list() {
    return api.get<ApiResponse<CollectionJobItem[]>>('/jobs')
  },
  getDetail(id: number) {
    return api.get<ApiResponse<CollectionJobDetail>>(`/jobs/${id}`)
  },
  create(data: CollectionJobCreatePayload) {
    return api.post<ApiResponse<CollectionJobItem>>('/jobs', data)
  },
  update(id: number, data: CollectionJobUpdatePayload) {
    return api.put<ApiResponse<CollectionJobItem>>(`/jobs/${id}`, data)
  },
  delete(id: number) {
    return api.delete(`/jobs/${id}`)
  },
  toggleActive(id: number) {
    return api.patch(`/jobs/${id}/toggle`)
  },
  execute(id: number) {
    return api.post<ApiResponse<ExecutionSummary>>(`/jobs/${id}/execute`)
  },

  // ────── v18.7 자동 수집 실패 진단 (L_USER_NEEDS_REDIRECT) ──────

  /**
   * v18.7 — 실패 시점 스크린샷 URL builder.
   * fetch 호출 없음 — URL 만 산출. <img :src="..." /> 또는 <a :href="..."> 에 활용.
   */
  getDiagnosisScreenshotUrl(executionId: number): string {
    return `/api/v1/admin/job-executions/${executionId}/diagnosis/screenshot`
  },

  /**
   * v18.7 — 실패 시점 페이지 소스 URL builder.
   * window.open(url, '_blank') 활용 — 새 탭에서 파일 다운로드.
   */
  getDiagnosisPageSourceUrl(executionId: number): string {
    return `/api/v1/admin/job-executions/${executionId}/diagnosis/page-source`
  },
}

// ========================================
// v18.7 — 진단 JSON 파싱 helper
// ========================================

/**
 * JobExecution.errorDiagnosis 의 JSON String 을 DiagnosisJson 객체로 파싱.
 * null/undefined/파싱 실패 시 null 반환 (FE graceful 처리).
 */
export function parseDiagnosis(rawJson: string | null | undefined): DiagnosisJson | null {
  if (!rawJson) return null
  try {
    return JSON.parse(rawJson) as DiagnosisJson
  } catch (e) {
    console.warn('진단 JSON 파싱 실패:', e)
    return null
  }
}

// ========================================
// v18.8.2 — 스크립트 관리 API (admin 한정, UID 기반)
// v18.8.7 — delete 메서드 추가 (Hard delete + 사용 중 검사 BE 처리)
//
// 어드민 UI 만으로 Python 스크립트 등록/수정/삭제. SSH 없이 진단 패널 → 수정 → 재실행 흐름.
// 사용자 의도: "스크립트 이름은 의미 없다. UID 로 관리." → filename 제거, 자동 id 부여.
// 모든 endpoint = hasRole('ADMIN').
// ========================================
export const scriptsApi = {
  /** 신규 작성 — content 만, 자동 id 부여 */
  create(payload: ScriptCreateRequest) {
    return api.post<ApiResponse<ScriptResponse>>('/admin/scripts', payload)
  },

  /** 기존 스크립트 내용 조회 — 편집 모드 진입 시 */
  getContent(id: number) {
    return api.get<ApiResponse<ScriptResponse>>(`/admin/scripts/${id}`)
  },

  /** 기존 스크립트 수정 (덮어쓰기). scriptId 유지 → 재실행 시 수정 반영 */
  update(id: number, payload: ScriptUpdateRequest) {
    return api.put<ApiResponse<ScriptResponse>>(`/admin/scripts/${id}`, payload)
  },

  /**
   * v18.8.7 — 스크립트 삭제 (Hard delete).
   *
   * BE 가 사용 중 검사 (active CollectionJob 의 script_id FK 참조 여부) 후 거부 / 진행.
   * 거부 시 BusinessException → axios reject → FE 의 catch 에서 e.response.data.message 노출.
   * 진행 시 물리 파일 + entity 삭제 + 옛 작업의 script_id 자동 NULL (@OnDelete(SET_NULL)).
   */
  delete(id: number) {
    return api.delete<ApiResponse<void>>(`/admin/scripts/${id}`)
  },

  // ── v19.5 — 버전 이력 (carry-over ⑤ FE) ──

  /** 버전 이력 목록 (BE 가 오래된 순 반환; 패널에서 최신순 정렬) */
  listVersions(id: number) {
    return api.get<ApiResponse<ScriptVersionResponse[]>>(`/admin/scripts/${id}/versions`)
  },

  /** 특정 버전 내용 (미리보기) */
  getVersion(id: number, versionNo: number) {
    return api.get<ApiResponse<ScriptVersionContentResponse>>(
      `/admin/scripts/${id}/versions/${versionNo}`,
    )
  },

  /** 롤백 (전진형) — 옛 버전 내용으로 새 버전 생성 + 실행본 교체. 새 현재 ScriptResponse 반환 */
  rollback(id: number, versionNo: number) {
    return api.post<ApiResponse<ScriptResponse>>(
      `/admin/scripts/${id}/versions/${versionNo}/rollback`,
    )
  },
}

// ────── v18.8.2 타입 정의 ──────

export interface ScriptResponse {
  id: number
  content: string
  contentSize: number
  createdAt: string   // ISO-8601
  updatedAt: string
}

export interface ScriptCreateRequest {
  content: string     // UTF-8 Python 소스 (최대 1MB)
}

export interface ScriptUpdateRequest {
  content: string
}

// ────── v19.5 버전 이력 타입 ──────

export interface ScriptVersionResponse {
  versionNo: number
  contentSize: number
  note?: string
  createdBy?: number
  createdAt: string   // ISO-8601
}

export interface ScriptVersionContentResponse {
  versionNo: number
  content: string
  contentSize: number
  note?: string
  createdAt: string
}

// ========================================
// v11 Phase 5-5: 담당자 "내 할 일" API
// ========================================
export const myTasksApi = {
  list() {
    return api.get<ApiResponse<MyTasksResponse>>('/my-tasks')
  },

  getDetail(evidenceTypeId: number) {
    return api.get<ApiResponse<MyTaskDetail>>(`/my-tasks/${evidenceTypeId}`)
  },
}
// ========================================
// v19.25 — 심사원(reviewer) 읽기 전용 API
//
// 관리자 트리 API(treeApi.getTree /frameworks/{id}/tree)는 BE 클래스 레벨 ADMIN +
// SecurityConfig /api/v1/frameworks/** ADMIN 으로 심사원 차단 → 별도 /review/** 사용.
// 응답은 스크립트·작업·이력·버전·노트를 제외한 평탄화 트리 + 항목별 최신 승인 파일뿐.
// ========================================

export interface ReviewFrameworkSummary {
  id: number
  name: string
}

export interface ReviewFileView {
  id: number
  fileName: string
}

export interface ReviewEvidenceTypeView {
  id: number
  name: string
  latestFile: ReviewFileView | null // 승인 파일 없으면 null → "미수집"
}

export interface ReviewNode {
  id: number
  parentId: number | null // 루트는 null
  code: string
  name: string
  depth: number
  evidenceTypes: ReviewEvidenceTypeView[]
}

export interface ReviewTreeResponse {
  framework: ReviewFrameworkSummary
  nodes: ReviewNode[] // depth ASC, displayOrder ASC 평탄화
}

export const reviewApi = {
  /** 심사원 랜딩 — 열람 가능한 active 프레임워크 목록. */
  listFrameworks() {
    return api.get<ApiResponse<ReviewFrameworkSummary[]>>('/review/frameworks')
  },

  /** 읽기 전용 트리 + leaf 별 최신 승인 파일. */
  getTree(frameworkId: number) {
    return api.get<ApiResponse<ReviewTreeResponse>>(`/review/frameworks/${frameworkId}/tree`)
  },

  /**
   * 승인 파일 다운로드. evidenceFilesApi.download 와 동일한 Blob 패턴
   * (responseType: 'blob' + Content-Disposition filename*=UTF-8'' 디코딩).
   * BE 가 approved/auto_approved 아닌 파일은 404 로 응답(존재 은닉).
   */
  async download(fileId: number, fileName?: string) {
    const response = await api.get(`/review/files/${fileId}/download`, {
      responseType: 'blob',
    })

    let downloadName = fileName || 'download'
    const disposition = response.headers['content-disposition']
    if (disposition) {
      const utf8Match = disposition.match(/filename\*=UTF-8''(.+?)(?:;|$)/)
      if (utf8Match) {
        downloadName = decodeURIComponent(utf8Match[1])
      } else {
        const basicMatch = disposition.match(/filename="?(.+?)"?(?:;|$)/)
        if (basicMatch) {
          downloadName = basicMatch[1]
        }
      }
    }

    const blob = new Blob([response.data])
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = downloadName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
  },
}

// ========================================
// v19.25 — 심사원 프레임워크 배정 API (admin 전용)
//
// 계정 설정(UserFormDialog)에서 심사원에게 열어줄 프레임워크를 지정. 계정 CRUD(usersApi)와
// 분리된 엔드포인트(/api/v1/admin/reviewers/{userId}/frameworks). /admin/** 는 이미 ADMIN.
// 배정 대상 프레임워크 목록은 기존 frameworksApi.list() 재사용.
// ========================================
export const reviewerAccessApi = {
  /** 이 심사원에게 배정된 framework id 목록. */
  getFrameworks(userId: number) {
    return api.get<ApiResponse<number[]>>(`/admin/reviewers/${userId}/frameworks`)
  },

  /** 배정 교체(replace-set). 저장 후 반영된 id 목록 반환. */
  setFrameworks(userId: number, frameworkIds: number[]) {
    return api.put<ApiResponse<number[]>>(`/admin/reviewers/${userId}/frameworks`, { frameworkIds })
  },
}