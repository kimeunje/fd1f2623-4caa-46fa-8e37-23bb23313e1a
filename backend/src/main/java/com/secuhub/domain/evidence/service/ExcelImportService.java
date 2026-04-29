package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.ExcelImportDto;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 통제항목 엑셀 Import — v14 Phase 5-14b 부터 차단됨.
 *
 * <h3>v13 까지 엑셀 컬럼 구조</h3>
 * <p>{@code | 코드 | 영역 | 항목명 | 설명 | 필요 증빙 (쉼표 구분) |}</p>
 *
 * <h3>v14 변경 이력</h3>
 * <ul>
 *   <li><b>5-14b (2026-04-28)</b> — 옵션 D 채택으로 영역(domain) 텍스트 컬럼이 폐기되고
 *       {@code control_nodes} 트리로 정규화. 410 Gone 으로 차단</li>
 *   <li><b>5-14f (2026-04-28)</b> — 의존성 정리 ({@code ControlRepository} /
 *       {@code EvidenceTypeRepository} 제거). {@code EvidenceType.control} 의 타입이
 *       {@link com.secuhub.domain.evidence.entity.ControlNode} 로 변경되어 기존 평면
 *       Import 로직은 더 이상 재사용 불가. 새 트리 친화 Import 는 5-14h FE 에서 Excel
 *       parse → {@code PATCH /api/v1/frameworks/{id}/tree} 호출 패턴으로 전환</li>
 * </ul>
 *
 * <p>{@link FrameworkRepository} 는 frameworkId 사전 검증 (404 우선) 용도로 유지.</p>
 *
 * @deprecated v14 Phase 5-14b 부터 410 Gone. v15 에서 메서드 자체 제거 예정.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private static final String GONE_MESSAGE =
            "엑셀 Import 는 통제 모델 정규화(v14)에 따라 일시 비활성화되었습니다. "
                    + "통제 항목 추가는 [통제 관리] 다이얼로그 또는 "
                    + "PATCH /api/v1/frameworks/{id}/tree 를 사용해 주세요. "
                    + "(5-14h FE 에서 트리 친화 Import 다이얼로그 제공 예정)";

    /**
     * frameworkId 사전 검증용 — 존재하지 않는 framework 면 404 (ResourceNotFoundException)
     * 가 410 보다 우선. 실 import 로직 의존성은 5-14f 에서 모두 제거됨.
     */
    private final FrameworkRepository frameworkRepository;

    /**
     * 엑셀 Import — v14 Phase 5-14b 부터 차단.
     *
     * <p>HTTP 410 Gone. 시그니처는 {@code FrameworkController.importControls()}
     * 와의 외부 API 호환을 위해 유지한다.</p>
     *
     * <p>frameworkId 가 존재하지 않으면 404 우선 응답 (ResourceNotFoundException). 그 외는
     * 항상 410 Gone.</p>
     *
     * @throws ResourceNotFoundException frameworkId 가 존재하지 않을 때 (404 우선)
     * @throws BusinessException 항상 (HttpStatus.GONE) — frameworkId 가 유효한 경우
     * @deprecated v14 Phase 5-14b. v15 에서 메서드 자체 제거.
     */
    @Deprecated(since = "v14 Phase 5-14b", forRemoval = true)
    @Transactional
    public ExcelImportDto.ImportResult importControls(Long frameworkId, MultipartFile file) {
        // 5-14f: 404 (없는 frameworkId) 가 410 (Gone) 보다 우선되도록 사전 검증
        frameworkRepository.findById(frameworkId)
                .orElseThrow(() -> new ResourceNotFoundException("프레임워크", frameworkId));

        throw new BusinessException(GONE_MESSAGE, HttpStatus.GONE);
    }
}