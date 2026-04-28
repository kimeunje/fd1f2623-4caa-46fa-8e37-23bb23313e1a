package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.dto.TreeDto;
import com.secuhub.domain.evidence.service.TreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 5-14c — Framework 트리 API.
 *
 * <p>v14 옵션 D — {@code control_nodes} 자기참조 트리의 평탄화 응답.
 * UnifiedControlsDialog (5-14g) 와 ControlsView 트리 본문 (5-14g) 모두
 * 본 단일 GET 호출로 채워진다.</p>
 *
 * <p>후속 phase 에서 같은 prefix 로 추가 예정:</p>
 * <ul>
 *   <li>{@code PATCH /api/v1/frameworks/{id}/tree} — 5-14d (가장 큰 작업)</li>
 *   <li>{@code GET /api/v1/controls/{id}/impact-summary} — 5-14e</li>
 *   <li>{@code GET /api/v1/frameworks/{id}/export} — 5-14e</li>
 * </ul>
 *
 * <p>본 컨트롤러는 신규 클래스 — 기존 {@code ControlController} 와 분리한 이유:
 * (1) v14 트리 API 의 endpoint shape 이 기존 평면 controls 와 다르고,
 * (2) v15 에서 {@code controls} 테이블 + {@code ControlController} 제거 시점에
 * 트리 API 만 자연스럽게 잔존하도록 분리.</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class TreeController {

    private final TreeService treeService;

    /**
     * Framework 의 트리 평탄화 + version 응답.
     *
     * @param frameworkId Framework PK
     * @return 200 OK + {@link TreeDto.Response}, 404 Not Found (Framework 없음)
     */
    @GetMapping("/frameworks/{frameworkId}/tree")
    public ResponseEntity<ApiResponse<TreeDto.Response>> getTree(@PathVariable Long frameworkId) {
        return ResponseEntity.ok(ApiResponse.ok(treeService.getTree(frameworkId)));
    }
}