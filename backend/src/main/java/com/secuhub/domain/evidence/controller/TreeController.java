package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.dto.TreeDto;
import com.secuhub.domain.evidence.dto.TreeUpdateDto;
import com.secuhub.domain.evidence.service.TreeService;
import com.secuhub.domain.evidence.service.TreeUpdateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 5-14c/d — Framework 트리 API.
 *
 * <p>v14 옵션 D — {@code control_nodes} 자기참조 트리.</p>
 *
 * <ul>
 *   <li><b>GET /api/v1/frameworks/{id}/tree</b> (5-14c) — 평탄화 응답 빌드</li>
 *   <li><b>PATCH /api/v1/frameworks/{id}/tree</b> (5-14d) — 변경분 일괄 저장,
 *       Optimistic lock + tempId 위상 정렬 + 12 검증 규칙</li>
 * </ul>
 *
 * <p>5-14e 의 impact-summary, export 는 별도 엔드포인트로 추가 예정.
 * 5-14e 의 controls/{id}/impact-summary 는 path prefix 가 다르므로 별도 컨트롤러
 * 가능 — TBD 5-14e.</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class TreeController {

    private final TreeService treeService;
    private final TreeUpdateService treeUpdateService;

    /**
     * Framework 의 트리 평탄화 + version 응답 (5-14c).
     */
    @GetMapping("/frameworks/{frameworkId}/tree")
    public ResponseEntity<ApiResponse<TreeDto.Response>> getTree(@PathVariable Long frameworkId) {
        return ResponseEntity.ok(ApiResponse.ok(treeService.getTree(frameworkId)));
    }

    /**
     * Framework 트리 변경분 일괄 저장 (5-14d).
     *
     * <p>응답 시나리오:</p>
     * <ul>
     *   <li><b>200 OK</b> — {@code ApiResponse<TreeUpdateDto.Response>} (version, mappings)</li>
     *   <li><b>404 Not Found</b> — Framework 미존재</li>
     *   <li><b>409 Conflict</b> — expectedVersion 불일치 ({@code TreeUpdateErrorResponse})</li>
     *   <li><b>422 Unprocessable Entity</b> — 12 검증 규칙 위반 ({@code TreeUpdateErrorResponse} + details[])</li>
     * </ul>
     *
     * <p>409 / 422 의 응답 shape 은 {@code TreeUpdateErrorResponse} — spec §3.3.1.4 정합.
     * GlobalExceptionHandler 가 매핑.</p>
     */
    @PatchMapping("/frameworks/{frameworkId}/tree")
    public ResponseEntity<ApiResponse<TreeUpdateDto.Response>> patchTree(
            @PathVariable Long frameworkId,
            @RequestBody TreeUpdateDto.Request request) {
        TreeUpdateDto.Response response = treeUpdateService.updateTree(frameworkId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}