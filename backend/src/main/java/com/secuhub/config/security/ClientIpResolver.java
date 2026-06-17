package com.secuhub.config.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * v19.0 — 클라이언트 IP 해석 (BE-1).
 *
 * <p>{@code trust-forwarded-header=true} 면 {@code X-Forwarded-For} 의 첫 hop 을,
 * 아니면 {@code remoteAddr} 를 사용. 필터 / 로그인(AuthService) / 감사(AuditService)가
 * 같은 규칙으로 IP 를 뽑도록 단일 컴포넌트로 둔다.</p>
 *
 * <p>v19.14 — IPv4 정규화. 운영은 IPv4 전용이지만 JVM 듀얼스택이면 localhost 가
 * IPv6 루프백({@code ::1} = {@code 0:0:0:0:0:0:0:1})으로, 혹은 IPv4-mapped
 * ({@code ::ffff:x.x.x.x})로 들어온다. IP 를 해석하는 이 단일 지점에서 IPv4 로 맞춰,
 * 이를 쓰는 모든 곳(감사 저장·검색 / IP ACL / RateLimit)이 일관된 IPv4 표기를 받게 한다.
 * (기동 인자 {@code -Djava.net.preferIPv4Stack=true} 없이도 동작.)</p>
 */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private static final String XFF_HEADER = "X-Forwarded-For";
    private static final String IPV4_MAPPED_PREFIX = "::ffff:";

    private final IpAccessProperties properties;

    public String resolve(HttpServletRequest request) {
        return normalizeIpv4(resolveRaw(request));
    }

    private String resolveRaw(HttpServletRequest request) {
        if (properties.isTrustForwardedHeader()) {
            String forwarded = request.getHeader(XFF_HEADER);
            if (StringUtils.hasText(forwarded)) {
                // "client, proxy1, proxy2" — 첫 hop 이 원 클라이언트.
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * IPv6 루프백 / IPv4-mapped 표기를 IPv4 로 정규화. 그 외(실제 IPv4 등)는 그대로 둔다.
     */
    private String normalizeIpv4(String ip) {
        if (ip == null) {
            return null;
        }
        if (ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) {
            return "127.0.0.1";
        }
        if (ip.regionMatches(true, 0, IPV4_MAPPED_PREFIX, 0, IPV4_MAPPED_PREFIX.length())) {
            String tail = ip.substring(IPV4_MAPPED_PREFIX.length());
            if (tail.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                return tail;
            }
        }
        return ip;
    }
}