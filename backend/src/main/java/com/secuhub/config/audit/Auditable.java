package com.secuhub.config.audit;

import com.secuhub.domain.audit.AuditAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 서비스 메서드에 붙이면 {@link AuditAspect} 가 성공/실패를 감사 로그로 남긴다.
 *
 * <p>대상은 보안 + 민감 변경 메서드로 한정한다 (조회성 GET 금지 — L_OVER_ENGINEER_DETECT).
 * <p>v1 은 action + targetType 만 캡처. 세밀한 targetId / 인자 캡처는 후속(SpEL 등)으로 보류.
 *
 * <pre>{@code
 * @Auditable(action = AuditAction.SCRIPT_DELETE, targetType = "Script")
 * public void delete(Long scriptId) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /** 기록할 액션. */
    AuditAction action();

    /** 대상 도메인 타입 표기 (예: "Script", "User"). 비우면 target_type = null. */
    String targetType() default "";
}