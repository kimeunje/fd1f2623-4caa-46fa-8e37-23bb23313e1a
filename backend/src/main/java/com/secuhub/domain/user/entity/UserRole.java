package com.secuhub.domain.user.entity;

public enum UserRole {
    admin,
    approver,
    developer,
    reviewer   // v19.24 — 심사원(읽기 전용). approver/developer 는 레거시 담당자용으로 보존.
}