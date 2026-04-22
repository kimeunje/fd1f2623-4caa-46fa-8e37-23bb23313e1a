package com.secuhub.domain.evidence.entity;

/**
 * Framework 상태 (v11)
 *
 * active   — 현재 감사 주기에서 사용 중
 * archived — 종료된 감사 주기. 이력 조회는 가능하지만 신규 작업 대상 아님.
 */
public enum FrameworkStatus {
    active,
    archived
}