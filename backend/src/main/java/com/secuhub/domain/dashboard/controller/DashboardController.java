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
 * 대시보드 위젯 데이터 endpoint (Phase v16.4a — §2.2 잔여 #1 일부, v16.4b-fix 권한 정합).
 *
 * <p>spec §3.8 정합. v16.0 baseline 의 "Phase 5-8 예정" 표기를 v16.4a 에서 구현.</p>
 *
 * <h3>v16.4b-fix 변경</h3>
 * <ul>
 *   <li>{@code @PreAuthorize("hasRole('admin')")} (소문자, v16.4a 시점 — 운영 시
 *       AuthorizationDeniedException 발생) → {@code @PreAuthorize("hasRole('ADMIN')")}
 *       (대문자, 본 프로젝트 컨벤션 정합).</li>
 * </ul>
 *
 * <h3>본 프로젝트 권한 컨벤션 (v16.4b-fix 시점 명문화)</h3>
 * <ul>
 *   <li>{@code UserRole} enum 값 = 소문자 ({@code admin / approver / developer})</li>
 *   <li>{@code JwtAuthenticationFilter} 가 JWT subject claim 의 role 을
 *       {@code "ROLE_" + role.toUpperCase()} 로 변환 → SimpleGrantedAuthority
 *       ({@code ROLE_ADMIN})</li>
 *   <li>모든 controller / SecurityConfig 의 권한 표기 = {@code hasRole('ADMIN')}
 *       (대문자) — 본 프로젝트 컨벤션. 9 controller 모두 정합 (v16.4b-fix 까지),
 *       DashboardController 가 유일한 위반자였음.</li>
 * </ul>
 *
 * <p>참조: SecurityConfig.{@code requestMatchers(...).hasRole("ADMIN")},
 * EvidenceTypeController / UserController / FrameworkController / TreeController /
 * ControlNodeController / EvidenceFileController / CollectionJobController 모두 대문자.</p>
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
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AdminDashboardSummaryDto> adminSummary() {
        log.debug("관리자 대시보드 요약 조회");
        return ApiResponse.ok(dashboardService.getAdminSummary());
    }
}