import api from './api'
import type { ApiResponse, PageResponse } from '@/types'
import type {
  Framework,
  FrameworkCreatePayload,
  ControlItem,
  ControlDetail,
  ControlCreatePayload,
  ControlUpdatePayload,
  EvidenceTypePayload,
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
  ExcelImportResult,
  MyTasksResponse,
  MyTaskDetail,
} from '@/types/evidence'

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
  create(frameworkId: number, data: ControlCreatePayload) {
    return api.post<ApiResponse<ControlItem>>(`/frameworks/${frameworkId}/controls`, data)
  },
  update(id: number, data: ControlUpdatePayload) {
    return api.put<ApiResponse<ControlItem>>(`/controls/${id}`, data)
  },
  delete(id: number) {
    return api.delete(`/controls/${id}`)
  },
  addEvidenceType(controlId: number, data: EvidenceTypePayload) {
    return api.post<ApiResponse<EvidenceTypeResponse>>(`/controls/${controlId}/evidence-types`, data)
  },
  deleteEvidenceType(evidenceTypeId: number) {
    return api.delete(`/evidence-types/${evidenceTypeId}`)
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
   * admin 이 업로드하면 review_status=auto_approved,
   * 담당자가 업로드하면 review_status=pending 으로 자동 설정됨.
   *
   * @param submitNote 담당자 제출 메모 (선택, admin 은 보통 생략)
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

  /**
   * 증빙 파일 다운로드 — Blob으로 받아서 브라우저 다운로드 트리거
   * JWT 토큰이 Authorization 헤더에 자동 포함됩니다.
   */
  async download(id: number, fileName?: string) {
    const response = await api.get(`/evidence-files/${id}/download`, {
      responseType: 'blob',
    })

    // Content-Disposition 헤더에서 파일명 추출 시도
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
   * 통제항목별 전체 증빙 파일 ZIP 다운로드
   */
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

  /**
   * 승인 대기 목록 조회 (페이징, 관리자 전용)
   */
  listPending(params?: { page?: number; size?: number }) {
    return api.get<ApiResponse<PageResponse<EvidenceFileItem>>>('/evidence-files/pending', { params })
  },

  /**
   * 증빙 파일 승인 (관리자 전용)
   * reviewNote 는 선택. 생략 가능.
   */
  approve(fileId: number, payload?: ApproveRequest) {
    return api.post<ApiResponse<EvidenceFileItem>>(
      `/evidence-files/${fileId}/approve`,
      payload ?? {}
    )
  },

  /**
   * 증빙 파일 반려 (관리자 전용)
   * reviewNote 필수. 빈 값이면 백엔드가 400 응답.
   */
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
  /**
   * 5섹션 묶음 응답 조회.
   * - permission_evidence=false 사용자는 403
   * - admin 은 항상 허용 (본인 owner 증빙만 반환)
   */
  list() {
    return api.get<ApiResponse<MyTasksResponse>>('/my-tasks')
  },

  /**
   * 증빙 유형 상세 (재제출 페이지용).
   * 본인 소유가 아니면 403.
   */
  getDetail(evidenceTypeId: number) {
    return api.get<ApiResponse<MyTaskDetail>>(`/my-tasks/${evidenceTypeId}`)
  },
}