package com.secuhub.domain.dashboard.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.dashboard.dto.AdminDashboardSummaryDto;
import com.secuhub.domain.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대시보드 위젯 데이터 endpoint (Phase v16.4a — §2.2 잔여 #1 일부).
 *
 * <p>spec §3.8 정합. v16.0 baseline 의 "Phase 5-8 예정" 표기를 본 phase 에서 구현.</p>
 *
 * <h3>권한 체크</h3>
 * <p>{@link PreAuthorize} 사용. 프로젝트 표준이 다른 경우 (예: SecurityConfig 의
 * antMatchers / EvidenceAuthService 패턴) 호출 측 정합:</p>
 * <ul>
 *   <li>옵션 1: 본 어노테이션 + {@code @EnableMethodSecurity(prePostEnabled=true)}
 *       SecurityConfig 활성</li>
 *   <li>옵션 2: 본 어노테이션 제거 + service 레이어에서
 *       {@code SecurityContextHolder} 로 role 직접 체크</li>
 *   <li>옵션 3: SecurityConfig 의 antMatchers 에 {@code "/api/v1/dashboard/admin-**"}
 *       hasRole("admin") 추가</li>
 * </ul>
 *
 * <p>본 phase 는 옵션 1 (가장 표준) 가정. 활성화 안 되어 있을 시 patch 적용 후 IT
 * 테스트 실행 시점 발견 → 옵션 2 로 fallback (DashboardService 안에서 직접 체크).</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * GET /api/v1/dashboard/admin-summary.
     *
     * <p>관리자 대시보드 요약 — KPI ("내 승인 대기 N건") + 승인 대기 목록 (top 10) +
     * Framework 별 진척. 단일 응답으로 3 위젯 데이터 모두 제공 (FE 측 N+1 호출 방지).</p>
     *
     * <p>응답 shape: {@link AdminDashboardSummaryDto} 참조.</p>
     *
     * @return 200 OK + summary. 인증 없음 401, admin 외 403.
     */
    @GetMapping("/admin-summary")
    @PreAuthorize("hasRole('admin')")
    public ApiResponse<AdminDashboardSummaryDto> adminSummary() {
        log.debug("관리자 대시보드 요약 조회");
        return ApiResponse.ok(dashboardService.getAdminSummary());
    }
}