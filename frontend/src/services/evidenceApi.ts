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
  CollectionJobItem,
  CollectionJobDetail,
  CollectionJobCreatePayload,
  CollectionJobUpdatePayload,
  ExecutionSummary,
  ExcelImportResult,
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
  upload(evidenceTypeId: number, file: File) {
    const formData = new FormData()
    formData.append('evidenceTypeId', String(evidenceTypeId))
    formData.append('file', file)
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
      // filename*=UTF-8''encoded_name 형식 우선
      const utf8Match = disposition.match(/filename\*=UTF-8''(.+?)(?:;|$)/)
      if (utf8Match) {
        downloadName = decodeURIComponent(utf8Match[1])
      } else {
        // filename="name" 형식 fallback
        const basicMatch = disposition.match(/filename="?(.+?)"?(?:;|$)/)
        if (basicMatch) {
          downloadName = basicMatch[1]
        }
      }
    }

    // Blob URL 생성 → <a> 클릭으로 다운로드 트리거
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