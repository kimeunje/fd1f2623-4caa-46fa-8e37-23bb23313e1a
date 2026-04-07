package com.secuhub.domain.evidence.dto;

import lombok.*;

import java.util.List;

public class ExcelImportDto {

    @Getter
    @Builder
    @AllArgsConstructor
    public static class ImportResult {
        private int totalRows;
        private int successCount;
        private int failCount;
        private List<String> errors;
    }
}
