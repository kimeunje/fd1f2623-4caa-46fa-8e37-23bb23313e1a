package com.secuhub.config.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * v19.11 (SEC-1 후속) — 보안 헤더 값 외부화.
 *
 * <p>v19.8 에서 SecurityConfig 에 하드코딩했던 CSP / Referrer-Policy / Permissions-Policy 를
 * 프로퍼티로 빼, 재빌드 없이 application-prod.yml 에서 튜닝 가능하게 한다. 기본값은 v19.8 과
 * 동일(코드 fallback) — dev/test 는 prod.yml 미적용이므로 기본값으로 동작.</p>
 *
 * <pre>
 * app:
 *   security:
 *     headers:
 *       content-security-policy: "default-src 'self'; ..."
 *       referrer-policy: "strict-origin-when-cross-origin"
 *       permissions-policy: "geolocation=(), camera=(), microphone=(), payment=(), usb=()"
 * </pre>
 *
 * <p>폐쇄망 평문(HTTP) 이므로 HSTS 는 다루지 않는다. content-security-policy 를 빈 문자열로
 * 두면 CSP 헤더를 비활성(미발행)한다.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.security.headers")
@Getter
@Setter
public class SecurityHeadersProperties {

    /** Content-Security-Policy. 빈 문자열이면 CSP 헤더 미발행. */
    private String contentSecurityPolicy =
            "default-src 'self'; " +
            "script-src 'self'; " +
            "style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; " +
            "font-src 'self' data:; " +
            "connect-src 'self'; " +
            "object-src 'none'; " +
            "frame-ancestors 'none'; " +
            "base-uri 'self'; " +
            "form-action 'self'";

    /** Referrer-Policy. */
    private String referrerPolicy = "strict-origin-when-cross-origin";

    /** Permissions-Policy. */
    private String permissionsPolicy = "geolocation=(), camera=(), microphone=(), payment=(), usb=()";
}