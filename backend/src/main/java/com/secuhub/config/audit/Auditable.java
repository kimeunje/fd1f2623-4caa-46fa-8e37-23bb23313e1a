package com.secuhub.config.audit;

import com.secuhub.domain.audit.AuditAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 서비스 메서드에 붙이면 {@link AuditAspect} 가 성공/실패를 감사 로그로 남긴다.
 *
 * <p>대상은 보안 + 민감 변경 메서드로 한정(조회성 GET 금지 — L_OVER_ENGINEER_DETECT).</p>
 *
 * <p><b>targetId / detail (SpEL)</b> — AuditAspect 가 메서드 인자/반환값을 대상으로 평가.
 * 인자는 위치 변수 {@code #a0, #a1, ...}(항상 사용 가능) 또는 파라미터명({@code #fileId},
 * {@code -parameters} 컴파일 시) 으로, 반환값은 {@code #result} 로 참조. 평가 실패/공백이면 null.</p>
 *
 * <pre>{@code
 * @Auditable(action = AuditAction.SCRIPT_DELETE, targetType = "Script", targetId = "#a0")
 * public void delete(Long scriptId) { ... }
 *
 * @Auditable(action = AuditAction.USER_CREATE, targetType = "User",
 *            targetId = "#result.id", detail = "#a0.email")
 * public UserDto.DetailResponse create(UserDto.CreateRequest request) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /** 기록할 액션. */
    AuditAction action();

    /** 대상 도메인 타입 표기 (예: "Script", "User", "EvidenceFile"). 비우면 null. */
    String targetType() default "";

    /** 대상 식별자 SpEL (예: "#a0", "#result.id"). 비우면 null. */
    String targetId() default "";

    /** 부가 상세 SpEL (예: "#a1.originalFilename", "'v' + #a1"). 비우면 null. */
    String detail() default "";
}