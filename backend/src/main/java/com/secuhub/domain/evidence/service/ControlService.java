package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.ControlDto;
import com.secuhub.domain.evidence.dto.EvidenceFileDto;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ControlService {

    /**
     * v14 Phase 5-14b: legacy controls 테이블 신규 INSERT 차단 안내 메시지.
     * UPDATE / DELETE 는 5-14f 에서 control_nodes 위임으로 교체될 예정.
     */
    private static final String GONE_MESSAGE =
            "통제 항목 생성은 신규 트리 API (PATCH /api/v1/frameworks/{id}/tree) 로 이전되었습니다. "
                    + "기존 controls 테이블은 v14 동안 rollback 안전망으로만 유지됩니다.";

    private final FrameworkRepository frameworkRepository;
    private final ControlRepository controlRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final EvidenceFileRepository evidenceFileRepository;

    /**
     * 프레임워크별 통제항목 목록.
     *
     * <p>v11 Phase 5-9: 각 통제항목에 {@code pendingReviewCount} 집계를 주입한다.
     * FrameworkService.findAll() 과 동일한 규약 — 통제 수가 수십~수백 규모라
     * N+1 은 실용 범위. 규모 확대 시 GROUP BY 집계로 전환.</p>
     */
    @Transactional(readOnly = true)
    public List<ControlDto.Response> findByFramework(Long frameworkId) {
        List<Control> controls = controlRepository.findByFrameworkIdWithEvidenceTypes(frameworkId);
        return controls.stream()
                .map(ctrl -> ControlDto.Response.from(
                        ctrl,
                        countCollectedTypes(ctrl),
                        evidenceFileRepository.countByControlIdAndReviewStatus(
                                ctrl.getId(), ReviewStatus.pending)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ControlDto.DetailResponse findDetail(Long controlId) {
        Control control = controlRepository.findById(controlId)
                .orElseThrow(() -> new ResourceNotFoundException("통제항목", controlId));

        List<EvidenceType> types = evidenceTypeRepository.findByControlId(controlId);
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

        return ControlDto.DetailResponse.builder()
                .id(control.getId())
                .frameworkId(control.getFramework().getId())
                .code(control.getCode())
                .domain(control.getDomain())
                .name(control.getName())
                .description(control.getDescription())
                .evidenceTotal(types.size())
                .evidenceCollected(collectedCount)
                .status(status)
                .evidenceTypes(typeResponses)
                .createdAt(control.getCreatedAt() != null ?
                        control.getCreatedAt().toString() : null)
                .build();
    }

    /**
     * 통제항목 생성 — v14 Phase 5-14b 부터 차단.
     *
     * <p>HTTP 410 Gone 으로 응답한다. controls 테이블은 v14 동안 rollback
     * 안전망으로 유지되지만 신규 데이터 진입은 control_nodes 트리 API
     * (PATCH /api/v1/frameworks/{id}/tree, Phase 5-14d) 로만 가능하다.</p>
     *
     * <p>@param frameworkId, request 시그니처는 {@link com.secuhub.domain.evidence.controller.ControlController}
     * 와 외부 API 호환을 위해 그대로 유지한다 — v15 에서 컨트롤러 엔드포인트
     * 자체가 제거될 때 함께 정리된다.</p>
     *
     * @throws BusinessException 항상 (HttpStatus.GONE)
     */
    @Transactional
    public ControlDto.Response create(Long frameworkId, ControlDto.CreateRequest request) {
        throw new BusinessException(GONE_MESSAGE, HttpStatus.GONE);
    }

    @Transactional
    public ControlDto.Response update(Long controlId, ControlDto.UpdateRequest request) {
        Control control = controlRepository.findById(controlId)
                .orElseThrow(() -> new ResourceNotFoundException("통제항목", controlId));
        control.update(request.getCode(), request.getDomain(), request.getName(), request.getDescription());

        // 수정 경로도 현재 pending 집계 반영 (UI 가 즉시 갱신할 수 있도록)
        long pending = evidenceFileRepository.countByControlIdAndReviewStatus(
                control.getId(), ReviewStatus.pending);
        return ControlDto.Response.from(control, countCollectedTypes(control), pending);
    }

    @Transactional
    public void delete(Long controlId) {
        if (!controlRepository.existsById(controlId)) {
            throw new ResourceNotFoundException("통제항목", controlId);
        }
        controlRepository.deleteById(controlId);
    }

    @Transactional
    public ControlDto.EvidenceTypeResponse addEvidenceType(Long controlId, ControlDto.EvidenceTypeRequest request) {
        Control control = controlRepository.findById(controlId)
                .orElseThrow(() -> new ResourceNotFoundException("통제항목", controlId));
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(control)
                .name(request.getName())
                .description(request.getDescription())
                .build());
        return ControlDto.EvidenceTypeResponse.from(et, List.of());
    }

    @Transactional
    public void deleteEvidenceType(Long evidenceTypeId) {
        if (!evidenceTypeRepository.existsById(evidenceTypeId)) {
            throw new ResourceNotFoundException("증빙 유형", evidenceTypeId);
        }
        evidenceTypeRepository.deleteById(evidenceTypeId);
    }

    private int countCollectedTypes(Control control) {
        if (control.getEvidenceTypes() == null) return 0;
        return (int) control.getEvidenceTypes().stream()
                .filter(et -> !evidenceFileRepository.findByEvidenceTypeIdOrderByVersionDesc(et.getId()).isEmpty())
                .count();
    }

    private String resolveStatus(int total, int collected) {
        if (total == 0) return "미수집";
        if (collected >= total) return "완료";
        if (collected > 0) return "진행중";
        return "미수집";
    }
}