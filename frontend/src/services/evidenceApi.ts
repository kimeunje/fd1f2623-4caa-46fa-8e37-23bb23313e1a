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
  downloadUrl(id: number) {
    return `/api/v1/evidence-files/${id}/download`
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
