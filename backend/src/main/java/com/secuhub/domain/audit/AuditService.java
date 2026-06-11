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
 * 감사 로그 기록 진입점 (AUDIT-1).
 *
 * <h3>설계 메모</h3>
 * <ul>
 *   <li><b>동기 기록</b> (v1). 비동기는 부모 tx commit 전 race + cross-thread LAZY 위험
 *       (L_ASYNC_BEFORE_TX_COMMIT / L_JPA_ASYNC_LAZY_CROSS_THREAD)이라 성능 이슈 발생 시 후속.</li>
 *   <li><b>{@code REQUIRES_NEW}</b> — 감사 기록을 호출자(업무) 트랜잭션과 분리.
 *       감사 실패가 업무 트랜잭션을 깨지 않고, 업무 롤백이 감사 기록을 지우지 않음.
 *       {@link com.secuhub.config.audit.AuditAspect} 가 tx advice 보다 <b>바깥</b>(@Order)에서
 *       돌도록 해, SUCCESS 기록은 항상 업무 commit 이후에 남는다.</li>
 *   <li><b>예외 비전파 책임은 호출자</b> — 본 메서드는 실패 시 예외를 던진다(자체 새 tx 롤백).
 *       모든 호출자(Aspect / 필터)는 try-catch 로 감싸 감사 실패가 본 흐름을 막지 않게 한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    /**
     * AOP / 서비스 계층용. actor 는 SecurityContext, IP 는 현재 요청에서 자동 해소.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, AuditResult result,
                       String targetType, String targetId, String detail) {
        Actor actor = resolveActor();
        persist(action, result, targetType, targetId, detail,
                actor.userId(), actor.email(), resolveClientIp());
    }

    /**
     * 필터 / 특수 지점용. SecurityContext 가 비어있거나(미인증 로그인 실패) actor·IP 를
     * 호출자가 이미 알고 있는 경우 명시 전달.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, AuditResult result,
                       String targetType, String targetId, String detail,
                       Long actorUserId, String actorEmail, String clientIp) {
        persist(action, result, targetType, targetId, detail, actorUserId, actorEmail, clientIp);
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
                         String targetType, String targetId, String detail,
                         Long actorUserId, String actorEmail, String clientIp) {
        AuditLog row = AuditLog.builder()
                .actorUserId(actorUserId)
                .actorEmail(actorEmail)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .detail(detail)
                .clientIp(clientIp)
                .result(result)
                .build();
        auditLogRepository.save(row);
    }

    private Actor resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            // UserPrincipal(userId, email, role) — JwtAuthenticationFilter 가 주입.
            return new Actor(principal.getUserId(), principal.getEmail());
        }
        return new Actor(null, null); // 시스템 / 익명
    }

    /**
     * 현재 요청에서 클라이언트 IP 해소. 요청 컨텍스트가 없으면(스케줄러 등) null.
     *
     * <p>⚠ ClientIpResolver 시그니처 가정: {@code String resolve(HttpServletRequest)}
     * (v19.0/v19.9 필터에서 재사용). 실제 시그니처가 다르면 이 한 줄만 조정.
     */
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