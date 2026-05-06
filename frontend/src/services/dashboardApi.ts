import api from './api'
import type { ApiResponse } from '@/types'
import type { AdminDashboardSummary } from '@/types/evidence'

/**
 * 관리자 대시보드 API (v16.4b 신규).
 *
 * <p>BE: v16.4a 의 {@code DashboardController} → {@code GET /api/v1/dashboard/admin-summary}.
 * spec §3.8 정합. 단일 호출로 KPI + 승인 대기 목록 (top 10) + Framework 진척 모두
 * 일괄 응답 — FE N+1 호출 회피.</p>
 *
 * <h3>권한 처리</h3>
 * <ul>
 *   <li>401 (인증 없음) — api.ts 의 응답 인터셉터가 자동으로 /login 리다이렉트
 *       (별도 처리 불요)</li>
 *   <li>403 (admin 외 역할) — 본 view 는 router meta.roles=['admin'] 으로 사전
 *       차단되므로 호출 자체가 발생 안 됨. 그러나 관리자 권한 변경 등 edge case
 *       방어는 호출 측에서 try/catch + 토스트로 처리</li>
 * </ul>
 *
 * <h3>왜 evidenceApi.ts 안이 아닌 별도 파일</h3>
 * <p>evidenceApi.ts 가 이미 7 개 API 묶음 (frameworksApi / controlNodesApi /
 * evidenceTypesApi / treeApi / evidenceFilesApi / jobsApi / myTasksApi) 으로
 * 비대 — 신규 dashboard 도메인을 별도 파일로 분리하면 관심사 명확. 향후 Phase 3
 * 의 "대시보드 통계" 추가 시 본 파일에 자연 확장.</p>
 *
 * <p>같은 axios instance ({@code api}) 를 import 하므로 baseURL / JWT / 401
 * 인터셉터 모두 동일 동작.</p>
 */
export const dashboardApi = {
  /**
   * GET /api/v1/dashboard/admin-summary
   *
   * <p>관리자 대시보드 단일 fetch. 진입 시 1회 호출 + 사용자가 새로고침 버튼
   * 클릭 시 재호출. 자동 polling 미적용 (운영 피드백 후 별도 phase).</p>
   *
   * @returns ApiResponse<AdminDashboardSummary>
   */
  getAdminSummary() {
    return api.get<ApiResponse<AdminDashboardSummary>>('/dashboard/admin-summary')
  },
}