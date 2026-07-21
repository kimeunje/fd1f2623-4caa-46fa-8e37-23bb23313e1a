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
import java.util.Optional;

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
 *
 * <p>v19.29 — 단말 자동 로그인용 역방향 조회 {@link #resolveExactMappedUserId(String)} 추가.
 * enforcement 경로(fail-open)와 정반대로 <b>fail-closed + 정확 매칭</b>이라는 점에 주의.</p>
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

    /**
     * v19.29 — 단말 IP 자동 로그인용 역방향 조회.
     *
     * <p>"이 IP 는 어느 계정인가"를 찾는다. enforcement 의 {@link #isAllowed}(대역 포함 검사, fail-open)와
     * 정반대로, <b>단일 IP 정확 매칭 + fail-closed</b>: cidr 가 {@code clientIp} 또는 {@code clientIp+"/32"} 인
     * enabled 규칙만 후보로 삼는다(대역 규칙은 여러 단말을 덮으므로 배제 — 한 단말=한 계정 불변식). break-glass
     * ({@code always-allow})는 신원 판정에 끼지 않는다(여기서 아예 조회 안 함).</p>
     *
     * @return 정확히 <b>1개</b> 계정에 매핑되면 그 userId, 0개(미배정)·2개↑(모호)면 empty.
     */
    @Transactional(readOnly = true)
    public Optional<Long> resolveExactMappedUserId(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return Optional.empty();
        }
        List<IpAccessRule> rules =
                ruleRepository.findByEnabledTrueAndCidrIn(List.of(clientIp, clientIp + "/32"));
        List<Long> userIds = rules.stream()
                .map(IpAccessRule::getUserId)
                .distinct()
                .toList();
        return userIds.size() == 1 ? Optional.of(userIds.get(0)) : Optional.empty();
    }
}