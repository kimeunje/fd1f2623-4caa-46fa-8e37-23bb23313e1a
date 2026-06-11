package com.secuhub.config.audit;

import com.secuhub.domain.audit.AuditResult;
import com.secuhub.domain.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link Auditable} 가 붙은 메서드의 성공/실패를 감사 로그로 기록한다.
 *
 * <p><b>@Order(0)</b> — 트랜잭션 advice(기본 LOWEST_PRECEDENCE)보다 <b>바깥</b>에서 돌게 한다.
 * 그래야 {@code proceed()} 가 업무 트랜잭션 commit 까지 포함하고, SUCCESS 기록은 항상
 * 업무가 실제 커밋된 뒤 남는다. (예외 시엔 업무 tx 롤백 + FAILURE 기록은 REQUIRES_NEW 로 보존.)
 *
 * <p>감사 기록은 {@code REQUIRES_NEW} 새 트랜잭션이므로, 여기서 try-catch 로 감싸 감사 실패가
 * 절대 업무 흐름을 깨지 않도록 한다.
 */
@Slf4j
@Aspect
@Component
@Order(0)
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditService auditService;

    @Around("@annotation(auditable)")
    public Object around(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable ex) {
            safeRecord(auditable, AuditResult.FAILURE,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw ex;
        }
        safeRecord(auditable, AuditResult.SUCCESS, null);
        return result;
    }

    private void safeRecord(Auditable auditable, AuditResult result, String detail) {
        try {
            String targetType = auditable.targetType().isBlank() ? null : auditable.targetType();
            auditService.record(auditable.action(), result, targetType, null, detail);
        } catch (Throwable t) {
            log.warn("[audit] 기록 실패 (업무 흐름 영향 없음): {}", t.toString());
        }
    }
}