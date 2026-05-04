package com.secuhub.domain.evidence.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Phase 5-14e (생성) / Phase 5-15a (확장) / Phase 5-15c v15.7 (legacy alias 제거) —
 * leaf/hybrid 통제 코드 변경 사전 경고 다이얼로그 (5-14h FE) 가 호출하는
 * {@code GET /api/v1/control-nodes/{id}/impact-summary} 응답 DTO.
 *
 * <h3>v15 Phase 5-15a — Hybrid 모델 분리 카운트</h3>
 *
 * <p>5-14e 시점: leaf-only 모델 (mutex). 카운트 3개 (file / job / review) 모두 leaf
 * 자체에 매달린 것만. 5-15a hybrid 모델 후: 한 노드가 자식 + 본인 증빙 동시 보유 가능
 * → "자체" 카운트 vs "자손" 카운트 분리 필요. own / descendant 6 필드 + legacy alias
 * 3 필드 (BC 보존) 9 필드 응답 구조로 도입.</p>
 *
 * <h3>v15 Phase 5-15c (v15.7) — legacy alias 3 필드 제거 (Q2=A)</h3>
 *
 * <p>v15.0 ~ v15.6 동안 BC 보존용 legacy alias 3 필드 ({@code evidenceFileCount} /
 * {@code jobCount} / {@code reviewCount}, 모두 own 의 alias) 가 {@code @Deprecated}
 * 마킹 상태로 잔존. v15.6 의 호출처 grep 결과 caller = 0 dead 확인 → v15.7 일괄 제거.
 * FE 측 {@code ImpactSummary} type 도 6 필드로 갱신 (types/evidence.ts).</p>
 *
 * <h3>응답 shape (v15.7)</h3>
 * <pre>
 * {
 *   "ownEvidenceFileCount": 2,       // 본 노드 자체 evidence_files
 *   "ownJobCount": 1,                // 본 노드 evidence_types 산하 CollectionJob
 *   "ownReviewCount": 1,             // 본 노드 evidence_files 중 reviewed_at NOT NULL
 *   "descendantEvidenceFileCount": 5,
 *   "descendantJobCount": 3,
 *   "descendantReviewCount": 2
 * }
 * </pre>
 *
 * <h3>합산 카운트 정의</h3>
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
 * 은 own + descendant 합으로 적용 (호출 측 결정).</p>
 *
 * <h3>id 의미 — 5-14e Q1=A 결정 그대로 보존</h3>
 * <p>{@code id} 는 leaf control_node 의 id (또는 v15 hybrid 시점에는 leaf/hybrid 모두
 * control_node.id). v15.6 endpoint URL 이전 후 path variable 명도 {@code id} (= node id).</p>
 */
@Getter
@Builder
@AllArgsConstructor
public class ImpactSummaryDto {

    // ════════════════════════════════════════════════════════════════════
    // v15 Phase 5-15a — Hybrid 분리 카운트 (own = 본인만, descendant = 자손만 본인 제외)
    // v15 Phase 5-15c (v15.7) — legacy alias 3 필드 제거. 본 6 필드만 노출.
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