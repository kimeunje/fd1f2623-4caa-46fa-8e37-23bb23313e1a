package com.secuhub.domain.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.secuhub.domain.audit.AuditAction;
import com.secuhub.domain.audit.AuditLog;
import com.secuhub.domain.audit.AuditResult;

import java.time.LocalDateTime;

/**
 * 감사 로그 1건 응답 (AUDIT-2).
 *
 * <p>enum 은 Jackson 이 name 으로 직렬화(예: "LOGIN_SUCCESS"/"SUCCESS"),
 * LocalDateTime 은 ISO-8601 문자열 자동 직렬화(본 프로젝트 컨벤션).
 * null 필드는 응답에서 생략(TreeDto 의 NON_NULL 패턴 정합).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogResponse(
        Long id,
        Long actorUserId,
        String actorEmail,
        AuditAction action,
        String targetType,
        String targetId,
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
                a.getDetail(),
                a.getClientIp(),
                a.getResult(),
                a.getCreatedAt()
        );
    }
}