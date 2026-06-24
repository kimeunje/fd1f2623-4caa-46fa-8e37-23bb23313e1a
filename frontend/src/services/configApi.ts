import api from './api'
import type { ApiResponse } from '@/types'

export interface FeatureFlags {
  approvalEnabled: boolean
}

/**
 * 서버 기능 플래그 (app.* 설정 파생).
 * 로그인 직후 / 앱 복원 시 1회 받아 auth 스토어에 보관 → 조건부 렌더링에 사용.
 */
export const configApi = {
  getFeatures() {
    return api.get<ApiResponse<FeatureFlags>>('/config/features')
  },
}