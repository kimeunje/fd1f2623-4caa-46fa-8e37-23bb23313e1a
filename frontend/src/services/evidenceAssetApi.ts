import api from './api'
import type { ApiResponse } from '@/types'
import type {
  EvidenceAsset,
  AssetSearchParams,
  AssetPage,
} from '@/types/evidence'

/**
 * v18.6a — Evidence Asset 신규 채널 API.
 *
 * <h3>BE endpoint</h3>
 * <ul>
 *   <li>GET /api/v1/evidence-assets — 검색 (페이지네이션, Q8 권한 = isAuthenticated)</li>
 *   <li>GET /api/v1/evidence-assets/{id} — 단건 조회</li>
 * </ul>
 *
 * <h3>응답 shape — Spring Page 직접 (PageResponse 변환 안 함)</h3>
 * <p>v18.6a 의 예외 — 본 API 만 Spring Data Page 표준 ({@code content / totalElements}).
 * v18.6c 또는 후순위로 BE 의 PageResponse.from 적용 + 본 type 정리 carry-over.</p>
 *
 * <h3>linkExistingAsset 위치</h3>
 * <p>BE endpoint = POST /api/v1/evidence-files/link. namespace 정합으로
 * {@code evidenceFilesApi.link} 에 정의 ({@code evidenceApi.ts}). 본 객체는 read-only.</p>
 */
export const evidenceAssetsApi = {
  /**
   * 검색 — q (파일명 LIKE prefix) / uploaderId / from / to 필터 + page/size.
   *
   * 결과의 각 asset 에 usedInCount (Q11) 포함.
   */
  search(params: AssetSearchParams = {}) {
    return api.get<ApiResponse<AssetPage<EvidenceAsset>>>('/evidence-assets', { params })
  },

  /**
   * 단건 조회 — id 로 EvidenceAsset 의 sha256 / filePath / usedInCount 등 메타.
   */
  getById(id: number) {
    return api.get<ApiResponse<EvidenceAsset>>(`/evidence-assets/${id}`)
  },
}