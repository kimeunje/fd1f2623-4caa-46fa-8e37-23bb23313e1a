package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.FrameworkDto;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.ReviewStatus;
import com.secuhub.domain.evidence.repository.CollectionJobRepository;
import com.secuhub.domain.evidence.repository.ControlRepository;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FrameworkService {

    private final FrameworkRepository frameworkRepository;
    private final ControlRepository controlRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final EvidenceFileRepository evidenceFileRepository;
    private final CollectionJobRepository collectionJobRepository;

    /**
     * Framework 목록 — 집계 필드 포함 (v11 Phase 5-3)
     *
     * <p>각 Framework 마다 아래 집계를 수행:</p>
     * <ul>
     *   <li>controlCount — 통제 항목 수</li>
     *   <li>evidenceTypeCount — 소속 통제의 evidence_types 합</li>
     *   <li>jobCount — 소속 evidence_type 에 연결된 수집 작업 수</li>
     *   <li>pendingReviewCount — 검토 대기 중인 파일 수 (Framework 배지용)</li>
     * </ul>
     *
     * <p>N+1 관점: 각 Framework 당 COUNT 쿼리 4회. Framework 수가 수십 규모라 충분.
     * 규모가 커지면 한 쿼리에 GROUP BY 집계로 전환 고려.</p>
     */
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
    // 집계 헬퍼
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