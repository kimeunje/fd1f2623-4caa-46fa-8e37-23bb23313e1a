package com.secuhub.domain.audit;

/**
 * 감사 기록 결과 구분 (VARCHAR(16) 저장).
 */
public enum AuditResult {
    SUCCESS,    // 정상 수행
    FAILURE,    // 시도했으나 실패 (예: 로그인 실패, 예외로 중단)
    BLOCKED     // 정책에 의해 차단 (ACL, Rate Limit)
}