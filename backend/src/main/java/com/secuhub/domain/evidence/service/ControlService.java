package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.ControlDto;
import com.secuhub.domain.evidence.dto.EvidenceFileDto;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 통제항목 서비스.
 *
 * <h3>v14 Phase 5-14f — ControlNode 위임 + write 410 Gone 일괄</h3>
 *
 * <ul>
 *   <li><b>findByFramework / findDetail</b> — leaf {@link ControlNode} 위임. spec §8.2
 *       의 ancestors[] 필드 빌드 추가</li>
 *   <li><b>create</b> — 5-14b 의 410 Gone 그대로 (보존)</li>
 *   <li><b>update / delete / addEvidenceType</b> — 5-14f 신규 410 Gone (PATCH /tree 동선)</li>
 *   <li><b>deleteEvidenceType</b> — 그대로 보존 (EvidenceType 직접 삭제, Control 거치지 않음)</li>
 * </ul>
 *
 * <h3>v15 Phase 5-15b R3 (v15.6) — ControlController 삭제</h3>
 * <p>본 service 의 {@link #findByFramework} / {@link #findDetail} 호출 측 (옛
 * ControlController) 은 v15.3 에서 일괄 삭제됨. v15.6 ControlNodeController 가 신
 * endpoint 신설 — 본 메서드들은 dead code 가능성 높음. 본 phase 비대화 회피로 v15.7
 * 시점 보존 (Q5=A 메서드명 일괄 갱신만), 후속 phase 일괄 정리 후보로 등재.</p>
 *
 * <h3>v15 Phase 5-15c (v15.7) — Q5=A 정합</h3>
 * <ul>
 *   <li>{@code evidenceTypeRepository.findByControlId} → {@code findByControlNodeId}
 *       (3 호출 — findByFramework × 1, findDetail × 1, 헬퍼 0)</li>
 *   <li>{@code evidenceFileRepository.countByControlIdAndReviewStatus} →
 *       {@code countByControlNodeIdAndReviewStatus} (1 호출)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ControlService {

    /**
     * v14 Phase 5-14b: legacy controls 테이블 신규 INSERT 차단 메시지.
     * v14 Phase 5-14f: UPDATE / DELETE / addEvidenceType 도 동일 정책 확장.
     */
    private static final String GONE_MESSAGE =
            "통제 항목 직접 수정은 신규 트리 API (PATCH /api/v1/frameworks/{id}/tree) 로 이전되었습니다. "
                    + "기존 controls 테이블은 v14 동안 rollback 안전망으로만 유지됩니다.";

    private final FrameworkRepository frameworkRepository;
    private final ControlNodeRepository controlNodeRepository;        // ← v14 Phase 5-14f: ControlRepository 제거
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final EvidenceFileRepository evidenceFileRepository;
    private final UserRepository userRepository;

    // ====================================================================
    // 조회 (leaf-only 위임)
    // ====================================================================

    /**
     * 프레임워크별 통제항목 (leaf) 목록.
     *
     * <p>v11 Phase 5-9: 각 통제에 {@code pendingReviewCount} 집계 주입.</p>
     *
     * <p>v14 Phase 5-14f: leaf {@link ControlNode} 위임 — {@code node_type='control'} 만
     * 반환. 외부 API 호환 ({@code GET /api/v1/frameworks/{id}/controls} 의 의미를 그대로
     * "통제 leaf 목록" 으로 유지). 통제 수가 수십~수백 규모라 leaf 마다 evidence_types
     * 조회 + pending count 호출 (N+1) 은 실용 범위. 규모 확대 시 GROUP BY 집계로 전환.</p>
     *
     * <p>v15.7: Repository 메서드명 갱신 (Q5=A).</p>
     */
    @Transactional(readOnly = true)
    public List<ControlDto.Response> findByFramework(Long frameworkId) {
        // 5-14f: leaf ControlNode 만 조회
        List<ControlNode> leaves = controlNodeRepository
                .findByFrameworkIdAndNodeTypeOrderByDisplayOrderAsc(frameworkId, NodeType.control);

        return leaves.stream()
                .map(leaf -> {
                    // v15.7 Q5=A: findByControlId → findByControlNodeId
                    List<EvidenceType> ets = evidenceTypeRepository.findByControlNodeId(leaf.getId());
                    int total = ets.size();
                    int collected = countCollectedTypes(ets);
                    // v15.7 Q5=A: countByControlIdAndReviewStatus → countByControlNodeIdAndReviewStatus
                    long pending = evidenceFileRepository.countByControlNodeIdAndReviewStatus(
                            leaf.getId(), ReviewStatus.pending);
                    return ControlDto.Response.from(leaf, total, collected, pending);
                })
                .toList();
    }

    /**
     * 통제항목 (leaf) 상세.
     *
     * <p>v14 Phase 5-14f: leaf {@link ControlNode} 위임 + spec §8.2 의 ancestors[] 추가
     * (EvidenceTypeDetailView 헤더 서브텍스트의 N단 경로용).</p>
     *
     * <p>요청된 nodeId 가 category 노드면 404 ({@link ResourceNotFoundException}) —
     * 통제 상세 endpoint 는 leaf 만 의미.</p>
     *
     * <p>v15.7: 파라미터명 {@code controlId} → {@code nodeId} (Q6=A) + Repository 호출
     * 갱신 (Q5=A).</p>
     */
    @Transactional(readOnly = true)
    public ControlDto.DetailResponse findDetail(Long nodeId) {
        ControlNode leaf = controlNodeRepository.findById(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("통제항목", nodeId));

        if (leaf.getNodeType() != NodeType.control) {
            // category 노드는 통제 상세로 조회 불가 — 404 자연 응답
            throw new ResourceNotFoundException("통제항목 (분류 노드는 통제 상세로 조회 불가)", nodeId);
        }

        // v15.7 Q5=A: findByControlId → findByControlNodeId
        List<EvidenceType> types = evidenceTypeRepository.findByControlNodeId(leaf.getId());
        List<ControlDto.EvidenceTypeResponse> typeResponses = new ArrayList<>();
        int collectedCount = 0;

        for (EvidenceType et : types) {
            List<EvidenceFile> files = evidenceFileRepository.findByEvidenceTypeIdOrderByVersionDesc(et.getId());
            List<EvidenceFileDto.Response> fileResponses = files.stream()
                    .map(EvidenceFileDto.Response::from)
                    .toList();
            typeResponses.add(ControlDto.EvidenceTypeResponse.from(et, fileResponses));
            if (!files.isEmpty()) collectedCount++;
        }

        String status = resolveStatus(types.size(), collectedCount);

        // v14 Phase 5-14f — ancestors[] 빌드 (spec §8.2)
        List<ControlDto.AncestorSummary> ancestors = buildAncestors(leaf);

        // domain 은 5-14f 후 deprecated (controls.domain 컬럼 폐기). depth=1 ancestor name
        // 으로 대체 — FrameworkExportService (5-14e) 의 영역 컬럼 패턴과 정합.
        String domain = ancestors.isEmpty() ? null : ancestors.get(0).getName();

        return ControlDto.DetailResponse.builder()
                .id(leaf.getId())
                .frameworkId(leaf.getFramework() != null ? leaf.getFramework().getId() : null)
                .code(leaf.getCode())
                .domain(domain)
                .name(leaf.getName())
                .description(leaf.getDescription())
                .evidenceTotal(types.size())
                .evidenceCollected(collectedCount)
                .status(status)
                .evidenceTypes(typeResponses)
                .ancestors(ancestors)            // ← v14 Phase 5-14f 신규 (spec §8.2)
                .createdAt(leaf.getCreatedAt() != null ?
                        leaf.getCreatedAt().toString() : null)
                .build();
    }

    // ====================================================================
    // 쓰기 — 모두 410 Gone (5-14b create / 5-14f update/delete/addEvidenceType)
    // ====================================================================

    /**
     * 통제항목 삭제 — v14 Phase 5-14f 부터 차단.
     *
     * <p>5-14b 의 {@link #create} 차단 정책 확장. 통제 삭제는 {@code PATCH /api/v1/frameworks/{id}/tree}
     * (TreeUpdateService 의 deleted 액션) 사용. cascading delete 는 DB 의 ON DELETE CASCADE
     * 가 자동 처리.</p>
     *
     * @throws BusinessException 항상 (HttpStatus.GONE)
     * @deprecated v14 Phase 5-14f. v15 에서 ControlController 엔드포인트 자체 제거.
     */
    @Deprecated(since = "v14 Phase 5-14f", forRemoval = true)
    @Transactional
    public void delete(Long nodeId) {
        throw new BusinessException(GONE_MESSAGE, HttpStatus.GONE);
    }

    /**
     * 증빙 유형 삭제 — 5-14f 후에도 보존.
     *
     * <p>EvidenceType 직접 삭제 (Control 거치지 않음). {@link ControlNode#getEvidenceTypes}
     * 는 cascade ALL + orphanRemoval 이지만, 본 메서드는 EvidenceType 만 직접 지우는 단순
     * 경로 — Control / ControlNode 의존성 0. 5-14f 의 410 Gone 정책 본질이 "Control 엔티티
     * 직접 사용 금지" 이지 "evidence_type 삭제 금지" 가 아니므로 그대로 보존.</p>
     */
    @Transactional
    public void deleteEvidenceType(Long evidenceTypeId) {
        if (!evidenceTypeRepository.existsById(evidenceTypeId)) {
            throw new ResourceNotFoundException("증빙 유형", evidenceTypeId);
        }
        evidenceTypeRepository.deleteById(evidenceTypeId);
    }

    /**
     * v18 — 증빙 유형 생성 (특정 통제 노드에 추가).
     *
     * @param nodeId      통제 노드 ID (control_nodes.id)
     * @param name        증빙 유형 이름
     * @param description 설명 (nullable)
     */
    @Transactional
    public void createEvidenceType(Long nodeId, String name, String description) {
        ControlNode node = controlNodeRepository.findById(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("통제 노드", nodeId));
        EvidenceType et = EvidenceType.builder()
                .controlNode(node)
                .name(name)
                .description(description)
                .build();
        evidenceTypeRepository.save(et);
    }

    /**
     * v18 — 증빙 유형 수정 (이름/설명/담당자/마감일).
     *
     * <p>부분 수정: null 필드는 기존 값 유지.</p>
     * <ul>
     *   <li>ownerUserId = null → 변경 안 함</li>
     *   <li>ownerUserId = 0L → 담당자 해제 (미배정)</li>
     *   <li>ownerUserId > 0 → 해당 사용자로 배정</li>
     *   <li>dueDate = null → 변경 안 함</li>
     *   <li>dueDate = "" → 마감일 해제</li>
     *   <li>dueDate = "2026-12-31" → 해당 날짜로 설정</li>
     * </ul>
     */
    @Transactional
    public void updateEvidenceType(Long evidenceTypeId, String name, String description,
                                    Long ownerUserId, String dueDate) {
        EvidenceType et = evidenceTypeRepository.findById(evidenceTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 유형", evidenceTypeId));
 
        // 이름/설명 수정
        et.update(name, description);
 
        // 담당자 수정
        if (ownerUserId != null) {
            if (ownerUserId == 0L) {
                et.assignOwner(null);  // 미배정
            } else {
                User owner = userRepository.findById(ownerUserId)
                        .orElseThrow(() -> new ResourceNotFoundException("사용자", ownerUserId));
                et.assignOwner(owner);
            }
        }
 
        // 마감일 수정
        if (dueDate != null) {
            if (dueDate.isBlank()) {
                et.updateDueDate(null);  // 마감일 해제
            } else {
                et.updateDueDate(java.time.LocalDate.parse(dueDate));
            }
        }
    }

    // ====================================================================
    // 헬퍼
    // ====================================================================

    /**
     * v14 Phase 5-14f — leaf 의 ancestors (depth=1 부터 직계 부모까지).
     *
     * <p>spec §8.2 의 EvidenceTypeDetailView 헤더 서브텍스트용. leaf 자기 자신은 미포함.
     * parent chain 을 끝까지 따라가서 root 부터 leaf 의 직계 부모까지 순서대로 반환.</p>
     *
     * <p>5-14e {@code FrameworkExportService} 의 ancestors 빌드 패턴과 동일 (visited
     * 가드로 cycle 안전).</p>
     */
    private List<ControlDto.AncestorSummary> buildAncestors(ControlNode leaf) {
        List<ControlDto.AncestorSummary> ancestors = new ArrayList<>();
        ControlNode cur = leaf.getParent();
        Set<Long> visited = new HashSet<>();
        while (cur != null) {
            if (cur.getId() == null || !visited.add(cur.getId())) break;
            ancestors.add(0, ControlDto.AncestorSummary.builder()
                    .id(cur.getId())
                    .code(cur.getCode())
                    .name(cur.getName())
                    .build());
            cur = cur.getParent();
        }
        return ancestors;
    }

    /**
     * v14 Phase 5-14f — 시그니처 변경: {@code Control} → {@code List<EvidenceType>} 받음.
     * leaf 의 evidence_types 리스트가 이미 호출 측에서 조회되어 있으면 그대로 전달
     * (findByFramework / findDetail 에서 활용). 한 번 조회 → 두 번 활용 (collected 계산 +
     * total 계산).
     */
    private int countCollectedTypes(List<EvidenceType> types) {
        if (types == null || types.isEmpty()) return 0;
        int collected = 0;
        for (EvidenceType et : types) {
            if (!evidenceFileRepository.findByEvidenceTypeIdOrderByVersionDesc(et.getId()).isEmpty()) {
                collected++;
            }
        }
        return collected;
    }

    private String resolveStatus(int total, int collected) {
        if (total == 0) return "미수집";
        if (collected >= total) return "완료";
        if (collected > 0) return "진행중";
        return "미수집";
    }
}