package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.TreeDto;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.NodeType;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

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
 *   <li><b>5-14c 카운트는 0 고정 (TODO 5-14f)</b> — leaf 의 evidenceTypeCount /
 *       pendingReviewCount 는 ControlNode.evidenceTypes OneToMany 매핑이
 *       5-14a 에서 미포함이고 EvidenceType.control 이 아직 controls 테이블을
 *       가리키므로, dev/test/prod 일관성 보장 위해 0 고정. 본격 집계는
 *       5-14f 에서 매핑 통합과 함께.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
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

    @Transactional(readOnly = true)
    public TreeDto.Response getTree(Long frameworkId) {
        Framework fw = frameworkRepository.findById(frameworkId)
                .orElseThrow(() -> new ResourceNotFoundException("프레임워크", frameworkId));

        List<ControlNode> nodes = controlNodeRepository.findByFrameworkIdOrderByDepthAscDisplayOrderAsc(frameworkId);

        List<TreeDto.NodeSummary> nodeSummaries = nodes.stream()
                .sorted(SPEC_ORDER)
                .map(this::toNodeSummary)
                .toList();

        return TreeDto.Response.builder()
                .framework(TreeDto.FrameworkSummary.from(fw))
                .nodes(nodeSummaries)
                .build();
    }

    private TreeDto.NodeSummary toNodeSummary(ControlNode cn) {
        if (cn.getNodeType() == NodeType.category) {
            return TreeDto.NodeSummary.fromCategory(cn);
        }
        // leaf — TODO 5-14f: 본격 집계 (현재는 0 고정)
        return TreeDto.NodeSummary.fromLeaf(cn, 0, 0L);
    }
}