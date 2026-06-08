package com.secuhub.domain.access.service;

import com.secuhub.config.security.IpAccessProperties;
import com.secuhub.domain.access.entity.IpAccessRule;
import com.secuhub.domain.access.repository.IpAccessRuleRepository;
import com.secuhub.domain.access.util.IpCidr;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * v19.0 — 계정별 IP 접근 허용 판정 (BE-1).
 *
 * <p>판정 순서:</p>
 * <ol>
 *   <li>clientIp 해석 실패(null/blank) → 허용 (fail-open, WARN 로그). 잠금 사고 회피 우선.</li>
 *   <li>break-glass({@code always-allow}) 대역에 속하면 → 허용.</li>
 *   <li>해당 계정의 enabled 규칙이 0건 → 허용 (IP 제한 없음, 기본 동작).</li>
 *   <li>규칙 중 하나라도 매칭 → 허용, 아니면 → 거부.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpAccessService {

    private final IpAccessRuleRepository ruleRepository;
    private final IpAccessProperties properties;

    @Transactional(readOnly = true)
    public boolean isAllowed(Long userId, String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            log.warn("클라이언트 IP 해석 실패 — fail-open 허용 (userId={})", userId);
            return true;
        }

        // break-glass — 계정별 규칙 우회
        for (String allow : properties.getAlwaysAllow()) {
            if (IpCidr.matches(allow, clientIp)) {
                return true;
            }
        }

        List<IpAccessRule> rules = ruleRepository.findByUserIdAndEnabledTrue(userId);
        if (rules.isEmpty()) {
            return true; // IP 제한 없음
        }

        return rules.stream().anyMatch(r -> IpCidr.matches(r.getCidr(), clientIp));
    }
}