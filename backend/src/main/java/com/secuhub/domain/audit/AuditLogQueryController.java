package com.secuhub.domain.audit;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.audit.dto.AuditLogPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 감사 로그 조회 API (AUDIT-2). admin 전용 — class 레벨 @PreAuthorize(대문자, 본 프로젝트 컨벤션).
 *
 * <p>{@code GET /api/v1/admin/audit-logs?keyword=&action=&result=&from=&to=&page=&size=}
 * 모든 필터 optional. keyword 는 이메일/IP/대상명 부분일치. 정렬 created_at DESC 고정. 페이지네이션.</p>
 *
 * <p>비로그인 = 401, 비admin = 403(@PreAuthorize) — 다른 admin 컨트롤러와 동일.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AuditLogQueryController {

    private final AuditQueryService auditQueryService;

    @GetMapping
    public ApiResponse<AuditLogPageResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditResult result,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.ok(
                auditQueryService.search(keyword, action, result, from, to, page, size));
    }
}