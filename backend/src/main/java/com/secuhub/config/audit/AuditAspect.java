package com.secuhub.config.audit;

import com.secuhub.domain.audit.AuditResult;
import com.secuhub.domain.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * {@link Auditable} 가 붙은 메서드의 성공/실패를 감사 로그로 기록한다.
 *
 * <p><b>@Order(0)</b> — 트랜잭션 advice 보다 바깥에서 돌아, SUCCESS 기록은 업무 commit 이후.
 * 예외 시 FAILURE 기록(REQUIRES_NEW 로 보존) 후 재전파.</p>
 *
 * <p><b>targetId / targetName / detail</b> 는 {@link Auditable} 의 SpEL 을 메서드 인자({@code #a0..}/
 * 파라미터명)와 반환값({@code #result})에 대해 평가해 채운다. 평가/기록 실패는 업무 흐름에 영향 없다.</p>
 */
@Slf4j
@Aspect
@Component
@Order(0)
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditService auditService;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(auditable)")
    public Object around(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        Object result = null;
        Throwable thrown = null;
        try {
            result = pjp.proceed();
        } catch (Throwable ex) {
            thrown = ex;
        }

        AuditResult auditResult = (thrown == null) ? AuditResult.SUCCESS : AuditResult.FAILURE;
        String targetId = evalSpel(auditable.targetId(), pjp, result);
        String targetName = evalSpel(auditable.targetName(), pjp, result);
        String detail = (thrown != null)
                ? (thrown.getClass().getSimpleName() + ": " + thrown.getMessage())
                : evalSpel(auditable.detail(), pjp, result);

        safeRecord(auditable, auditResult, targetId, targetName, detail);

        if (thrown != null) {
            throw thrown;
        }
        return result;
    }

    private void safeRecord(Auditable auditable, AuditResult result,
                            String targetId, String targetName, String detail) {
        try {
            String targetType = auditable.targetType().isBlank() ? null : auditable.targetType();
            auditService.record(auditable.action(), result, targetType, targetId, targetName, detail);
        } catch (Throwable t) {
            log.warn("[audit] 기록 실패 (업무 흐름 영향 없음): {}", t.toString());
        }
    }

    /** SpEL 평가 — 인자(#a0../파라미터명) + 반환값(#result). 공백/실패 시 null. */
    private String evalSpel(String expr, ProceedingJoinPoint pjp, Object result) {
        if (expr == null || expr.isBlank()) {
            return null;
        }
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Method method = signature.getMethod();
            MethodBasedEvaluationContext ctx = new MethodBasedEvaluationContext(
                    pjp.getTarget(), method, pjp.getArgs(), paramDiscoverer);
            ctx.setVariable("result", result);
            Object value = parser.parseExpression(expr).getValue(ctx);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            log.debug("[audit] SpEL 평가 실패 (무시): expr={}, {}", expr, t.toString());
            return null;
        }
    }
}