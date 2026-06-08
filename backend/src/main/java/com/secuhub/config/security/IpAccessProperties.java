package com.secuhub.config.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * v19.0 — IP 접근 제어 설정 (BE-1).
 *
 * <pre>
 * app:
 *   security:
 *     ip-acl:
 *       trust-forwarded-header: false   # nginx 등 reverse proxy 뒤면 true
 *       always-allow:                   # break-glass — 계정별 규칙을 우회하는 비상 대역
 *         - 10.0.0.0/8
 * </pre>
 *
 * <p><b>trust-forwarded-header</b>: false(기본)면 {@code request.getRemoteAddr()} 사용,
 * true 면 {@code X-Forwarded-For} 의 첫 hop 을 클라이언트 IP 로 신뢰. 폐쇄망 직결이면 false.</p>
 *
 * <p><b>always-allow</b>: 여기 속한 IP 는 계정별 규칙과 무관하게 항상 허용. 운영자가 자기 IP 를
 * 잘못 제한해 잠기는 사고를 막는 안전장치. 기본 비어 있음(효과 없음).</p>
 */
@Component
@ConfigurationProperties(prefix = "app.security.ip-acl")
@Getter
@Setter
public class IpAccessProperties {

    private boolean trustForwardedHeader = false;

    private List<String> alwaysAllow = new ArrayList<>();
}