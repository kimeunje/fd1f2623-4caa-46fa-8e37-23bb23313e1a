package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.ControlDto;
import com.secuhub.domain.evidence.dto.ControlNodeDetailDto;
import com.secuhub.domain.evidence.dto.EvidenceFileDto;
import com.secuhub.domain.evidence.dto.ImpactSummaryDto;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.EvidenceFile;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.ReviewStatus;
import com.secuhub.domain.evidence.repository.CollectionJobRepository;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Phase 5-14e (생성) / Phase 5-15a (hybrid 분리 카운트) /
 * Phase 5-15b Round 3 (v15.6 — leaf detail + evidence-types 분리 응답 추가) /
 * Phase 5-15c (v15.7 — Q2=A legacy alias 제거 + Q5/Q6=A 명명 정리) —
 * control_node 단위 운영 데이터 조회 서비스.
 *
 * <p>v15.7 시점 3 메서드 보유:</p>
 * <ul>
 *   <li>{@link #getControlNodeDetail(Long)} — v15.6 신규 (옛 ControlController.getDetail
 *       의 의미 흡수)</li>
 *   <li>{@link #getEvidenceTypes(Long)} — v15.6 신규 (leaf detail 의 evidenceTypes[]
 *       부분만 분리 응답)</li>
 *   <li>{@link #getImpactSummary(Long)} — v14.5 도입 / v15.0 hybrid 확장 / v15.7 legacy
 *       alias 제거 + 파라미터 rename</li>
 * </ul>
 *
 * <h3>v15.7 변경</h3>
 * <ul>
 *   <li>{@link #getImpactSummary(Long)} 의 builder 안 legacy alias 3 줄 제거 (Q2=A,
 *       호출처 0 dead 확인 후 정리). own 3 + descendant 3 만 빌드.</li>
 *   <li>Repository 호출 측 메서드명 일괄 갱신 (Q5=A, Repository 메서드 rename 정합):
 *       {@code countByControlId} → {@code countByControlNodeId} 등</li>
 *   <li>{@link #getImpactSummary(Long)} 의 파라미터명 {@code controlId} → {@code nodeId}
 *       (Q6=A, service-layer 명명을 phase 의도에 정합 — wire shape "nodeId" 와 같은 명명)</li>
 * </ul>
 *
 * <h3>v15.6 신규 메서드의 데이터 모델 정합</h3>
 * <p>{@code getControlNodeDetail} 은 leaf {@link ControlNode} 의 in-memory
 * evidenceTypes / evidenceFiles 순회로 카운트 + DTO 빌드. Single leaf 조회라
 * N+1 영향 미미 (typical N=1~5). N+1 최적화는 후속 phase 검토.</p>
 *
 * <p>{@code @Transactional(readOnly = true)} 안에서 LAZY 로딩이 자연 작동
 * (ControlNode.evidenceTypes / EvidenceType.evidenceFiles).</p>
 *
 * <h3>spec §3.3.1.5 + §3.3.1.9 + §8.2 정합</h3>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlNodeService {

    private final EvidenceFileRepository evidenceFileRepository;
    private final CollectionJobRepository collectionJobRepository;
    private final ControlNodeRepository controlNodeRepository;       // ← v15 5-15a 추가

    // ════════════════════════════════════════════════════════════════════
    // v15.6 신규 — leaf detail 응답 (옛 ControlController.getDetail 의미 흡수)
    // ════════════════════════════════════════════════════════════════════

    /**
     * leaf {@link ControlNode} 상세 응답.
     *
     * <p>spec §8.2 정합 — ancestors[] (depth=1 부터 leaf 직계 부모까지, leaf 미포함) +
     * evidenceTypes[] + 카운트 4 (evidenceTotal / evidenceCollected /
     * pendingReviewCount / status derive).</p>
     *
     * <p>v15.3 폐기된 {@link ControlDto.DetailResponse} 의 응답 shape 을 본 메서드가
     * {@link ControlNodeDetailDto} 로 신규 빌드. FE {@code ControlDetail} 타입
     * (types/evidence.ts) 정합.</p>
     *
     * <h3>node_type 검증</h3>
     * <p>현재는 control / category 모두 허용 (hybrid 모델 v15.0 결정 정합 — 모든 노드가
     * evidence_types 보유 가능). 호출 측 (FE ControlsView 의 leaf 클릭) 이 leaf 만
     * 호출하므로 별도 422 검증 없음.</p>
     *
     * @param id control_node.id
     * @return leaf detail DTO
     * @throws ResourceNotFoundException id 부재 시
     */
    @Transactional(readOnly = true)
    public ControlNodeDetailDto getControlNodeDetail(Long id) {
        ControlNode node = controlNodeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("통제 노드", id));

        // ── ancestors[] 빌드: depth=1 부터 leaf 직계 부모까지 ──────────
        // (leaf 자기 자신 미포함, 빈 리스트는 depth=1 leaf)
        LinkedList<ControlDto.AncestorSummary> stack = new LinkedList<>();
        ControlNode cur = node.getParent();
        while (cur != null) {
            stack.addFirst(ControlDto.AncestorSummary.builder()
                    .id(cur.getId())
                    .code(cur.getCode())
                    .name(cur.getName())
                    .build());
            cur = cur.getParent();
        }
        List<ControlDto.AncestorSummary> ancestors = new ArrayList<>(stack);

        // domain = depth=1 ancestor name (있으면) — controls.domain 컬럼 폐기 후
        // 호환 필드 (v14 5-14f 부터 ControlDto.Response.from 패턴 정합)
        String domain = ancestors.isEmpty() ? null : ancestors.get(0).getName();

        // ── evidenceTypes[] + 카운트 ─────────────────────────────────
        List<ControlDto.EvidenceTypeResponse> etResponses =
                buildEvidenceTypeResponses(node);

        int total = etResponses.size();
        int collected = (int) etResponses.stream().filter(ControlDto.EvidenceTypeResponse::isCollected).count();
        long pendingReviewCount = countPendingReviews(node);

        String status = deriveStatus(total, collected);

        return ControlNodeDetailDto.builder()
                .id(node.getId())
                .frameworkId(node.getFramework() != null ? node.getFramework().getId() : null)
                .code(node.getCode())
                .domain(domain)
                .name(node.getName())
                .description(node.getDescription())
                .evidenceTotal(total)
                .evidenceCollected(collected)
                .status(status)
                .createdAt(node.getCreatedAt() != null ? node.getCreatedAt().toString() : null)
                .pendingReviewCount(pendingReviewCount)
                .ancestors(ancestors)
                .evidenceTypes(etResponses)
                .build();
    }

    /**
     * leaf {@link ControlNode} 의 evidence-types 만 분리 응답.
     *
     * <p>{@link #getControlNodeDetail(Long)} 의 evidenceTypes[] 부분과 동일 shape /
     * 동일 빌드 로직 — 본 메서드는 그 부분만 추출. evidence 운영 화면이 detail 전체를
     * 다시 안 받고 evidence-types 만 갱신하고 싶을 때 사용.</p>
     *
     * @param id control_node.id
     * @return evidence-types 리스트
     * @throws ResourceNotFoundException id 부재 시
     */
    @Transactional(readOnly = true)
    public List<ControlDto.EvidenceTypeResponse> getEvidenceTypes(Long id) {
        ControlNode node = controlNodeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("통제 노드", id));
        return buildEvidenceTypeResponses(node);
    }

    // ────────────────────────────────────────────────────────────────────
    // 내부 헬퍼 — evidenceTypes[] 빌드 / pending 카운트 / status derive
    // ────────────────────────────────────────────────────────────────────

    /**
     * ControlNode 의 evidence_types 를 {@link ControlDto.EvidenceTypeResponse} 리스트로
     * 변환. files[] 는 {@link EvidenceFileDto.Response} 로 변환.
     *
     * <p>Single leaf 조회의 LAZY N+1 은 운영 영향 미미 (N=1~5 전형). 대규모 leaf 가
     * 발견되면 후속 phase 에서 EvidenceTypeRepository batch 쿼리로 전환 검토.</p>
     */
    private List<ControlDto.EvidenceTypeResponse> buildEvidenceTypeResponses(ControlNode node) {
        List<EvidenceType> evidenceTypes = node.getEvidenceTypes();
        if (evidenceTypes == null || evidenceTypes.isEmpty()) {
            return new ArrayList<>();
        }
        List<ControlDto.EvidenceTypeResponse> result = new ArrayList<>(evidenceTypes.size());
        for (EvidenceType et : evidenceTypes) {
            List<EvidenceFile> files = et.getEvidenceFiles();
            List<EvidenceFileDto.Response> fileResponses = (files == null)
                    ? new ArrayList<>()
                    : files.stream()
                            .map(EvidenceFileDto.Response::from)
                            .toList();
            result.add(ControlDto.EvidenceTypeResponse.from(et, fileResponses));
        }
        return result;
    }

    /**
     * leaf 의 모든 evidence_files 중 review_status=pending 카운트.
     *
     * <p>Repository 의 batch 메서드 ({@link EvidenceFileRepository#countByControlNodeId})
     * 가 own files 전체를 카운트하나, pending 만 분리한 메서드가 없으므로 in-memory
     * 순회. Single leaf 의 files 수는 typical 작음 (N=수~수십).</p>
     *
     * <p>대규모 leaf 발견 시 EvidenceFileRepository 에 countPendingByControlNodeId 메서드
     * 추가 검토 (후속 phase).</p>
     */
    private long countPendingReviews(ControlNode node) {
        long count = 0L;
        List<EvidenceType> evidenceTypes = node.getEvidenceTypes();
        if (evidenceTypes == null) return 0L;
        for (EvidenceType et : evidenceTypes) {
            List<EvidenceFile> files = et.getEvidenceFiles();
            if (files == null) continue;
            for (EvidenceFile f : files) {
                if (f.getReviewStatus() == ReviewStatus.pending) count++;
            }
        }
        return count;
    }

    /**
     * total / collected 로 ControlsView 트리 본문의 진행 상태 derive.
     * v14 ControlDto.Response.from 의 패턴 그대로.
     */
    private String deriveStatus(int total, int collected) {
        if (total == 0) return "미수집";
        if (collected >= total) return "완료";
        if (collected > 0) return "진행중";
        return "미수집";
    }

    // ════════════════════════════════════════════════════════════════════
    // v14.5 도입 / v15.0 hybrid 확장 / v15.7 legacy alias 제거 + 파라미터 rename
    // ════════════════════════════════════════════════════════════════════

    /**
     * leaf / hybrid 노드 코드 변경 사전 경고 다이얼로그 (5-14h FE) 가 호출.
     *
     * <p>입력 {@code nodeId} 는 leaf control_node.id (5-14e Q1=A 결정 — spec §3.3.1.5).
     * 5-14h FE 가 ControlNode.id 로 호출. service 는 받은 id 로 evidence_files /
     * collection_jobs 의 control_node 매칭 카운트만 리턴. ControlNode 존재 검증 없음
     * — 매칭 0 면 모두 0 리턴 (404 아님). 단순함이 핵심 (5-14h FE 가 leaf 만 호출).</p>
     *
     * <p>v15 Phase 5-15a (hybrid): own (본인) + descendant (자손, 본인 제외) 분리 카운트.
     * 호출 측 (FE) 은 합산 / 분리 표시 자유 결정. 5-14h 의 합산 임계값 (= 0 면 무경고)
     * 은 own + descendant 합 사용 권장.</p>
     *
     * <p>v15 Phase 5-15c (v15.7):</p>
     * <ul>
     *   <li>builder 안 legacy alias 3 줄 (`.evidenceFileCount(ownFiles)` 등) 제거 —
     *       호출처 0 dead 확인 후 정리 (Q2=A)</li>
     *   <li>파라미터명 {@code controlId} → {@code nodeId} (Q6=A, phase 의도 정합)</li>
     *   <li>Repository 호출 메서드명 갱신 (Q5=A, Repository rename 정합)</li>
     * </ul>
     *
     * <h3>알고리즘</h3>
     * <ol>
     *   <li>본인 (own) 카운트 3개 — 5-14e 패턴 그대로 ({@link EvidenceFileRepository#countByControlNodeId},
     *       {@link EvidenceFileRepository#countReviewedByControlNodeId},
     *       {@link CollectionJobRepository#countByControlNodeId})</li>
     *   <li>자손 id list 조회 ({@link ControlNodeRepository#findAllDescendants}) —
     *       재귀 CTE, 본인 제외</li>
     *   <li>자손 list 비어있으면 descendant 3개 모두 0 (IN 절 빈 list 호출 회피)</li>
     *   <li>자손 list 비어있지 않으면 IN 절로 카운트 3개 집계
     *       ({@link EvidenceFileRepository#countByControlNodeIds},
     *       {@link EvidenceFileRepository#countReviewedByControlNodeIds},
     *       {@link CollectionJobRepository#countByControlNodeIds})</li>
     * </ol>
     *
     * <p>본인 자체가 존재하지 않으면 모든 카운트 0 (5-14e 의 단순 결정 그대로 — 404 아님).</p>
     *
     * <p>{@code reviewCount} 의 의미 (Q4 결정): {@code reviewed_at IS NOT NULL}
     * — 관리자가 명시 검토한 횟수. pending / auto_approved 제외, approved / rejected 포함.
     * spec §3.3.1.5 의 "검토 이력 N건" 표현 정합.</p>
     *
     * @param nodeId leaf 또는 hybrid control_node.id (외부 클라이언트가 호출하는 식별자)
     * @return own / descendant 6 필드 채워진 DTO (v15.7 legacy alias 제거)
     */
    @Transactional(readOnly = true)
    public ImpactSummaryDto getImpactSummary(Long nodeId) {
        // ── 본인 (own) ───────────────────────────────────────────────
        long ownFiles   = evidenceFileRepository.countByControlNodeId(nodeId);
        long ownJobs    = collectionJobRepository.countByControlNodeId(nodeId);
        long ownReviews = evidenceFileRepository.countReviewedByControlNodeId(nodeId);

        // ── 자손 (descendant, 본인 제외) ─────────────────────────────
        long descFiles = 0L, descJobs = 0L, descReviews = 0L;
        List<ControlNode> descendants = controlNodeRepository.findAllDescendants(nodeId);
        if (!descendants.isEmpty()) {
            List<Long> descIds = descendants.stream().map(ControlNode::getId).toList();
            descFiles   = evidenceFileRepository.countByControlNodeIds(descIds);
            descJobs    = collectionJobRepository.countByControlNodeIds(descIds);
            descReviews = evidenceFileRepository.countReviewedByControlNodeIds(descIds);
        }

        // v15.7 Q2=A: legacy alias 3 줄 (.evidenceFileCount / .jobCount / .reviewCount)
        //             제거. own 3 + descendant 3 만 빌드.
        return ImpactSummaryDto.builder()
                .ownEvidenceFileCount(ownFiles)
                .ownJobCount(ownJobs)
                .ownReviewCount(ownReviews)
                .descendantEvidenceFileCount(descFiles)
                .descendantJobCount(descJobs)
                .descendantReviewCount(descReviews)
                .build();
    }
}