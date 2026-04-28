package com.secuhub.common.exception;

import com.secuhub.domain.evidence.dto.ValidationDetail;
import lombok.Getter;

import java.util.List;

/**
 * Phase 5-14d — PATCH /tree 의 검증 규칙 (spec §3.3.1.4 의 12개) 위반 시 던진다.
 * GlobalExceptionHandler 가 422 + TreeUpdateErrorResponse.validationFailed(details) 로 매핑.
 */
@Getter
public class TreeValidationException extends RuntimeException {

    private final List<ValidationDetail> details;

    public TreeValidationException(List<ValidationDetail> details) {
        super("Tree update validation failed: " + (details == null ? 0 : details.size()) + " errors");
        this.details = details;
    }
}