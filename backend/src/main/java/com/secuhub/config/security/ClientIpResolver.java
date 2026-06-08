package com.secuhub.config.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * v19.0 — 클라이언트 IP 해석 (BE-1).
 *
 * <p>{@code trust-forwarded-header=true} 면 {@code X-Forwarded-For} 의 첫 hop 을,
 * 아니면 {@code remoteAddr} 를 사용. 필터와 로그인(AuthService) 양쪽에서 같은 규칙으로
 * IP 를 뽑기 위해 단일 컴포넌트로 둔다.</p>
 */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private static final String XFF_HEADER = "X-Forwarded-For";

    private final IpAccessProperties properties;

    public String resolve(HttpServletRequest request) {
        if (properties.isTrustForwardedHeader()) {
            String forwarded = request.getHeader(XFF_HEADER);
            if (StringUtils.hasText(forwarded)) {
                // "client, proxy1, proxy2" — 첫 hop 이 원 클라이언트.
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}