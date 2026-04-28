package com.secuhub.domain.evidence.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Phase 5-14e — leaf 통제 코드 변경 시 사전 경고 다이얼로그 (5-14h FE) 가 호출하는
 * {@code GET /api/v1/controls/{id}/impact-summary} 응답 DTO.
 *
 * <p>합산 ({@code evidenceFileCount + jobCount + reviewCount}) 이 0 이면 5-14h 의 경고
 * 다이얼로그가 발동하지 않고 그대로 코드 변경. 0 이 아니면 경고 다이얼로그 노출.</p>
 *
 * <p>spec §3.3.1.5 정합. v14 결정: 사전 호출 (Q2-A from §3.3.1.10 — 반응형 호출).</p>
 *
 * <h3>5-14e 시점 의미</h3>
 * <ul>
 *   <li>{@code evidenceFileCount} — 이 leaf 통제에 매달린 모든 EvidenceFile 수
 *       (모든 review_status 포함, version 무관)</li>
 *   <li>{@code jobCount} — 이 leaf 통제 산하 EvidenceType 에 바인딩된 CollectionJob 수
 *       (실행 이력 무관)</li>
 *   <li>{@code reviewCount} — {@code reviewed_at IS NOT NULL} 인 EvidenceFile 수
 *       (관리자가 명시적으로 검토(approve/reject)한 횟수). pending / auto_approved 제외</li>
 * </ul>
 *
 * <h3>id 의미 — Phase 5-14e Q1 결정 (옵션 A)</h3>
 * <p>{@code id} 는 leaf control_node 의 id. 5-14h FE 가 그렇게 호출.</p>
 * <ul>
 *   <li>5-14e 시점 prod (V6 후): {@code evidence_types.control_id == leaf control_node.id}
 *       (V6 Step 3b 가 +1,000,000 offset 적용해서 이주). 자연 매칭.</li>
 *   <li>5-14e 시점 dev/test (V6 미실행): {@code Control} 과 {@code ControlNode} 가 별개
 *       sequence. 클라이언트가 ControlNode.id 로 호출하면 매칭 0 자연 결과 (5-14f 까지).
 *       의도된 동작.</li>
 * </ul>
 *
 * <p>본 DTO 는 외부 노출용 — 필드 추가 시 명시 (FE 5-14h 가 의존).</p>
 */
@Getter
@Builder
@AllArgsConstructor
public class ImpactSummaryDto {

    /** 이 leaf 통제에 매달린 모든 EvidenceFile 수 (모든 review_status). */
    private long evidenceFileCount;

    /** 이 leaf 통제 산하 EvidenceType 에 바인딩된 CollectionJob 수. */
    private long jobCount;

    /** 관리자가 명시 검토한 EvidenceFile 수 ({@code reviewed_at IS NOT NULL}). */
    private long reviewCount;
}