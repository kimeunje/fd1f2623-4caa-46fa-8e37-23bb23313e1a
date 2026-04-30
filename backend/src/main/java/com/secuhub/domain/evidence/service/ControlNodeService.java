package com.secuhub.domain.evidence.service;

import com.secuhub.domain.evidence.dto.ImpactSummaryDto;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.repository.CollectionJobRepository;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 5-14e (생성) / Phase 5-15a (hybrid 분리 카운트) — control_node 단위 운영
 * 데이터 조회 서비스.
 *
 * <p>현재는 impact-summary 한 메서드만 보유. 5-14f / v15 에서 ControlNode 단위
 * 운영 메서드 (예: leaf 의 evidence_types 집계, 통제 단위 통계) 가 추가될 자리.</p>
 *
 * <p>5-14a 의 {@link com.secuhub.domain.evidence.entity.ControlNode} 엔티티 +
 * 5-14c 의 {@link ControlNodeRepository} 와 별개. impact-summary 는 leaf 단위 운영
 * 데이터 (evidence_files / collection_jobs / review 이력) 카운트 만 다루므로 5-14e
 * 시점에는 {@link ControlNodeRepository} 의존성 없었으나, v15 Phase 5-15a 에서 자손
 * id list 조회 용도로 추가됨.</p>
 *
 * <h3>v15 Phase 5-15a — Hybrid 분리 카운트</h3>
 * <p>{@link #getImpactSummary(Long)} 가 own / descendant 6 카운트 분리 응답.
 * 자손 id 는 {@link ControlNodeRepository#findAllDescendants(Long)} 의 재귀 CTE
 * 결과 사용 (5-14a 패턴 재활용, H2/MariaDB 양쪽 안전).</p>
 *
 * <p>spec §3.3.1.5 + §3.3.1.9 정합. 응답 shape: {@link ImpactSummaryDto} —
 * 6 신규 필드 (own/desc) + 3 legacy alias.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlNodeService {

    private final EvidenceFileRepository evidenceFileRepository;
    private final CollectionJobRepository collectionJobRepository;
    private final ControlNodeRepository controlNodeRepository;       // ← v15 5-15a 추가

    /**
     * leaf / hybrid 노드 코드 변경 사전 경고 다이얼로그 (5-14h FE) 가 호출.
     *
     * <p>입력 {@code controlId} 는 leaf control_node.id (5-14e Q1=A 결정 — spec §3.3.1.5).
     * 5-14h FE 가 ControlNode.id 로 호출. service 는 받은 id 로 evidence_files /
     * collection_jobs 의 control 매칭 카운트만 리턴. ControlNode 존재 검증 없음
     * — 매칭 0 면 모두 0 리턴 (404 아님). 단순함이 핵심 (5-14h FE 가 leaf 만 호출).</p>
     *
     * <p>v15 Phase 5-15a (hybrid): own (본인) + descendant (자손, 본인 제외) 분리 카운트.
     * 호출 측 (FE) 은 합산 / 분리 표시 자유 결정. 5-14h 의 합산 임계값 (= 0 면 무경고)
     * 은 own + descendant 합 사용 권장.</p>
     *
     * <h3>알고리즘</h3>
     * <ol>
     *   <li>본인 (own) 카운트 3개 — 5-14e 패턴 그대로 ({@link EvidenceFileRepository#countByControlId},
     *       {@link EvidenceFileRepository#countReviewedByControlId},
     *       {@link CollectionJobRepository#countByControlId})</li>
     *   <li>자손 id list 조회 ({@link ControlNodeRepository#findAllDescendants}) —
     *       재귀 CTE, 본인 제외</li>
     *   <li>자손 list 비어있으면 descendant 3개 모두 0 (IN 절 빈 list 호출 회피)</li>
     *   <li>자손 list 비어있지 않으면 IN 절로 카운트 3개 집계
     *       ({@link EvidenceFileRepository#countByControlIds},
     *       {@link EvidenceFileRepository#countReviewedByControlIds},
     *       {@link CollectionJobRepository#countByControlIds})</li>
     *   <li>legacy 3 필드 = own 의 alias 로 채움 (FE BC 보존)</li>
     * </ol>
     *
     * <p>본인 자체가 존재하지 않으면 모든 카운트 0 (5-14e 의 단순 결정 그대로 — 404 아님).</p>
     *
     * <h3>5-14e 시점 환경별 동작 (5-14f 후 자연 정상화 — v15 5-15a 도 동일)</h3>
     * <ul>
     *   <li><b>prod V6 후</b>: {@code evidence_types.control_id == leaf control_node.id}
     *       (V6 Step 3b 이주). 자연 매칭, 의미 있는 카운트.</li>
     *   <li><b>dev/test (V6 미실행)</b>: 5-14f 후 ControlNode.id 직접 매칭, 자연 작동.</li>
     * </ul>
     *
     * <p>{@code reviewCount} 의 의미 (Q4 결정): {@code reviewed_at IS NOT NULL}
     * — 관리자가 명시 검토한 횟수. pending / auto_approved 제외, approved / rejected 포함.
     * spec §3.3.1.5 의 "검토 이력 N건" 표현 정합.</p>
     *
     * @param controlId leaf 또는 hybrid control_node.id (외부 클라이언트가 호출하는 식별자)
     * @return own / descendant / legacy alias 9 필드 채워진 DTO
     */
    @Transactional(readOnly = true)
    public ImpactSummaryDto getImpactSummary(Long controlId) {
        // ── 본인 (own) ───────────────────────────────────────────────
        long ownFiles   = evidenceFileRepository.countByControlId(controlId);
        long ownJobs    = collectionJobRepository.countByControlId(controlId);
        long ownReviews = evidenceFileRepository.countReviewedByControlId(controlId);

        // ── 자손 (descendant, 본인 제외) ─────────────────────────────
        long descFiles = 0L, descJobs = 0L, descReviews = 0L;
        List<ControlNode> descendants = controlNodeRepository.findAllDescendants(controlId);
        if (!descendants.isEmpty()) {
            List<Long> descIds = descendants.stream().map(ControlNode::getId).toList();
            descFiles   = evidenceFileRepository.countByControlIds(descIds);
            descJobs    = collectionJobRepository.countByControlIds(descIds);
            descReviews = evidenceFileRepository.countReviewedByControlIds(descIds);
        }

        return ImpactSummaryDto.builder()
                // legacy alias (= own, 5-14h FE BC 보존)
                .evidenceFileCount(ownFiles)
                .jobCount(ownJobs)
                .reviewCount(ownReviews)
                // v15 own
                .ownEvidenceFileCount(ownFiles)
                .ownJobCount(ownJobs)
                .ownReviewCount(ownReviews)
                // v15 descendant
                .descendantEvidenceFileCount(descFiles)
                .descendantJobCount(descJobs)
                .descendantReviewCount(descReviews)
                .build();
    }
}