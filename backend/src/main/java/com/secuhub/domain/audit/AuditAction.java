package com.secuhub.domain.audit;

/**
 * 감사 대상 액션 (AUDIT-1, Q2 1차 범위).
 *
 * <p>본 프로젝트 enum 컨벤션 정합: top-level 별도 파일 + DB 저장은 {@code EnumType.STRING}.
 * 보안 + 민감 변경에 한정 (조회성 GET 제외 — L_OVER_ENGINEER_DETECT).
 *
 * <p>VARCHAR(64) 컬럼에 저장되므로 각 상수명은 64자 이내로 유지.
 */
public enum AuditAction {

    // --- 인증 / 보안 필터 이벤트 (명시 호출 — AOP 대상 아님) ---
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    ACL_BLOCKED,            // 계정별 IP 접근제어 차단 (v19.0~2)
    RATE_LIMIT_BLOCKED,     // 로그인 Rate Limit 429 (v19.9)

    // --- 증빙 승인 / 반려 ---
    EVIDENCE_APPROVE,
    EVIDENCE_REJECT,

    // --- 스크립트 (v19.4~5 버저닝 포함) ---
    SCRIPT_CREATE,
    SCRIPT_UPDATE,
    SCRIPT_ROLLBACK,
    SCRIPT_DELETE,

    // --- 사용자 CRUD ---
    USER_CREATE,
    USER_UPDATE,
    USER_DELETE,

    // --- 프레임워크 / 통제 트리 변경 ---
    FRAMEWORK_CHANGE,
    TREE_CHANGE,

    // --- 파일 업로드 / 삭제 ---
    FILE_UPLOAD,
    FILE_DELETE
}