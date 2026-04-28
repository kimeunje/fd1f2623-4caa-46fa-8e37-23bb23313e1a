package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.domain.evidence.dto.ExcelImportDto;
import com.secuhub.domain.evidence.repository.ControlRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
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
 * <p>v13 까지 엑셀 컬럼 구조: | 코드 | 영역 | 항목명 | 설명 | 필요 증빙 (쉼표 구분) |</p>
 *
 * <p>v14 옵션 D 채택으로 영역(domain) 텍스트 컬럼이 폐기되고
 * control_nodes 트리로 정규화되었다. 엑셀 Import 자체는 v14 후속
 * (Phase 5-14f) 에서 트리 친화 포맷으로 재구현될 예정.
 * 그 사이 본 메서드는 410 Gone 으로 명시 차단한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private static final String GONE_MESSAGE =
            "엑셀 Import 는 통제 모델 정규화(v14)에 따라 일시 비활성화되었습니다. "
                    + "통제 항목 추가는 [통제 관리] 다이얼로그를 사용해주세요.";

    // 의존성은 v14 진행 중 트리 기반 Import 재구현 시 그대로 사용 예정이라 제거하지 않는다.
    // (Lombok @RequiredArgsConstructor 가 생성자에서 사용하므로 실질적 unused 아님)
    private final FrameworkRepository frameworkRepository;
    private final ControlRepository controlRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;

    /**
     * 엑셀 Import — v14 Phase 5-14b 부터 차단.
     *
     * <p>HTTP 410 Gone. 시그니처는 {@code FrameworkController.importControls()}
     * 와의 외부 API 호환을 위해 유지한다.</p>
     *
     * @throws BusinessException 항상 (HttpStatus.GONE)
     */
    @Transactional
    public ExcelImportDto.ImportResult importControls(Long frameworkId, MultipartFile file) {
        throw new BusinessException(GONE_MESSAGE, HttpStatus.GONE);
    }
}