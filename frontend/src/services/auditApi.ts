// 감사 로그 조회 API (AUDIT-3).
//
// ⚠ 아래 두 import 는 evidenceApi.ts / dashboardApi.ts 와 "동일하게" 맞추세요:
//   - 공통 axios 인스턴스 `api` (baseURL=/api/v1, JWT 헤더 인터셉터, response.data 언래핑)
//   - `ApiResponse<T>` 타입 위치
// 본 프로젝트는 `api.get<ApiResponse<T>>(...)` 가 ApiResponse 봉투를 그대로 반환하고
// 호출부가 `.data` 로 payload 를 읽는 패턴(evidenceApi.ts 의 scriptsApi 와 동일).
import api from '@/services/api';
import type { ApiResponse } from '@/types';
import type { AuditLogPage, AuditLogSearchParams } from '@/types/audit';

export const auditLogsApi = {
  /** GET /api/v1/admin/audit-logs — admin 전용, 필터 + 페이지네이션. */
  search(params: AuditLogSearchParams) {
    return api.get<ApiResponse<AuditLogPage>>('/admin/audit-logs', { params });
  },
};