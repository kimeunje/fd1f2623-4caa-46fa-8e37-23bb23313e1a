package com.secuhub.common.exception;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.dto.TreeUpdateErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(message));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("접근 권한이 없습니다."));
    }

    // ========================================================================
    // Phase 5-14d — PATCH /tree 전용 두 핸들러 (spec §3.3.1.4 응답 shape 정합)
    // ========================================================================

    /**
     * 5-14d 검증 규칙 (spec §3.3.1.4 의 12개) 위반 시 422 +
     * {@link TreeUpdateErrorResponse#validationFailed(java.util.List)}
     */
    @ExceptionHandler(TreeValidationException.class)
    public ResponseEntity<TreeUpdateErrorResponse> handleTreeValidation(TreeValidationException e) {
        log.warn("Tree validation failed: {} errors", e.getDetails().size());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(TreeUpdateErrorResponse.validationFailed(e.getDetails()));
    }

    /**
     * 5-14d expectedVersion 불일치 (다이얼로그 동시 편집 충돌) 시 409 +
     * {@link TreeUpdateErrorResponse#versionMismatch(long)}
     *
     * <p>5-14d 범위에서는 currentVersion 만 노출. lastEditedBy / lastEditedAt 은
     * 후속 phase (Framework 엔티티 audit 필드 추가 후).</p>
     */
    @ExceptionHandler(OptimisticLockMismatchException.class)
    public ResponseEntity<TreeUpdateErrorResponse> handleOptimisticLock(OptimisticLockMismatchException e) {
        log.warn("Optimistic lock conflict: current version = {}", e.getCurrentVersion());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(TreeUpdateErrorResponse.versionMismatch(e.getCurrentVersion()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unexpected error: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("서버 내부 오류가 발생했습니다."));
    }
}