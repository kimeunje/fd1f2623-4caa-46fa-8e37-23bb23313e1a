package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.service.ReviewerAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * v19.25 — 심사원 프레임워크 배정 API(admin 전용).
 *
 * <p>계정 설정(UserFormDialog)에서 심사원에게 열어줄 프레임워크를 지정할 때 사용. 계정 CRUD
 * (UserController/UserDto)와 분리 — v19.0 IP ACL(IpRulesDialog)이 계정 CRUD 와 분리된 선례 정합.</p>
 *
 * <p>{@code /api/v1/admin/**} 는 SecurityConfig 에서 이미 {@code hasRole("ADMIN")} URL 규칙이
 * 걸려 있어 별도 SecurityConfig 변경이 없다. 클래스 레벨 {@code @PreAuthorize} 로 defense in depth.</p>
 *
 * <ul>
 *   <li><b>GET  /api/v1/admin/reviewers/{userId}/frameworks</b> — 배정된 framework id 목록</li>
 *   <li><b>PUT  /api/v1/admin/reviewers/{userId}/frameworks</b> — 배정 교체(body: {frameworkIds:[...]}) </li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/reviewers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ReviewerAccessController {

    private final ReviewerAccessService reviewerAccessService;

    @GetMapping("/{userId}/frameworks")
    public ResponseEntity<ApiResponse<List<Long>>> getFrameworks(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(reviewerAccessService.getAssignedFrameworkIds(userId)));
    }

    @PutMapping("/{userId}/frameworks")
    public ResponseEntity<ApiResponse<List<Long>>> setFrameworks(
            @PathVariable Long userId,
            @RequestBody ReviewerFrameworksRequest request) {
        reviewerAccessService.setAssignedFrameworks(userId, request.frameworkIds());
        return ResponseEntity.ok(ApiResponse.ok(reviewerAccessService.getAssignedFrameworkIds(userId)));
    }

    /** 배정 교체 요청 body. */
    public record ReviewerFrameworksRequest(List<Long> frameworkIds) {}
}