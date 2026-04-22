package com.secuhub.domain.evidence.entity;

/**
 * 증빙 파일 검토 상태 (v11)
 *
 * pending       — 담당자가 업로드, 관리자 검토 대기
 * approved      — 관리자가 승인 처리
 * rejected      — 관리자가 반려 처리 (review_note 필수)
 * auto_approved — 관리자가 직접 업로드 또는 자동수집 결과. 검토 단계 생략.
 *                 기존 Phase 2 파일도 마이그레이션 시 이 값으로 세팅됨.
 */
public enum ReviewStatus {
    pending,
    approved,
    rejected,
    auto_approved
}