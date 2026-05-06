package com.secuhub.domain.dashboard.service;

import com.secuhub.domain.dashboard.dto.AdminDashboardSummaryDto;
import com.secuhub.domain.dashboard.dto.AdminDashboardSummaryDto.FrameworkProgress;
import com.secuhub.domain.dashboard.dto.AdminDashboardSummaryDto.Kpi;
import com.secuhub.domain.dashboard.dto.AdminDashboardSummaryDto.PendingApproval;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.EvidenceFile;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.FrameworkStatus;
import com.secuhub.domain.evidence.entity.ReviewStatus;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 관리자 대시보드 데이터 집계 service (Phase v16.4a 신규, v16.4a-fix 타입 정합).
 *
 * <p>spec §3.8 정합. 단일 호출 ({@link #getAdminSummary()}) 로 KPI + 승인 대기 목록 +
 * Framework 진척 모두 집계.</p>
 *
 * <h3>v16.4a-fix 변경</h3>
 * <ul>
 *   <li>{@code Framework.Status.active} (nested) → {@link FrameworkStatus#active}
 *       (top-level) 정정. Framework entity 가 {@code @Enumerated} +
 *       {@code FrameworkStatus} top-level 을 사용하므로 정합.</li>
 * </ul>
 *
 * <h3>쿼리 효율</h3>
 * <ul>
 *   <li>KPI: 1 쿼리 ({@code countByReviewStatus})</li>
 *   <li>Pending top 10: 1 쿼리 (JOIN FETCH 로 evidenceType + controlNode + framework
 *       + uploadedBy 일괄 hydrate, N+1 차단)</li>
 *   <li>ancestors path: 별도 쿼리 1회 ({@code byId 맵} 으로 in-memory traversal,
 *       FrameworkExportService 와 같은 패턴)</li>
 *   <li>Framework progresses: framework 마다 evidence_types loop — 1 + 2N 쿼리.
 *       Framework 수가 작은 운영 환경 (typical &lt; 20) 가정 충분. 향후 운영 피드백
 *       에서 N+1 식별 시 단일 GROUP BY 쿼리로 최적화 (별도 phase)</li>
 * </ul>
 *
 * <h3>딥링크 형식 (spec §3.7.2 / §8.1 정합)</h3>
 * <p>{@code /controls/{frameworkId}/{controlNodeId}/evidence-types/{evidenceTypeId}}
 * — FE router 의 {@code evidence-type-detail} 라우트.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 승인 대기 목록 표시 limit (spec §3.8 의 "승인 대기 목록"). */
    private static final int PENDING_LIST_LIMIT = 10;

    private final EvidenceFileRepository evidenceFileRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final ControlNodeRepository controlNodeRepository;
    private final FrameworkRepository frameworkRepository;

    /**
     * 관리자 대시보드 요약 집계.
     *
     * @return KPI + 승인 대기 목록 + Framework 진척 통합 응답.
     */
    @Transactional(readOnly = true)
    public AdminDashboardSummaryDto getAdminSummary() {
        return AdminDashboardSummaryDto.builder()
                .kpi(buildKpi())
                .pendingApprovals(buildPendingApprovals())
                .frameworkProgresses(buildFrameworkProgresses())
                .build();
    }

    // -------------------------------------------------------------------
    // 1. KPI
    // -------------------------------------------------------------------

    private Kpi buildKpi() {
        long pendingCount = evidenceFileRepository.countByReviewStatus(ReviewStatus.pending);
        return Kpi.builder()
                .pendingApprovalCount(pendingCount)
                .build();
    }

    // -------------------------------------------------------------------
    // 2. Pending top 10 — 딥링크 + ancestors path 포함
    // -------------------------------------------------------------------

    private List<PendingApproval> buildPendingApprovals() {
        List<EvidenceFile> pendingFiles = evidenceFileRepository.findTop10PendingForDashboard();

        if (pendingFiles.isEmpty()) {
            return Collections.emptyList();
        }

        // ancestors traversal 을 위한 byId 맵 — 한 번만 framework_id 별 노드 모두 조회
        // (FrameworkExportService 와 같은 패턴, in-memory traversal 로 N+1 차단)
        List<Long> frameworkIds = pendingFiles.stream()
                .map(ef -> ef.getEvidenceType().getControlNode().getFramework().getId())
                .distinct()
                .toList();

        Map<Long, ControlNode> nodesById = controlNodeRepository
                .findByFrameworkIdInOrderByDepthAscDisplayOrderAsc(frameworkIds)
                .stream()
                .collect(Collectors.toMap(ControlNode::getId, n -> n));

        return pendingFiles.stream()
                .map(ef -> toPendingApprovalDto(ef, nodesById))
                .toList();
    }

    private PendingApproval toPendingApprovalDto(EvidenceFile ef, Map<Long, ControlNode> nodesById) {
        EvidenceType et = ef.getEvidenceType();
        ControlNode leaf = et.getControlNode();
        Framework fw = leaf.getFramework();

        // ancestors path 빌드 (root 부터 leaf 까지 code 를 " > " 로 연결)
        List<ControlNode> ancestors = buildAncestors(leaf, nodesById);
        List<String> pathCodes = new ArrayList<>(ancestors.size() + 1);
        for (ControlNode a : ancestors) pathCodes.add(safeCode(a));
        pathCodes.add(safeCode(leaf));
        String controlPath = String.join(" > ", pathCodes);

        // 딥링크 URL 빌드 (spec §3.7.2 / §8.1 정합)
        String deepLink = String.format("/controls/%d/%d/evidence-types/%d",
                fw.getId(), leaf.getId(), et.getId());

        return PendingApproval.builder()
                .fileId(ef.getId())
                .evidenceTypeId(et.getId())
                .evidenceTypeName(et.getName())
                .uploaderName(ef.getUploadedBy() != null ? ef.getUploadedBy().getName() : null)
                .uploaderTeam(ef.getUploadedBy() != null ? ef.getUploadedBy().getTeam() : null)
                .submittedAt(ef.getCreatedAt())
                .frameworkId(fw.getId())
                .frameworkName(fw.getName())
                .controlNodeId(leaf.getId())
                .controlPath(controlPath)
                .deepLinkUrl(deepLink)
                .build();
    }

    /**
     * 노드의 ancestors 를 root 부터 직계 부모 순서로 반환 (자기 자신 미포함).
     * FrameworkExportService.buildAncestors 와 같은 패턴.
     */
    private List<ControlNode> buildAncestors(ControlNode node, Map<Long, ControlNode> byId) {
        List<ControlNode> chain = new ArrayList<>();
        ControlNode current = node.getParent();
        while (current != null) {
            // byId 에서 in-memory 조회 (lazy parent.parent.parent 재호출 방지)
            ControlNode resolved = byId.getOrDefault(current.getId(), current);
            chain.add(resolved);
            current = resolved.getParent();
        }
        Collections.reverse(chain);
        return chain;
    }

    private String safeCode(ControlNode node) {
        return node.getCode() != null ? node.getCode() : "";
    }

    // -------------------------------------------------------------------
    // 3. Framework progresses
    // -------------------------------------------------------------------

    private List<FrameworkProgress> buildFrameworkProgresses() {
        // v16.4a-fix: Framework.Status.active → FrameworkStatus.active (top-level enum)
        List<Framework> activeFrameworks = frameworkRepository.findByStatusOrderByCreatedAtDesc(
                FrameworkStatus.active);

        return activeFrameworks.stream()
                .map(this::computeProgress)
                .toList();
    }

    private FrameworkProgress computeProgress(Framework fw) {
        // 해당 framework 의 모든 evidence_types id 조회
        List<Long> evidenceTypeIds = evidenceTypeRepository.findIdsByFrameworkId(fw.getId());
        long total = evidenceTypeIds.size();

        if (total == 0) {
            return FrameworkProgress.builder()
                    .frameworkId(fw.getId())
                    .frameworkName(fw.getName())
                    .totalEvidenceTypes(0L)
                    .collectedCount(0L)
                    .pendingReviewCount(0L)
                    .progressRatio(0.0)
                    .build();
        }

        // 수집 완료 = evidence_types 중 1+ approved/auto_approved 가진 것의 수
        long collected = evidenceFileRepository.countDistinctEvidenceTypesByCollectedStatus(evidenceTypeIds);

        // pending = evidence_types 중 1+ pending 가진 것의 수
        long pending = evidenceFileRepository.countDistinctEvidenceTypesByPendingStatus(evidenceTypeIds);

        double ratio = (double) collected / total;

        return FrameworkProgress.builder()
                .frameworkId(fw.getId())
                .frameworkName(fw.getName())
                .totalEvidenceTypes(total)
                .collectedCount(collected)
                .pendingReviewCount(pending)
                .progressRatio(ratio)
                .build();
    }
}