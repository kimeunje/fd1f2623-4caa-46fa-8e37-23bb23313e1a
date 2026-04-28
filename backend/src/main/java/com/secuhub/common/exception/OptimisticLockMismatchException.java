package com.secuhub.common.exception;

import lombok.Getter;

/**
 * Phase 5-14d — PATCH /tree 의 expectedVersion 이 Framework 의 현재 version 과
 * 일치하지 않을 때 던진다. GlobalExceptionHandler 가 409 +
 * TreeUpdateErrorResponse.versionMismatch(currentVersion) 으로 매핑.
 *
 * <p>JPA 의 {@link jakarta.persistence.OptimisticLockException} 과 분리 — 그쪽은
 * lock + flush 시점의 race condition (다이얼로그 동시 편집), 본 예외는 client 가
 * 보낸 expectedVersion 의 사전 검증 (검증 규칙 1) 에서 던진다.</p>
 */
@Getter
public class OptimisticLockMismatchException extends RuntimeException {

    private final long currentVersion;

    public OptimisticLockMismatchException(long currentVersion) {
        super("Version mismatch: current=" + currentVersion);
        this.currentVersion = currentVersion;
    }
}