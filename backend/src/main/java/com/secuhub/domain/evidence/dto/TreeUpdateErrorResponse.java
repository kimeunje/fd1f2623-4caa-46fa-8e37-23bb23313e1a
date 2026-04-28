package com.secuhub.domain.evidence.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Phase 5-14d — PATCH /tree 의 422 / 409 공용 에러 응답.
 *
 * <p>spec §3.3.1.4 응답 shape:</p>
 *
 * <p><b>422 validation_failed:</b></p>
 * <pre>
 * { "success": false, "error": "validation_failed",
 *   "details": [ {target, targetId|targetTempId, field, code, message}, ... ] }
 * </pre>
 *
 * <p><b>409 version_mismatch:</b></p>
 * <pre>
 * { "success": false, "error": "version_mismatch", "currentVersion": 18 }
 * </pre>
 *
 * <p>{@code lastEditedBy / lastEditedAt} 은 5-14d 범위 외 (Q1=B, 후속 phase).</p>
 *
 * <p>기존 {@link com.secuhub.common.dto.ApiResponse} 가 {@code success/message/data}
 * 만 표현 가능해서 별도 클래스로 분리. 200 응답은 기존 ApiResponse 사용.</p>
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TreeUpdateErrorResponse {

    private boolean success;
    private String error;
    private String message;

    // 422 validation_failed 만
    private List<ValidationDetail> details;

    // 409 version_mismatch 만
    private Long currentVersion;

    // ========================================================================
    // Factory methods
    // ========================================================================

    public static TreeUpdateErrorResponse versionMismatch(long currentVersion) {
        return TreeUpdateErrorResponse.builder()
                .success(false)
                .error("version_mismatch")
                .currentVersion(currentVersion)
                .build();
    }

    public static TreeUpdateErrorResponse validationFailed(List<ValidationDetail> details) {
        return TreeUpdateErrorResponse.builder()
                .success(false)
                .error("validation_failed")
                .details(details)
                .build();
    }
}