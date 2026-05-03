import api from './api'
import type { ApiResponse, PageResponse } from '@/types'
import type {
  Framework,
  FrameworkCreatePayload,
  FrameworkInheritPayload,
  ControlItem,
  ControlDetail,
  EvidenceFileItem,
  EvidenceFileStats,
  ApproveRequest,
  RejectRequest,
  CollectionJobItem,
  CollectionJobDetail,
  CollectionJobCreatePayload,
  CollectionJobUpdatePayload,
  ExecutionSummary,
  ExcelImportResult,
  MyTasksResponse,
  MyTaskDetail,
  // v14 Phase 5-14g — 통제 트리
  TreeResponse,
  TreePatchPayload,
  TreePatchSuccessResponse,
  ImpactSummary,
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
  delete(id: number) {
    return api.delete(`/frameworks/${id}`)
  },
  importControls(id: number, file: File) {
    const formData = new FormData()
    formData.append('file', file)
    return api.post<ApiResponse<ExcelImportResult>>(`/frameworks/${id}/import`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
}

// ========================================
// Controls API
// ========================================
export const controlsApi = {
  listByFramework(frameworkId: number) {
    return api.get<ApiResponse<ControlItem[]>>(`/frameworks/${frameworkId}/controls`)
  },
  getDetail(id: number) {
    return api.get<ApiResponse<ControlDetail>>(`/controls/${id}`)
  },
  /** v14 Phase 5-14f 보존 — leaf 의 evidence_type 단건 삭제는 그대로 유효. */
  deleteEvidenceType(evidenceTypeId: number) {
    return api.delete(`/evidence-types/${evidenceTypeId}`)
  },
}

// ========================================
// v14 Phase 5-14g — 통제 트리 API (control_nodes 자기참조)
//
// spec §3.3.1.4 의 4개 신규 엔드포인트를 모은 단일 객체.
// 5-14g 는 read 만 적극 활용 (getTree / getImpactSummary / exportFramework).
// patchTree 는 5-14h 에서 본격 사용 — 정의만 노출.
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
   * 매핑. 12 검증 규칙. 5-14g 는 정의만 노출, 5-14h 에서 본격 호출.</p>
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
   * GET /api/v1/controls/{id}/impact-summary (5-14e).
   *
   * <p>leaf 코드 변경 사전 경고용 3 카운트 (evidenceFileCount / jobCount / reviewCount).
   * 합산 > 0 이면 5-14h 의 코드 변경 경고 다이얼로그 발동. 5-14g 는 정의만 노출.</p>
   *
   * <p>존재하지 않는 controlId 도 404 가 아닌 {0,0,0} 반환 (5-14e Q2-A).</p>
   */
  getImpactSummary(controlId: number) {
    return api.get<ApiResponse<ImpactSummary>>(`/controls/${controlId}/impact-summary`)
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
   * 증빙 파일 업로드 (Phase 5-2 / 5-4 확장)
   *
   * admin 업로드 → review_status=auto_approved,
   * 담당자 업로드 → review_status=pending.
   */
  upload(evidenceTypeId: number, file: File, submitNote?: string) {
    const formData = new FormData()
    formData.append('evidenceTypeId', String(evidenceTypeId))
    formData.append('file', file)
    if (submitNote) {
      formData.append('submitNote', submitNote)
    }
    return api.post<ApiResponse<EvidenceFileItem>>('/evidence-files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
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

  async downloadZip(controlId: number, controlCode: string) {
    const response = await api.get(`/evidence-files/zip/${controlId}`, {
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