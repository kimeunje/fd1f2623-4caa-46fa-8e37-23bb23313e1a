import api from './api'
import type { ApiResponse, PageResponse } from '@/types'
import type {
  Framework,
  FrameworkCreatePayload,
  FrameworkInheritPayload,
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