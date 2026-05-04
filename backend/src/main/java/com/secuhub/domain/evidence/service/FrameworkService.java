package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.FrameworkDto;
import com.secuhub.domain.evidence.entity.CollectionJob;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.NodeType;
import com.secuhub.domain.evidence.entity.ReviewStatus;
import com.secuhub.domain.evidence.repository.CollectionJobRepository;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework 서비스.
 *
 * <h3>v15 Phase 5-15c (v15.7) 변경</h3>
 * <ul>
 *   <li>{@link #inherit} 안 {@code EvidenceType.builder().controlNode(targetLeaf)} →
 *       {@code .controlNode(targetLeaf)} (★ Lombok @Builder 가 EvidenceType.controlNode
 *       필드명에 묶여 자동 생성 — Q1=B FORCED. 누락 시 컴파일 에러.)</li>
 *   <li>Repository 호출 메서드명 갱신 (Q5=A):
 *       {@code findByControlId} → {@code findByControlNodeId},
 *       {@code countGroupByControlIdInFramework} → {@code countGroupByControlNodeIdInFramework}</li>
 *   <li>{@code import ControlNodeRepository} 중복 정리 (pre-existing cosmetic 버그)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrameworkService {

    private final FrameworkRepository frameworkRepository;
    private final ControlNodeRepository controlNodeRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final CollectionJobRepository collectionJobRepository;
    private final EvidenceFileRepository evidenceFileRepository;

    @Transactional(readOnly = true)
    public List<FrameworkDto.Response> findAll() {
        return frameworkRepository.findAll().stream()
                .map(this::toResponseWithCounts)
                .toList();
    }

    @Transactional(readOnly = true)
    public FrameworkDto.Response findById(Long id) {
        Framework framework = frameworkRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("프레임워크", id));
        return toResponseWithCounts(framework);
    }

    @Transactional
    public FrameworkDto.Response create(FrameworkDto.CreateRequest request) {
        Framework framework = Framework.builder()
                .name(request.getName())
                .description(request.getDescription())
                .build();
        return FrameworkDto.Response.from(frameworkRepository.save(framework));
    }

    @Transactional
    public FrameworkDto.Response update(Long id, FrameworkDto.UpdateRequest request) {
        Framework framework = frameworkRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("프레임워크", id));
        framework.update(request.getName(), request.getDescription());
        return toResponseWithCounts(framework);
    }

    @Transactional
    public void delete(Long id) {
        if (!frameworkRepository.existsById(id)) {
            throw new ResourceNotFoundException("프레임워크", id);
        }
        frameworkRepository.deleteById(id);
    }

    // ------------------------------------------------------------------
    // v11 Phase 5-6 — Framework 상속
    // ------------------------------------------------------------------

    /**
     * 기존 Framework 의 구조를 스냅샷 복제하여 새 Framework 를 생성한다.
     *
     * <h3>복제 규칙</h3>
     * <ul>
     *   <li>새 Framework 의 {@code parentFramework} = source, {@code status} = active</li>
     *   <li>각 Control 을 복제 (code / domain / name / description 그대로)</li>
     *   <li>각 EvidenceType 을 복제:
     *     <ul>
     *       <li>{@code ownerUser} 유지 — 담당자 배정은 계속 유효</li>
     *       <li>{@code dueDate} 유지 — 운영자가 필요 시 수정 (기획서 §3.2)</li>
     *       <li>{@code evidenceFiles} 복제 <b>제외</b></li>
     *     </ul>
     *   </li>
     *   <li>각 CollectionJob 을 복제:
     *     <ul>
     *       <li>{@code evidenceType} 을 새로 복제된 EvidenceType 으로 재연결 (ID 매핑)</li>
     *       <li>{@code scheduleCron / isActive} 유지 — 새 Framework 에서 바로 스케줄링 가능</li>
     *       <li>{@code executions} 복제 <b>제외</b></li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>전체 작업은 단일 트랜잭션. 중간에 실패하면 모두 롤백된다.</p>
     *
     * @throws ResourceNotFoundException source Framework 가 존재하지 않으면
     */
    @Transactional
    public FrameworkDto.Response inherit(FrameworkDto.InheritRequest request) {
        Framework source = frameworkRepository.findById(request.getSourceFrameworkId())
                .orElseThrow(() -> new ResourceNotFoundException("프레임워크", request.getSourceFrameworkId()));

        // 1) 새 Framework 생성 (parentFramework = source)
        Framework target = Framework.builder()
                .name(request.getName())
                .description(request.getDescription())
                .build();
        target.setParentFramework(source);
        target = frameworkRepository.save(target);

        log.info("Framework 상속 시작 (트리 재귀): sourceId={}, targetId={}, targetName={}",
                source.getId(), target.getId(), target.getName());

        // ─── v14 Phase 5-14f — 트리 재귀 복제 ───
        // 2) 모든 노드 한 번에 가져와서 byId 맵 빌드 (5-14e FrameworkExportService 패턴)
        List<ControlNode> sourceNodes = controlNodeRepository
                .findByFrameworkIdOrderByDepthAscDisplayOrderAsc(source.getId());

        // 3) depth ASC 순서로 복제 — 부모가 자식보다 먼저 생성됨이 보장됨
        //    (findByFrameworkIdOrderByDepthAscDisplayOrderAsc 가 이미 그 순서로 반환)
        Map<Long, ControlNode> nodeIdMap = new HashMap<>();   // sourceNode.id → targetNode
        Map<Long, ControlNode> targetLeafIdMap = new HashMap<>();  // sourceLeaf.id → targetLeaf

        for (ControlNode sn : sourceNodes) {
            ControlNode parentInTarget = null;
            if (sn.getParent() != null) {
                parentInTarget = nodeIdMap.get(sn.getParent().getId());
                // 안전망 — depth ASC 순서라서 부모가 먼저 들어와 있어야 함
                if (parentInTarget == null) {
                    throw new IllegalStateException(
                            "트리 복제 중 부모 노드 미발견: sourceParentId=" + sn.getParent().getId());
                }
            }

            ControlNode tn = ControlNode.builder()
                    .framework(target)
                    .parent(parentInTarget)
                    .nodeType(sn.getNodeType())
                    .code(sn.getCode())
                    .name(sn.getName())
                    .description(sn.getDescription())
                    .displayOrder(sn.getDisplayOrder())
                    .depth(sn.getDepth())
                    .build();
            tn = controlNodeRepository.save(tn);
            nodeIdMap.put(sn.getId(), tn);

            // leaf 만 evidence_types 매핑 대상으로 기록
            if (sn.getNodeType() == NodeType.control) {
                targetLeafIdMap.put(sn.getId(), tn);
            }
        }

        // 4) 증빙 유형 복제 (담당자·마감일 유지. 파일은 제외)
        //    sourceLeaf → targetLeaf 매핑으로 evidence_types 재연결
        Map<Long, EvidenceType> evidenceTypeIdMap = new HashMap<>();

        for (Map.Entry<Long, ControlNode> e : targetLeafIdMap.entrySet()) {
            Long sourceLeafId = e.getKey();
            ControlNode targetLeaf = e.getValue();

            // v15.7 Q5=A: findByControlId → findByControlNodeId
            List<EvidenceType> sourceTypes = evidenceTypeRepository.findByControlNodeId(sourceLeafId);
            for (EvidenceType set : sourceTypes) {
                // v15.7 Q1=B: Lombok @Builder.control(...) → .controlNode(...) (FORCED)
                EvidenceType tet = EvidenceType.builder()
                        .controlNode(targetLeaf)            // 5-14f: ControlNode 직접 전달, v15.7: builder method 명도 controlNode
                        .name(set.getName())
                        .description(set.getDescription())
                        .ownerUser(set.getOwnerUser())
                        .dueDate(set.getDueDate())
                        .build();
                tet = evidenceTypeRepository.save(tet);
                evidenceTypeIdMap.put(set.getId(), tet);
            }
        }

        // 5) 수집 작업 복제 — 5-6 로직 그대로 (evidenceTypeIdMap 으로 재연결)
        int jobCloneCount = 0;
        List<CollectionJob> allJobs = collectionJobRepository.findAll();
        for (CollectionJob sj : allJobs) {
            if (sj.getEvidenceType() == null) continue;
            EvidenceType targetEt = evidenceTypeIdMap.get(sj.getEvidenceType().getId());
            if (targetEt == null) continue;  // 이 source 소속이 아닌 job

            CollectionJob tj = CollectionJob.builder()
                    .name(sj.getName())
                    .description(sj.getDescription())
                    .jobType(sj.getJobType())
                    .scriptPath(sj.getScriptPath())
                    .evidenceType(targetEt)
                    .scheduleCron(sj.getScheduleCron())
                    .isActive(sj.getIsActive())
                    .build();
            collectionJobRepository.save(tj);
            jobCloneCount++;
        }

        log.info("Framework 상속 완료: targetId={}, nodes={}, leaves={}, evidenceTypes={}, jobs={}",
                target.getId(), nodeIdMap.size(), targetLeafIdMap.size(),
                evidenceTypeIdMap.size(), jobCloneCount);

        return toResponseWithCounts(target);
    }

    // ------------------------------------------------------------------
    // 집계 헬퍼 (Phase 5-3)
    // ------------------------------------------------------------------

    private FrameworkDto.Response toResponseWithCounts(Framework fw) {
        // controlCount = leaf 통제 수
        long controlCount = controlNodeRepository
                .countByFrameworkIdAndNodeType(fw.getId(), NodeType.control);

        // evidenceTypeCount = Framework 내 모든 leaf 의 evidence_types 합계
        // v15.7 Q5=A: countGroupByControlIdInFramework → countGroupByControlNodeIdInFramework
        int evidenceTypeCount = evidenceTypeRepository
                .countGroupByControlNodeIdInFramework(fw.getId()).stream()
                .mapToInt(row -> ((Long) row[1]).intValue())
                .sum();

        long jobCount = collectionJobRepository.countByFrameworkId(fw.getId());
        long pendingReviewCount = evidenceFileRepository
                .countByFrameworkIdAndReviewStatus(fw.getId(), ReviewStatus.pending);

        return FrameworkDto.Response.from(fw, (int) controlCount, evidenceTypeCount,
                (int) jobCount, pendingReviewCount);
    }
}