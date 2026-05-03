package com.secuhub.domain.evidence.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * v15 Phase 5-15b Round 3 (v15.6) — control_node 단위 leaf detail 응답 DTO.
 *
 * <p>{@code GET /api/v1/control-nodes/{id}} 응답. v15.3 에서 폐기된 옛
 * {@code GET /api/v1/controls/{id}} (ControlController.getDetail) 의 응답 shape
 * ({@link ControlDto.DetailResponse}) 을 본 phase 의 명명 정합 위해 신규 DTO 로
 * 분리.</p>
 *
 * <p>FE {@code ControlDetail} 타입 (types/evidence.ts) 정합. v15.7+ 에서
 * {@link ControlDto.DetailResponse} (호출처 0 dead) 일괄 제거 검토 (학습 후보).</p>
 *
 * <h3>필드 정합 (FE ControlDetail extends ControlItem)</h3>
 * <ul>
 *   <li>ControlItem 필드: id / frameworkId / code / domain / name / description /
 *       evidenceTotal / evidenceCollected / status / createdAt / pendingReviewCount</li>
 *   <li>ControlDetail 추가: evidenceTypes[] / ancestors[]</li>
 * </ul>
 *
 * <p>spec §8.2 정합 — ancestors[] 는 depth=1 부터 leaf 직계 부모까지 순서대로,
 * leaf 자기 자신 미포함. depth=1 leaf 의 경우 빈 리스트 (null 아님).</p>
 *
 * <h3>왜 ControlDto.DetailResponse 직접 재사용 안 했나</h3>
 *
 * <p>v15.6 = 명명 정리 phase. control_node 단위 응답이 옛 명명 ({@code ControlDto})
 * 안에 들어가는 것은 정리 사유와 모순. {@code ControlDto.AncestorSummary} +
 * {@link ControlDto.EvidenceTypeResponse} 는 호출처 다수 + shape 안정 → 재사용.
 * {@link ControlDto.DetailResponse} 는 호출처 0 (v15.3 ControlController 폐기 후)
 * dead code 로 잔존 → v15.7+ 정리.</p>
 */
@Getter
@Builder
@AllArgsConstructor
public class ControlNodeDetailDto {

    private Long id;
    private Long frameworkId;
    private String code;
    private String domain;
    private String name;
    private String description;
    private int evidenceTotal;
    private int evidenceCollected;
    private String status;
    private List<ControlDto.EvidenceTypeResponse> evidenceTypes;
    private String createdAt;
    private long pendingReviewCount;

    /**
     * spec §8.2 — depth=1 부터 leaf 직계 부모까지 순서대로. 빈 리스트 가능 (null 아님).
     */
    @Builder.Default
    private List<ControlDto.AncestorSummary> ancestors = new ArrayList<>();
}