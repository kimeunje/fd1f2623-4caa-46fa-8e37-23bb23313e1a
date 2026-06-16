package com.secuhub.domain.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.secuhub.domain.audit.AuditAction;
import com.secuhub.domain.audit.AuditLog;
import com.secuhub.domain.audit.AuditResult;

import java.time.LocalDateTime;

/**
 * 감사 로그 1건 응답 (AUDIT-2, B: targetName).
 *
 * <p>enum 은 Jackson 이 name 으로 직렬화, LocalDateTime 은 ISO-8601 자동 직렬화.
 * null 필드는 응답에서 생략(NON_NULL) — targetName 이 없는 행(스크립트/트리 등)은 키 자체가 빠진다.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogResponse(
        Long id,
        Long actorUserId,
        String actorEmail,
        AuditAction action,
        String targetType,
        String targetId,
        String targetName,
        String detail,
        String clientIp,
        AuditResult result,
        LocalDateTime createdAt
) {
    public static AuditLogResponse from(AuditLog a) {
        return new AuditLogResponse(
                a.getId(),
                a.getActorUserId(),
                a.getActorEmail(),
                a.getAction(),
                a.getTargetType(),
                a.getTargetId(),
                a.getTargetName(),
                a.getDetail(),
                a.getClientIp(),
                a.getResult(),
                a.getCreatedAt()
        );
    }
}