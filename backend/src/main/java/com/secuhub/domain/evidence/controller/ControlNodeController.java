package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.dto.ImpactSummaryDto;
import com.secuhub.domain.evidence.service.ControlNodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 5-14e — control_node 단위 운영 데이터 API.
 *
 * <p>현재는 impact-summary 한 endpoint 만 제공.</p>
 *
 * <p><b>왜 별도 컨트롤러인가?</b> — 5-14c 의 {@code TreeController} 와 같은 논리.
 * 기존 {@link ControlController} 는 v14 동안 외부 호환용으로 유지되지만 v15 에서 제거 예정.
 * v15 의 ControlController 제거 시 본 컨트롤러는 자연 잔존 (impact-summary 는 v15 이후로도
 * 5-14h 의 코드 변경 경고 다이얼로그가 계속 사용).</p>
 *
 * <p>URL prefix {@code /api/v1/controls/} 는 기존 {@link ControlController} 와 같지만,
 * Spring 의 RequestMapping 은 longer-match 우선이라 충돌 없음
 * ({@code /controls/{id}/impact-summary} vs 기존 {@code /controls/{id}}).</p>
 */
@RestController
@RequestMapping("/api/v1/controls")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ControlNodeController {

    private final ControlNodeService controlNodeService;

    /**
     * leaf 통제 코드 변경 사전 경고 다이얼로그용 카운트 조회.
     *
     * <p>{@code GET /api/v1/controls/{id}/impact-summary}</p>
     *
     * <p>5-14h FE 가 leaf 코드 인라인 편집 직후 호출. 응답 합산 > 0 이면 경고 다이얼로그 노출,
     * 0 이면 즉시 코드 변경. spec §3.3.1.5 정합.</p>
     *
     * @param id leaf control_node.id (5-14e Q1=A — HANDOFF v12 §8.1)
     * @return {evidenceFileCount, jobCount, reviewCount}
     */
    @GetMapping("/{id}/impact-summary")
    public ResponseEntity<ApiResponse<ImpactSummaryDto>> getImpactSummary(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(controlNodeService.getImpactSummary(id)));
    }
}