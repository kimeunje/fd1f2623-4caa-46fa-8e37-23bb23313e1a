package com.secuhub.domain.config.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.config.approval.ApprovalProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프런트엔드용 기능 플래그 노출.
 *
 * <p>SPA 가 로그인 직후 1회 받아 전역 상태(예: auth/feature 스토어)에 담고,
 * 사이드바 "검토" 메뉴 / 대시보드 "검토 대기" 위젯 / 업로드 후 검토 안내 토스트 등을
 * 조건부 렌더링하는 데 사용한다.</p>
 *
 * <p>민감 정보가 아니므로 인증 사용자면 누구나 조회 가능. 값은 서버 설정
 * ({@code app.approval.enabled}) 에서 그대로 파생.</p>
 */
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class FeatureConfigController {

    private final ApprovalProperties approvalProperties;

    /**
     * 기능 플래그 조회
     * GET /api/v1/config/features → { "approvalEnabled": true|false }
     */
    @GetMapping("/features")
    public ResponseEntity<ApiResponse<FeatureFlags>> features() {
        return ResponseEntity.ok(ApiResponse.ok(
                new FeatureFlags(approvalProperties.isEnabled())));
    }

    /** 현재 노출 플래그. 추후 다른 토글이 생기면 필드만 추가. */
    public record FeatureFlags(boolean approvalEnabled) {
    }
}