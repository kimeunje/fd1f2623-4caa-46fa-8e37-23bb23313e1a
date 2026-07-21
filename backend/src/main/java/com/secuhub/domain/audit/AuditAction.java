package com.secuhub.domain.audit;

/**
 * 감사 대상 액션.
 *
 * <p>본 프로젝트 enum 컨벤션 정합: top-level 별도 파일 + DB 저장은 {@code EnumType.STRING}.
 * 보안 + 민감 변경 + 증빙 접근(다운로드)에 한정. VARCHAR(64) 컬럼이므로 상수명 64자 이내.</p>
 */
public enum AuditAction {

    // --- 인증 / 보안 필터 이벤트 (명시 호출) ---
    LOGIN_SUCCESS,
    LOGIN_BY_IP,          // v19.29 — 단말 IP 자동 로그인(비밀번호 없이). LOGIN_SUCCESS 와 구분해 행위가 단말 기반이었음을 남김.
    LOGIN_FAILURE,
    ACL_BLOCKED,
    RATE_LIMIT_BLOCKED,

    // --- 증빙 승인 / 반려 ---
    EVIDENCE_APPROVE,
    EVIDENCE_REJECT,

    // --- 스크립트 ---
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

    // --- 파일 업로드 / 다운로드 / 삭제 ---
    FILE_UPLOAD,
    FILE_DOWNLOAD,   // v19.12.2 — 증빙 파일 접근(다운로드) 감사
    FILE_DELETE
}