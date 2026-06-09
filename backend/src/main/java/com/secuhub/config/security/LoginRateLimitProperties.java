package com.secuhub.config.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * v19.9 (SEC-2) — 로그인 Rate Limiting 설정.
 *
 * <pre>
 * app:
 *   security:
 *     login-rate-limit:
 *       enabled: true
 *       max-attempts: 5
 *       window-minutes: 15
 * </pre>
 *
 * IP별로 윈도(window-minutes) 안에서 비밀번호 틀린 시도(401)가 max-attempts 이상이면
 * 윈도가 리셋될 때까지 429 로 차단한다. 로그인 성공(200) 시 카운터는 리셋된다.
 */
@Component
@ConfigurationProperties(prefix = "app.security.login-rate-limit")
@Getter
@Setter
public class LoginRateLimitProperties {

    /** 비활성화 시 필터는 통과만 한다. */
    private boolean enabled = true;

    /** 윈도 내 허용 실패 횟수. 초과 시 차단. */
    private int maxAttempts = 5;

    /** 카운팅 윈도(분). 마지막 실패 기준 경과 시 카운터 리셋. */
    private int windowMinutes = 15;
}