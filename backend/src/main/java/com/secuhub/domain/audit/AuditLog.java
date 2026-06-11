package com.secuhub.domain.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 감사 로그 1건. append-only — 수정/삭제는 보존정책 GC(AuditRetentionGcService)만 수행.
 *
 * <p>스키마: {@code V_v19_12__create_audit_logs.sql} (prod). dev/test 는 본 매핑으로 ddl-auto 생성.
 * <p>FK: actor_user_id → users(id) ON DELETE SET NULL (계정 삭제돼도 로그 보존, actor 만 NULL).
 * <p>{@code detail} 은 @Lob 대신 columnDefinition="LONGTEXT" 명시 (L_LOB_STRING_DIALECT).
 */
@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 행위자 users.id. 시스템/익명/미인증(로그인 실패 등) 시 null. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** 기록 시점 이메일 스냅샷. 미인증 로그인 실패 시 시도된 이메일. */
    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 64, nullable = false)
    private AuditAction action;

    /** 대상 도메인 타입 (Script, User, EvidenceFile, Framework ...). nullable. */
    @Column(name = "target_type", length = 64)
    private String targetType;

    /** 대상 식별자 — Long id 또는 UUID 문자열 모두 수용. nullable. */
    @Column(name = "target_id", length = 128)
    private String targetId;

    /** 부가 정보(JSON 권장). LONGTEXT. */
    @Column(name = "detail", columnDefinition = "LONGTEXT")
    private String detail;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 16, nullable = false)
    private AuditResult result;

    /** Hibernate 가 insert 시점에 채움. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private AuditLog(Long actorUserId, String actorEmail, AuditAction action,
                     String targetType, String targetId, String detail,
                     String clientIp, AuditResult result) {
        this.actorUserId = actorUserId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.clientIp = clientIp;
        this.result = result;
    }
}