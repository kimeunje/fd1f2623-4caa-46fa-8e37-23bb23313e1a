package com.secuhub.domain.access.util;

import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * v19.0 — CIDR / 단일 IP 매칭·검증 (BE-1).
 *
 * <p>Spring Security 의 {@link IpAddressMatcher} 를 래핑 — IPv4·IPv6 + CIDR 표기를
 * 모두 지원하며 바퀴를 재발명하지 않는다. 단일 IP("192.168.1.10")는 자동으로
 * {@code /32}(또는 IPv6 {@code /128}) 단일 호스트로 취급된다.</p>
 *
 * <p>잘못된 입력은 예외 대신 false 로 흡수 — enforcement 경로에서 한 규칙의 오타가
 * 전체 검사를 깨뜨리지 않도록. 규칙 저장 시점 검증은 {@link #isValid(String)} 사용.</p>
 */
public final class IpCidr {

    private IpCidr() {
    }

    /**
     * {@code ip} 가 {@code cidr} 범위에 속하는지. 입력 오류 시 false.
     */
    public static boolean matches(String cidr, String ip) {
        if (cidr == null || ip == null) {
            return false;
        }
        try {
            return new IpAddressMatcher(cidr.trim()).matches(ip.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 저장 가능한 CIDR/IP 표기인지 검증 (BE-2 의 규칙 등록 검증에서 사용).
     *
     * <p>prefix 길이 범위(IPv4 0~32 / IPv6 0~128)를 먼저 직접 확인한 뒤
     * {@link IpAddressMatcher} 생성·매칭으로 형식을 최종 검증한다 — 라이브러리 버전에 따라
     * prefix 범위 검증 시점이 다를 수 있어 이중으로 막는다.</p>
     */
    public static boolean isValid(String cidr) {
        if (cidr == null) {
            return false;
        }
        String c = cidr.trim();
        if (c.isEmpty()) {
            return false;
        }

        boolean ipv6 = c.contains(":");

        if (c.contains("/")) {
            String[] parts = c.split("/", -1);
            if (parts.length != 2) {
                return false;
            }
            int prefix;
            try {
                prefix = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                return false;
            }
            int max = ipv6 ? 128 : 32;
            if (prefix < 0 || prefix > max) {
                return false;
            }
        }

        String sample = ipv6 ? "::1" : "127.0.0.1";
        try {
            // 형식이 잘못되면 생성 또는 매칭에서 IllegalArgumentException.
            new IpAddressMatcher(c).matches(sample);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}