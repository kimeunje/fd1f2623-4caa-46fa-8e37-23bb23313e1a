package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.TreeDto;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.NodeType;
import com.secuhub.domain.evidence.entity.ReviewStatus;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 5-14c — GET /api/v1/frameworks/{id}/tree 서비스.
 *
 * <p>spec §3.3.1.4 의 응답 페이로드 + 정렬 (depth ASC, parent.id ASC NULL FIRST,
 * displayOrder ASC) 을 보장한다.</p>
 *
 * <h3>설계 결정</h3>
 * <ul>
 *   <li><b>parent fetch</b> — Repository 의 {@code findByFrameworkIdOrderByDepthAscDisplayOrderAsc}
 *       가 v14.3 (5-14c) 부터 {@code LEFT JOIN FETCH cn.parent} 로 hydrate.
 *       NodeSummary 매핑 시 {@code cn.getParent().getId()} 호출의 lazy load
 *       N+1 차단.</li>
 *   <li><b>JVM-side 정렬</b> — Repository 의 ORDER BY 는 (depth, displayOrder)
 *       2단. spec §3.3.1.4 의 (depth, parent.id NULL FIRST, displayOrder) 3단
 *       정렬 중 "parent.id" 단계는 본 서비스가 JVM Comparator 로 추가 적용 —
 *       JPQL ORDER BY 의 NULL 처리 환경 의존성 회피. 100~수백 노드 규모에서
 *       비용 무시 가능.</li>
 *   <li><b>v14.6 (5-14f) leaf 두 카운트 본격 집계</b> — leaf 의 evidenceTypeCount /
 *       pendingReviewCount 를 Framework 단위 1회 쿼리로 집계 후 Map 빌드.
 *       N+1 회피. 5-14c 의 "0 고정 TODO" 해결.</li>
 *   <li><b>v14.7 (5-14g β) leaf collectedCount 추가 집계</b> — ControlsView 트리 본문의
 *       6컬럼 진행바 ({@code N/M}) 를 위해 {@code collectedCount} 추가. 정의: leaf 에
 *       매달린 evidence_types 중 evidence_files 가 1개 이상 있는 type 의 distinct 수.
 *       5-14f 와 동일 패턴 (Framework 단위 single query + Map building, N+1 회피).</li>
 *   <li><b>v15.2 (5-15a 후속-1) 모든 노드 카운트 노출</b> — hybrid 모델 정합.
 *       {@code toNodeSummary} 의 nodeType 분기 폐기 — 모든 노드 (category + leaf)
 *       에 own 카운트 (Q1=A own only) 채움. Repository 쿼리 변경 0 (5-14f/5-14g 의
 *       explicit JOIN 패턴이 leaf/category 구분 없이 own 카운트 자연 집계).
 *       Q2=A: Map miss 시 0 명시 (NULL 없음).</li>
 *   <li><b>v15.7 (5-15c) Repository 호출 명명 갱신</b> — Q5=A 정합.
 *       {@code countGroupByControlIdInFramework} → {@code countGroupByControlNodeIdInFramework},
 *       {@code countPendingGroupByControlIdInFramework} → {@code countPendingGroupByControlNodeIdInFramework},
 *       {@code countCollectedGroupByControlIdInFramework} → {@code countCollectedGroupByControlNodeIdInFramework}.
 *       JPQL 자체는 Repository 안에서 {@code et.controlNode} 로 정합 (Q1=B).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TreeService {

    /**
     * spec §3.3.1.4 의 정렬 — depth ASC, parent.id ASC (NULL FIRST), displayOrder ASC.
     * 부모가 자식보다 먼저 등장하므로 클라이언트 트리 reconstruction 비용 최소화.
     */
    private static final Comparator<ControlNode> SPEC_ORDER = Comparator
            .comparingInt(ControlNode::getDepth)
            .thenComparing(
                    cn -> cn.getParent() == null ? null : cn.getParent().getId(),
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingInt(ControlNode::getDisplayOrder);

    private final FrameworkRepository frameworkRepository;
    private final ControlNodeRepository controlNodeRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;     // ← 5-14f 추가
    private final EvidenceFileRepository evidenceFileRepository;     // ← 5-14f 추가

    public TreeDto.Response getTree(Long frameworkId) {
        Framework framework = frameworkRepository.findById(frameworkId)
                .orElseThrow(() -> new ResourceNotFoundException("프레임워크", frameworkId));

        List<ControlNode> nodes = controlNodeRepository
                .findByFrameworkIdOrderByDepthAscDisplayOrderAsc(frameworkId);

        // JVM-side Comparator 정렬 — SPEC_ORDER 재사용
        List<ControlNode> sorted = nodes.stream()
                .sorted(SPEC_ORDER)
                .toList();

        // ─── v14 Phase 5-14f — leaf 두 카운트 본격 집계 (5-14c 의 0 고정 변경) ───
        // Framework 단위 1회 쿼리 후 Map 빌드 (N+1 회피, explicit JOIN 으로 Hibernate 6 안전)
        // v15.7: Repository 메서드명 갱신 (Q5=A) — countGroupByControlNodeIdInFramework
        Map<Long, Long> evidenceTypeCounts = new HashMap<>();
        for (Object[] row : evidenceTypeRepository.countGroupByControlNodeIdInFramework(frameworkId)) {
            evidenceTypeCounts.put((Long) row[0], (Long) row[1]);
        }
        Map<Long, Long> pendingReviewCounts = new HashMap<>();
        // 5-14f 안전: ReviewStatus.pending 을 parameter 로 전달 (fully-qualified enum literal 의
        //            Hibernate 6 SQM path 오인 회피)
        // v15.7: 메서드명 countPendingGroupByControlNodeIdInFramework
        for (Object[] row : evidenceFileRepository.countPendingGroupByControlNodeIdInFramework(
                frameworkId, ReviewStatus.pending)) {
            pendingReviewCounts.put((Long) row[0], (Long) row[1]);
        }

        // ─── v14 Phase 5-14g (β) — leaf collectedCount 본격 집계 (진행바 N/M 의 N) ───
        // 정의: leaf 에 매달린 evidence_types 중 evidence_files 가 1개 이상 있는 type 의
        //      distinct 수. ControlsView §3.3 의 6컬럼 진행바 + "완료/진행중" 상태 derive 용.
        // v15.7: 메서드명 countCollectedGroupByControlNodeIdInFramework
        Map<Long, Long> collectedCounts = new HashMap<>();
        for (Object[] row : evidenceTypeRepository.countCollectedGroupByControlNodeIdInFramework(frameworkId)) {
            collectedCounts.put((Long) row[0], (Long) row[1]);
        }
        // ────────────────────────────────────────────────────────────────────

        // toNodeSummary 호출 시 세 Map 함께 전달
        List<TreeDto.NodeSummary> nodeSummaries = sorted.stream()
                .map(n -> toNodeSummary(n, evidenceTypeCounts, collectedCounts, pendingReviewCounts))
                .toList();

        return TreeDto.Response.builder()
                .framework(TreeDto.FrameworkSummary.builder()
                        .id(framework.getId())
                        .name(framework.getName())
                        .version(framework.getVersion())
                        .build())
                .nodes(nodeSummaries)
                .build();
    }

    /**
     * v14 Phase 5-14f — Map 으로 카운트 lookup.
     * v14 Phase 5-14g (β) — collectedCounts 매개변수 추가 (시그니처 변경).
     * v15.2 5-15a 후속-1 — hybrid: 모든 노드 (category + leaf) 에 own 카운트 노출
     *                     (Q1=A own only, Q2=A Map miss 시 0).
     */
    private TreeDto.NodeSummary toNodeSummary(ControlNode node,
                                              Map<Long, Long> evidenceTypeCounts,
                                              Map<Long, Long> collectedCounts,
                                              Map<Long, Long> pendingReviewCounts) {
        // v15.2 5-15a 후속-1 — Map 에서 카운트 lookup (없으면 0 default).
        // Repository 의 explicit JOIN (et.controlNode cn) 이 leaf/category 구분 없이
        // own evidence_types 자연 집계. nodeType 분기 폐기.
        long etCount = evidenceTypeCounts.getOrDefault(node.getId(), 0L);
        long ccCount = collectedCounts.getOrDefault(node.getId(), 0L);
        long prCount = pendingReviewCounts.getOrDefault(node.getId(), 0L);

        return TreeDto.NodeSummary.builder()
                .id(node.getId())
                .parentId(node.getParent() != null ? node.getParent().getId() : null)
                .nodeType(node.getNodeType().name())
                .code(node.getCode())
                .name(node.getName())
                .description(node.getDescription())
                .displayOrder(node.getDisplayOrder())
                .depth(node.getDepth())
                .evidenceTypeCount((int) etCount)
                .collectedCount((int) ccCount)
                .pendingReviewCount(prCount)
                .descriptionUpdatedByName(node.getDescriptionUpdatedByName())  // v19.26
                .descriptionUpdatedAt(node.getDescriptionUpdatedAt())          // v19.26
                .build();
    }
}