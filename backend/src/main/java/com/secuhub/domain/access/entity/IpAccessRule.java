package com.secuhub.domain.access.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v19.0 — 계정별 IP 접근 제어 규칙 (BE-1).
 *
 * <p>한 사용자(user)에 대해 enabled 규칙이 1건 이상이면, 그 규칙들이 허용하는
 * IP/대역에서만 해당 계정으로 로그인·요청이 가능. enabled 규칙이 0건이면 IP 제한 없음
 * (fail-open — 기본 동작). 잠금 사고를 막기 위해 "규칙 없음 = 전부 허용"을 기본으로 둔다.</p>
 *
 * <p>{@code cidr} 는 CIDR 표기 또는 단일 IP. 단일 IP 는 {@code /32}(IPv4)·{@code /128}(IPv6)
 * 와 동일하게 취급된다. 매칭은 {@link com.secuhub.domain.access.util.IpCidr} 가 담당.</p>
 *
 * <p>전역(시스템 전체) 차단은 본 phase 범위 외 — 계정별 제어만 구현 (사용자 결정).</p>
 *
 * <p>{@code user_id} 는 JPA 연관(@ManyToOne) 대신 plain FK 컬럼으로 둔다 — 권한/소유
 * 매개를 plain FK 로 다루는 본 프로젝트 컨벤션(예: evidence_types.owner_user_id) 정합.
 * DB 레벨 FK + ON DELETE CASCADE 는 Flyway 가 보장 (계정 삭제 시 규칙 동반 삭제).</p>
 */
@Entity
@Table(name = "ip_access_rules", indexes = {
        @Index(name = "idx_ip_rules_user", columnList = "user_id"),
        @Index(name = "idx_ip_rules_user_enabled", columnList = "user_id,enabled")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class IpAccessRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 대상 계정. plain FK (DB FK + ON DELETE CASCADE 는 Flyway). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** CIDR 표기 또는 단일 IP. 예: "203.0.113.0/24", "192.168.1.10", "::1/128". */
    @Column(nullable = false, length = 64)
    private String cidr;

    /** 운영 메모 (선택). 예: "본사 사무실 대역". */
    @Column(length = 200)
    private String description;

    /** 활성 여부. false 면 매칭에서 제외 (삭제 없이 일시 비활성). */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ── BE-2 (관리자 CRUD) 에서 사용할 변경 메서드 ──

    public void updateCidr(String cidr) {
        this.cidr = cidr;
    }

    public void updateDescription(String description) {
        this.description = description;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}