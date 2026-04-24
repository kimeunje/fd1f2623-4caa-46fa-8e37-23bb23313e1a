package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.FrameworkDto;
import com.secuhub.domain.evidence.entity.CollectionJob;
import com.secuhub.domain.evidence.entity.Control;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.ReviewStatus;
import com.secuhub.domain.evidence.repository.CollectionJobRepository;
import com.secuhub.domain.evidence.repository.ControlRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class FrameworkService {

    private final FrameworkRepository frameworkRepository;
    private final ControlRepository controlRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final EvidenceFileRepository evidenceFileRepository;
    private final CollectionJobRepository collectionJobRepository;

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

        log.info("Framework 상속 시작: sourceId={}, targetId={}, targetName={}",
                source.getId(), target.getId(), target.getName());

        // 2) 통제 항목 복제 (원본 → 복제본 ID 매핑 유지, 수집 작업 재연결에 사용)
        List<Control> sourceControls = controlRepository.findByFrameworkIdOrderByCodeAsc(source.getId());
        Map<Long, Control> controlIdMap = new HashMap<>();

        for (Control sc : sourceControls) {
            Control tc = Control.builder()
                    .framework(target)
                    .code(sc.getCode())
                    .domain(sc.getDomain())
                    .name(sc.getName())
                    .description(sc.getDescription())
                    .build();
            tc = controlRepository.save(tc);
            controlIdMap.put(sc.getId(), tc);
        }

        // 3) 증빙 유형 복제 (담당자 · 마감일 유지. 파일은 제외)
        Map<Long, EvidenceType> evidenceTypeIdMap = new HashMap<>();

        for (Control sc : sourceControls) {
            Control tc = controlIdMap.get(sc.getId());
            List<EvidenceType> sourceTypes = evidenceTypeRepository.findByControlId(sc.getId());

            for (EvidenceType set : sourceTypes) {
                EvidenceType tet = EvidenceType.builder()
                        .control(tc)
                        .name(set.getName())
                        .description(set.getDescription())
                        .ownerUser(set.getOwnerUser())   // 담당자 유지
                        .dueDate(set.getDueDate())       // 마감일 유지
                        .build();
                tet = evidenceTypeRepository.save(tet);
                evidenceTypeIdMap.put(set.getId(), tet);
            }
        }

        // 4) 수집 작업 복제 (새 evidence_type_id 로 재연결. 실행 이력은 제외)
        //    CollectionJobRepository 에는 evidenceTypeId 단위 조회가 없으므로 findAll 후 필터.
        //    Framework 당 수집 작업 규모(수십 건)에서 실측 영향 없음.
        int jobCloneCount = 0;
        List<CollectionJob> allJobs = collectionJobRepository.findAll();
        for (CollectionJob sj : allJobs) {
            if (sj.getEvidenceType() == null) continue;
            EvidenceType targetEt = evidenceTypeIdMap.get(sj.getEvidenceType().getId());
            if (targetEt == null) continue; // 이 source 소속이 아닌 job

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

        log.info("Framework 상속 완료: targetId={}, controls={}, evidenceTypes={}, jobs={}",
                target.getId(), controlIdMap.size(), evidenceTypeIdMap.size(), jobCloneCount);

        return toResponseWithCounts(target);
    }

    // ------------------------------------------------------------------
    // 집계 헬퍼 (Phase 5-3)
    // ------------------------------------------------------------------

    private FrameworkDto.Response toResponseWithCounts(Framework fw) {
        var controls = controlRepository.findByFrameworkIdOrderByCodeAsc(fw.getId());

        int controlCount = controls.size();

        int evidenceTypeCount = controls.stream()
                .mapToInt(c -> evidenceTypeRepository.findByControlId(c.getId()).size())
                .sum();

        long jobCount = collectionJobRepository.countByFrameworkId(fw.getId());

        long pendingReviewCount = evidenceFileRepository
                .countByFrameworkIdAndReviewStatus(fw.getId(), ReviewStatus.pending);

        return FrameworkDto.Response.from(
                fw,
                controlCount,
                evidenceTypeCount,
                (int) jobCount,
                pendingReviewCount);
    }
}