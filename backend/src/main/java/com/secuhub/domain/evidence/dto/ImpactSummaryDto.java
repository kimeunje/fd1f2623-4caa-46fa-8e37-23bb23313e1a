package com.secuhub.domain.evidence.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Phase 5-14e (생성) / Phase 5-15a (확장) — leaf/hybrid 통제 코드 변경 사전 경고
 * 다이얼로그 (5-14h FE) 가 호출하는 {@code GET /api/v1/controls/{id}/impact-summary}
 * 응답 DTO.
 *
 * <h3>v15 Phase 5-15a — Hybrid 모델 분리 카운트</h3>
 *
 * <p>5-14e 시점: leaf-only 모델 (mutex). 카운트 3개 (file / job / review) 모두 leaf
 * 자체에 매달린 것만. 5-15a hybrid 모델 후: 한 노드가 자식 + 본인 증빙 동시 보유 가능
 * → "자체" 카운트 vs "자손" 카운트 분리 필요.</p>
 *
 * <p><b>Q1=C 결정 (BC 보존 + 명시)</b>:</p>
 * <ul>
 *   <li>기존 {@link #evidenceFileCount} / {@link #jobCount} / {@link #reviewCount}
 *       3 필드는 <b>own 의 alias</b> 로 보존 (5-14h FE 호출 호환). {@code @Deprecated}
 *       마킹.</li>
 *   <li>신규 {@code own*Count} / {@code descendant*Count} 6 필드 추가. 각각 본인만 /
 *       자손만 (본인 제외) 카운트. 합산 = 본인 + 자손 (subtree 전체) 은 호출 측 합.</li>
 *   <li>5-14h FE: 기존 필드 호출 → 자연 own 카운트 (mutex 데이터에서 자손 0 이라
 *       legacy 의미 보존). 새 hybrid UX 시 own/descendant 분리 표시.</li>
 * </ul>
 *
 * <h3>합산 카운트 정의 (5-14e 와 동일, 5-15a 에서 own/desc 분리만 추가)</h3>
 * <ul>
 *   <li>{@code *EvidenceFileCount} — 본 노드 (own) / 자손 (desc) 에 매달린 모든
 *       EvidenceFile 수 (모든 review_status 포함, version 무관)</li>
 *   <li>{@code *JobCount} — 본 노드 / 자손 산하 EvidenceType 에 바인딩된 CollectionJob
 *       수 (실행 이력 무관)</li>
 *   <li>{@code *ReviewCount} — {@code reviewed_at IS NOT NULL} 인 EvidenceFile 수
 *       (관리자가 명시 검토(approve/reject)한 횟수). pending / auto_approved 제외</li>
 * </ul>
 *
 * <p>spec §3.3.1.5 + §3.3.1.9 정합. 5-14h FE 의 코드 변경 차단 결정 (sum 0 면 무경고)
 * 은 own + descendant 합으로 적용 권장 (호출 측 결정).</p>
 *
 * <h3>id 의미 — 5-14e Q1=A 결정 그대로 보존</h3>
 * <p>{@code id} 는 leaf control_node 의 id (또는 v15 hybrid 시점에는 leaf/hybrid 모두
 * control_node.id). prod V6 후 자연 매칭. dev/test 도 5-14f 후 정상 매칭.</p>
 */
@Getter
@Builder
@AllArgsConstructor
public class ImpactSummaryDto {

    // ════════════════════════════════════════════════════════════════════
    // [legacy v14 5-14e] own 의 alias — 5-14h FE BC 보존, v15 5-15b 또는 후속에서 제거
    // ════════════════════════════════════════════════════════════════════

    /**
     * @deprecated v15 Phase 5-15a — {@link #ownEvidenceFileCount} 사용 권장.
     *     본 필드는 own 의 alias 로 보존 (5-14h FE BC).
     */
    @Deprecated(since = "v15 Phase 5-15a")
    private long evidenceFileCount;

    /**
     * @deprecated v15 Phase 5-15a — {@link #ownJobCount} 사용 권장.
     */
    @Deprecated(since = "v15 Phase 5-15a")
    private long jobCount;

    /**
     * @deprecated v15 Phase 5-15a — {@link #ownReviewCount} 사용 권장.
     */
    @Deprecated(since = "v15 Phase 5-15a")
    private long reviewCount;

    // ════════════════════════════════════════════════════════════════════
    // v15 Phase 5-15a — Hybrid 분리 카운트 (own = 본인만, descendant = 자손만 본인 제외)
    // ════════════════════════════════════════════════════════════════════

    /** 본 노드 자체에 매달린 EvidenceFile 수 (자손 제외). */
    private long ownEvidenceFileCount;

    /** 본 노드의 모든 자손 (본인 제외) 에 매달린 EvidenceFile 수. */
    private long descendantEvidenceFileCount;

    /** 본 노드 자체 산하 EvidenceType 에 바인딩된 CollectionJob 수. */
    private long ownJobCount;

    /** 본 노드의 모든 자손 산하 EvidenceType 에 바인딩된 CollectionJob 수. */
    private long descendantJobCount;

    /** 본 노드 자체에 매달린 EvidenceFile 중 명시 검토된 수. */
    private long ownReviewCount;

    /** 본 노드의 모든 자손에 매달린 EvidenceFile 중 명시 검토된 수. */
    private long descendantReviewCount;
}