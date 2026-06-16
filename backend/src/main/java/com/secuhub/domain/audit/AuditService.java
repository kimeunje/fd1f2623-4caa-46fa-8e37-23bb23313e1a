package com.secuhub.domain.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.config.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * 감사 로그 기록 진입점 (AUDIT-1, B: targetName).
 *
 * <h3>설계 메모</h3>
 * <ul>
 *   <li><b>동기 기록</b> (v1). 비동기는 부모 tx commit 전 race + cross-thread LAZY 위험이라 후속.</li>
 *   <li><b>{@code REQUIRES_NEW}</b> — 감사 기록을 호출자(업무) 트랜잭션과 분리.</li>
 *   <li><b>예외 비전파 책임은 호출자</b> — 모든 호출자(Aspect / 필터 / safeAudit)는 try-catch.</li>
 * </ul>
 *
 * <p><b>record 오버로드</b>: 대상 표시명(targetName)을 받는 6-arg 가 신규 기본형.
 * 기존 5-arg / 8-arg 는 targetName=null 로 위임(백워드 호환).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    /**
     * AOP / 서비스 계층용 (targetName 포함). actor 는 SecurityContext, IP 는 현재 요청에서 자동 해소.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, AuditResult result,
                       String targetType, String targetId, String targetName, String detail) {
        Actor actor = resolveActor();
        persist(action, result, targetType, targetId, targetName, detail,
                actor.userId(), actor.email(), resolveClientIp());
    }

    /** 백워드 호환 — targetName 없이 기록 (targetName=null). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, AuditResult result,
                       String targetType, String targetId, String detail) {
        Actor actor = resolveActor();
        persist(action, result, targetType, targetId, null, detail,
                actor.userId(), actor.email(), resolveClientIp());
    }

    /**
     * 필터 / 특수 지점용 (명시 actor·IP). SecurityContext 가 비어있거나(미인증 로그인 실패)
     * actor·IP 를 호출자가 이미 아는 경우. targetName=null.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, AuditResult result,
                       String targetType, String targetId, String detail,
                       Long actorUserId, String actorEmail, String clientIp) {
        persist(action, result, targetType, targetId, null, detail,
                actorUserId, actorEmail, clientIp);
    }

    /** detail 을 JSON 문자열로 직렬화하는 헬퍼 (실패 시 toString fallback). */
    public String toJson(Map<String, ?> fields) {
        if (fields == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            return String.valueOf(fields);
        }
    }

    // ------------------------------------------------------------------------

    private void persist(AuditAction action, AuditResult result,
                         String targetType, String targetId, String targetName, String detail,
                         Long actorUserId, String actorEmail, String clientIp) {
        AuditLog row = AuditLog.builder()
                .actorUserId(actorUserId)
                .actorEmail(actorEmail)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .targetName(targetName)
                .detail(detail)
                .clientIp(clientIp)
                .result(result)
                .build();
        auditLogRepository.save(row);
    }

    private Actor resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return new Actor(principal.getUserId(), principal.getEmail());
        }
        return new Actor(null, null); // 시스템 / 익명
    }

    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                return clientIpResolver.resolve(request);
            }
        } catch (Exception e) {
            log.debug("[audit] client IP 해소 실패 (무시): {}", e.toString());
        }
        return null;
    }

    private record Actor(Long userId, String email) {
    }
}