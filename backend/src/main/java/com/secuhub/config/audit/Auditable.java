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
 * <p><b>targetId / targetName / detail (SpEL)</b> — AuditAspect 가 메서드 인자/반환값을 평가.
 * 인자는 {@code #a0, #a1, ...}(항상 사용 가능) 또는 파라미터명({@code #fileId}, {@code -parameters} 시),
 * 반환값은 {@code #result} 로 참조. 평가 실패/공백이면 null.</p>
 *
 * <pre>{@code
 * // 반환 DTO 에 이름이 있으면 #result 로:
 * @Auditable(action = AuditAction.USER_UPDATE, targetType = "User",
 *            targetId = "#a0", targetName = "#result.name")
 * public UserDto.DetailResponse update(Long id, UserDto.UpdateRequest req) { ... }
 *
 * // 인자에 이름이 있으면 #aN 으로:
 * @Auditable(action = AuditAction.FILE_UPLOAD, targetType = "EvidenceFile",
 *            targetId = "#a0", targetName = "#a1.originalFilename")
 * public ... upload(Long evidenceTypeId, MultipartFile file, ...) { ... }
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

    /** 대상 표시명 SpEL (예: "#result.name", "#a1.originalFilename"). 비우면 null. */
    String targetName() default "";

    /** 부가 상세 SpEL (예: "'v' + #a1"). 비우면 null. */
    String detail() default "";
}