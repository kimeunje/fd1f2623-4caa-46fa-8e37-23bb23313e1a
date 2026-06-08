package com.secuhub.domain.access.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.config.security.ClientIpResolver;
import com.secuhub.domain.access.dto.IpAccessRuleDto;
import com.secuhub.domain.access.service.IpAccessRuleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * v19.1 — 계정별 IP 접근 규칙 관리 API (BE-2). ADMIN 전용.
 *
 * <ul>
 *   <li>{@code GET    /api/v1/users/{userId}/ip-rules} — 규칙 목록(활성+비활성, 생성순)</li>
 *   <li>{@code POST   /api/v1/users/{userId}/ip-rules} — 규칙 생성</li>
 *   <li>{@code PATCH  /api/v1/users/{userId}/ip-rules/{ruleId}} — 부분 수정</li>
 *   <li>{@code DELETE /api/v1/users/{userId}/ip-rules/{ruleId}} — 삭제</li>
 * </ul>
 *
 * <p>UserController 와 동일하게 {@code /api/v1/users/**} 아래 — SecurityConfig 의
 * {@code anyRequest().authenticated()} + 본 클래스 {@code hasRole('ADMIN')} 로 admin 제약.
 * 생성/수정 시 호출 관리자의 현재 IP 를 해석해 자기 잠금 방지 가드에 사용한다.</p>
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/ip-rules")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class IpAccessRuleController {

    private final IpAccessRuleService ipAccessRuleService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping
    public ResponseEntity<ApiResponse<List<IpAccessRuleDto.Response>>> list(
            @PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(ipAccessRuleService.list(userId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<IpAccessRuleDto.Response>> create(
            @PathVariable Long userId,
            @Valid @RequestBody IpAccessRuleDto.CreateRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        String actingIp = clientIpResolver.resolve(httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(
                ipAccessRuleService.create(userId, request, principal.getUserId(), actingIp)));
    }

    @PatchMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<IpAccessRuleDto.Response>> update(
            @PathVariable Long userId,
            @PathVariable Long ruleId,
            @Valid @RequestBody IpAccessRuleDto.UpdateRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        String actingIp = clientIpResolver.resolve(httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(
                ipAccessRuleService.update(userId, ruleId, request, principal.getUserId(), actingIp)));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long userId,
            @PathVariable Long ruleId) {
        ipAccessRuleService.delete(userId, ruleId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}