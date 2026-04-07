package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.ExcelImportDto;
import com.secuhub.domain.evidence.entity.Control;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.repository.ControlRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 통제항목 엑셀 Import
 *
 * 엑셀 컬럼 구조 (1행 헤더):
 * | 코드 | 영역 | 항목명 | 설명 | 필요 증빙 (쉼표 구분) |
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final FrameworkRepository frameworkRepository;
    private final ControlRepository controlRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;

    @Transactional
    public ExcelImportDto.ImportResult importControls(Long frameworkId, MultipartFile file) {
        Framework framework = frameworkRepository.findById(frameworkId)
                .orElseThrow(() -> new ResourceNotFoundException("프레임워크", frameworkId));

        List<String> errors = new ArrayList<>();
        int totalRows = 0;
        int successCount = 0;

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();

            for (int i = 1; i <= lastRow; i++) {  // 0행은 헤더
                Row row = sheet.getRow(i);
                if (row == null) continue;
                totalRows++;

                try {
                    String code = getCellStringValue(row.getCell(0));
                    String domain = getCellStringValue(row.getCell(1));
                    String name = getCellStringValue(row.getCell(2));
                    String description = getCellStringValue(row.getCell(3));
                    String evidenceNames = getCellStringValue(row.getCell(4));

                    if (code == null || code.isBlank()) {
                        errors.add((i + 1) + "행: 코드가 비어있습니다.");
                        continue;
                    }
                    if (name == null || name.isBlank()) {
                        errors.add((i + 1) + "행: 항목명이 비어있습니다.");
                        continue;
                    }

                    Control control = Control.builder()
                            .framework(framework)
                            .code(code.trim())
                            .domain(domain != null ? domain.trim() : null)
                            .name(name.trim())
                            .description(description != null ? description.trim() : null)
                            .build();
                    control = controlRepository.save(control);

                    // 필요 증빙 파싱 (쉼표 구분)
                    if (evidenceNames != null && !evidenceNames.isBlank()) {
                        String[] names = evidenceNames.split(",");
                        for (String etName : names) {
                            if (!etName.trim().isEmpty()) {
                                evidenceTypeRepository.save(EvidenceType.builder()
                                        .control(control)
                                        .name(etName.trim())
                                        .build());
                            }
                        }
                    }

                    successCount++;
                } catch (Exception e) {
                    errors.add((i + 1) + "행: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new BusinessException("엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
        }

        log.info("통제항목 엑셀 Import 완료 — 전체: {}, 성공: {}, 실패: {}", totalRows, successCount, errors.size());

        return ExcelImportDto.ImportResult.builder()
                .totalRows(totalRows)
                .successCount(successCount)
                .failCount(errors.size())
                .errors(errors)
                .build();
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }
}
