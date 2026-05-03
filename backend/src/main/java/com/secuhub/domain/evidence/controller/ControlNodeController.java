package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.dto.ControlDto;
import com.secuhub.domain.evidence.dto.ControlNodeDetailDto;
import com.secuhub.domain.evidence.dto.ImpactSummaryDto;
import com.secuhub.domain.evidence.service.ControlNodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * v14 Phase 5-14e (생성, impact-summary 1 endpoint) /
 * v15 Phase 5-15b Round 3 (v15.6 — URL 이전 + 2 endpoint 추가) — control_node 단위
 * 운영 데이터 API.
 *
 * <p>v15.6 시점 3 endpoint 보유:</p>
 * <pre>
 *   GET /api/v1/control-nodes/{id}                    (v15.6 신규 — leaf detail)
 *   GET /api/v1/control-nodes/{id}/evidence-types     (v15.6 신규 — leaf 의 ET 분리 응답)
 *   GET /api/v1/control-nodes/{id}/impact-summary     (v14.5 → v15.6 URL 이전)
 * </pre>
 *
 * <h3>v15.6 통합 정리 — URL 이전 (옛 namespace 폐기)</h3>
 *
 * <p>v15.3 (5-15b R1) 의 ControlController 통째 삭제로 옛 {@code GET /api/v1/controls/{id}}
 * 폐기. 본 컨트롤러도 v14.5 부터 옛 namespace ({@code /api/v1/controls/}) 안에서
 * longer-match 로 공존했으나, v15.6 부터 의미 일치 위해 새 namespace
 * ({@code /api/v1/control-nodes/}) 로 일괄 이전.</p>
 *
 * <p>옛 path {@code /api/v1/controls/{id}/impact-summary} 는 본 phase 에서
 * deprecation 0 으로 일괄 폐기 (Q1=A 결정). v15.x 누적 BC layer 종결의 일환.</p>
 *
 * <h3>옛 ControlController.getDetail (v15.3 삭제) 의 의미 흡수</h3>
 *
 * <p>{@code GET /api/v1/control-nodes/{id}} 는 v15.3 에서 폐기된 옛
 * {@code GET /api/v1/controls/{id}} 의 leaf detail 응답 shape 을 보존한다 — FE 의
 * ControlsView leaf 펼침 (v15.5.1 워크어라운드의 회복점) 정상화. 응답 DTO 만 명명
 * 정합 ({@link ControlDto.DetailResponse} → {@link ControlNodeDetailDto}) 으로 신규
 * 작성.</p>
 */
@RestController
@RequestMapping("/api/v1/control-nodes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ControlNodeController {

    private final ControlNodeService controlNodeService;

    /**
     * leaf control_node 상세 조회 — ControlsView 트리의 leaf 클릭 인라인 펼침에서 사용.
     *
     * <p>v15.3 까지 옛 {@code GET /api/v1/controls/{id}} (ControlController.getDetail)
     * 가 담당했던 응답 의미 (leaf + ancestors[] + evidenceTypes[] + 카운트 4) 를 본
     * endpoint 가 흡수. spec §8.2 ancestors[] 정합.</p>
     *
     * @param id leaf control_node.id
     * @return leaf detail DTO ({@link ControlNodeDetailDto})
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ControlNodeDetailDto>> getControlNodeDetail(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(controlNodeService.getControlNodeDetail(id)));
    }

    /**
     * leaf control_node 의 evidence-types 만 분리 응답.
     *
     * <p>본 endpoint 는 leaf detail 의 evidenceTypes[] 부분만 분리 조회. evidence
     * 운영 화면 (EvidenceTypeDetailView 등) 에서 재로드 비용 최소화 시 사용.
     * 응답 shape 은 {@link ControlDto.EvidenceTypeResponse} 재사용 (FE
     * EvidenceTypeResponse 타입과 정합).</p>
     *
     * @param id leaf control_node.id
     * @return evidence-types 리스트
     */
    @GetMapping("/{id}/evidence-types")
    public ResponseEntity<ApiResponse<List<ControlDto.EvidenceTypeResponse>>> getEvidenceTypes(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(controlNodeService.getEvidenceTypes(id)));
    }

    /**
     * leaf / hybrid 통제 코드 변경 사전 경고 다이얼로그용 카운트 조회.
     *
     * <p>v14 Phase 5-14e 도입. v15 Phase 5-15a 에서 own / descendant 분리 9 필드로
     * 확장. 5-14h FE 가 leaf 코드 인라인 편집 직후 호출. 응답 합산 (own + descendant)
     * &gt; 0 이면 경고 다이얼로그 노출, 0 이면 즉시 코드 변경. spec §3.3.1.5 정합.</p>
     *
     * <p>v15.6: URL 이전 ({@code /api/v1/controls/{id}/impact-summary} →
     * {@code /api/v1/control-nodes/{id}/impact-summary}). 응답 shape / service 호출은
     * 변경 없음.</p>
     *
     * @param id leaf 또는 hybrid control_node.id
     * @return own / descendant / legacy alias 9 필드 채워진 DTO
     */
    @GetMapping("/{id}/impact-summary")
    public ResponseEntity<ApiResponse<ImpactSummaryDto>> getImpactSummary(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(controlNodeService.getImpactSummary(id)));
    }
}