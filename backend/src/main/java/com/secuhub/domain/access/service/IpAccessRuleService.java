package com.secuhub.domain.access.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.config.security.IpAccessProperties;
import com.secuhub.domain.access.dto.IpAccessRuleDto;
import com.secuhub.domain.access.entity.IpAccessRule;
import com.secuhub.domain.access.repository.IpAccessRuleRepository;
import com.secuhub.domain.access.util.IpCidr;
import com.secuhub.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * v19.1 — 계정별 IP 접근 규칙 관리 (BE-2).
 *
 * <p>enforcement 판정({@link IpAccessService})과 분리된 "규칙 CRUD" 책임.
 * 관리자만 호출 (Controller {@code @PreAuthorize("hasRole('ADMIN')")}).</p>
 *
 * <p><b>자기 잠금 방지</b>: 대상 계정이 호출 관리자 본인이고, 이번 변경을 적용하면
 * 본인의 현재 IP 에서 접근이 거부되는 경우 409 로 막는다. 타인 계정 제한은 admin 의
 * 정상 권한이라 막지 않는다(잠금 위험은 그 계정 소유자에게 안내될 사안).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpAccessRuleService {

    private final IpAccessRuleRepository ruleRepository;
    private final UserRepository userRepository;
    private final IpAccessProperties properties;

    @Transactional(readOnly = true)
    public List<IpAccessRuleDto.Response> list(Long userId) {
        requireUser(userId);
        return ruleRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(IpAccessRuleDto.Response::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public IpAccessRuleDto.Response create(Long userId, IpAccessRuleDto.CreateRequest request,
                                           Long actingUserId, String actingIp) {
        requireUser(userId);
        String cidr = trim(request.getCidr());
        validateCidr(cidr);
        boolean enabled = request.getEnabled() == null || request.getEnabled();

        if (userId.equals(actingUserId)) {
            List<String> resulting = enabledCidrs(userId);
            if (enabled) {
                resulting.add(cidr);
            }
            assertNotSelfLockout(resulting, actingIp);
        }

        IpAccessRule rule = ruleRepository.save(IpAccessRule.builder()
                .userId(userId)
                .cidr(cidr)
                .description(blankToNull(request.getDescription()))
                .enabled(enabled)
                .build());

        log.info("IP 규칙 생성: userId={} cidr={} enabled={}", userId, cidr, enabled);
        return IpAccessRuleDto.Response.from(rule);
    }

    @Transactional
    public IpAccessRuleDto.Response update(Long userId, Long ruleId, IpAccessRuleDto.UpdateRequest request,
                                           Long actingUserId, String actingIp) {
        IpAccessRule rule = requireRule(userId, ruleId);

        String newCidr = rule.getCidr();
        if (request.getCidr() != null) {
            newCidr = trim(request.getCidr());
            validateCidr(newCidr);
        }
        boolean newEnabled = request.getEnabled() != null ? request.getEnabled() : rule.isEnabled();

        if (userId.equals(actingUserId)) {
            // 이번 수정 후의 활성 규칙 집합 = (이 규칙 제외한 기존 활성) + (이 규칙이 활성이면 새 cidr)
            List<String> resulting = ruleRepository.findByUserIdAndEnabledTrue(userId).stream()
                    .filter(r -> !r.getId().equals(ruleId))
                    .map(IpAccessRule::getCidr)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (newEnabled) {
                resulting.add(newCidr);
            }
            assertNotSelfLockout(resulting, actingIp);
        }

        if (request.getCidr() != null) {
            rule.updateCidr(newCidr);
        }
        if (request.getDescription() != null) {
            rule.updateDescription(blankToNull(request.getDescription()));
        }
        if (request.getEnabled() != null) {
            rule.setEnabled(newEnabled);
        }

        log.info("IP 규칙 수정: ruleId={} userId={}", ruleId, userId);
        return IpAccessRuleDto.Response.from(rule);
    }

    @Transactional
    public void delete(Long userId, Long ruleId) {
        IpAccessRule rule = requireRule(userId, ruleId);
        // 삭제는 허용 범위를 줄이기만 함 → 최악의 경우 규칙 0건(= 제한 없음)이라 잠금 불가. 가드 불필요.
        ruleRepository.delete(rule);
        log.info("IP 규칙 삭제: ruleId={} userId={}", ruleId, userId);
    }

    // ────────────────────────────── helpers ──────────────────────────────

    /**
     * resultingEnabledCidrs 적용 시 actingIp 가 접근 불가가 되면 409.
     * actingIp 해석 불가 시 가드 생략(enforcement 가 fail-open 이므로 일관).
     */
    private void assertNotSelfLockout(List<String> resultingEnabledCidrs, String actingIp) {
        if (actingIp == null || actingIp.isBlank()) {
            return;
        }
        for (String allow : properties.getAlwaysAllow()) {
            if (IpCidr.matches(allow, actingIp)) {
                return;
            }
        }
        if (resultingEnabledCidrs.isEmpty()) {
            return; // 제한 없음
        }
        boolean allowed = resultingEnabledCidrs.stream().anyMatch(c -> IpCidr.matches(c, actingIp));
        if (!allowed) {
            throw new BusinessException(
                    "이 변경을 적용하면 현재 IP(" + actingIp + ")에서 본인 계정이 잠깁니다. "
                            + "현재 IP를 허용하는 규칙을 먼저 추가하세요.",
                    HttpStatus.CONFLICT);
        }
    }

    private List<String> enabledCidrs(Long userId) {
        return ruleRepository.findByUserIdAndEnabledTrue(userId).stream()
                .map(IpAccessRule::getCidr)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void validateCidr(String cidr) {
        if (!IpCidr.isValid(cidr)) {
            throw new BusinessException(
                    "올바른 IP 또는 CIDR 표기가 아닙니다: " + cidr, HttpStatus.BAD_REQUEST);
        }
    }

    private void requireUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("사용자", userId);
        }
    }

    private IpAccessRule requireRule(Long userId, Long ruleId) {
        return ruleRepository.findByIdAndUserId(ruleId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("IP 접근 규칙", ruleId));
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}