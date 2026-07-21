package com.secuhub.domain.user.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.config.security.IpAccessProperties;
import com.secuhub.domain.access.service.IpAccessService;
import com.secuhub.domain.audit.AuditAction;
import com.secuhub.domain.audit.AuditResult;
import com.secuhub.domain.audit.AuditService;
import com.secuhub.domain.user.dto.LoginRequest;
import com.secuhub.domain.user.dto.LoginResponse;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.entity.UserStatus;
import com.secuhub.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final IpAccessService ipAccessService;
    private final IpAccessProperties ipAccessProperties; // v19.29 — XFF 신뢰 모드 판정
    private final AuditService auditService; // AUDIT-1

    /**
     * 로그인 처리
     * - 이메일로 사용자 조회
     * - 비밀번호 검증
     * - 계정 상태 확인 (active만 허용)
     * - v19.0: 계정별 IP 접근 제어 검사 (clientIp 가 허용 규칙에 없으면 403)
     * - JWT 토큰 생성 및 반환
     * - AUDIT-1: 성공/실패/IP 차단을 감사 로그로 기록 (명시 호출 — 필터는 AOP 대상 아님)
     *
     * @param clientIp 호출부(AuthController)가 {@code ClientIpResolver} 로 해석한 클라이언트 IP
     */
    @Transactional
    public LoginResponse login(LoginRequest request, String clientIp) {
        // 사용자 조회
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);
        if (user == null) {
            // 존재하지 않는 이메일 — actor 미상(actorUserId=null), 시도 이메일만 스냅샷
            safeAudit(AuditAction.LOGIN_FAILURE, AuditResult.FAILURE,
                    null, request.getEmail(), clientIp, "존재하지 않는 이메일");
            throw new BusinessException(
                    "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
        }

        // 비밀번호 검증
        if (!passwordEncoder.matches(request.getPassword(), user.getHashedPassword())) {
            safeAudit(AuditAction.LOGIN_FAILURE, AuditResult.FAILURE,
                    user.getId(), user.getEmail(), clientIp, "비밀번호 불일치");
            throw new BusinessException(
                    "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
        }

        // 계정 상태 확인
        if (user.getStatus() != UserStatus.active) {
            safeAudit(AuditAction.LOGIN_FAILURE, AuditResult.FAILURE,
                    user.getId(), user.getEmail(), clientIp, "비활성 계정");
            throw new BusinessException(
                    "비활성화된 계정입니다. 관리자에게 문의하세요.", HttpStatus.FORBIDDEN);
        }

        // v19.0 — 계정별 IP 접근 제어. 자격증명이 맞아도 허용 IP 밖이면 거부.
        if (!ipAccessService.isAllowed(user.getId(), clientIp)) {
            log.warn("로그인 IP 거부: {} ip={}", user.getEmail(), clientIp);
            safeAudit(AuditAction.ACL_BLOCKED, AuditResult.BLOCKED,
                    user.getId(), user.getEmail(), clientIp, "로그인 IP 미허용");
            throw new BusinessException(
                    "허용되지 않은 IP 에서의 접근입니다.", HttpStatus.FORBIDDEN);
        }

        // 마지막 로그인 시각 갱신
        user.updateLastLogin();

        // JWT 토큰 생성
        String token = jwtTokenProvider.createToken(
                user.getId(), user.getEmail(), user.getRole().name());

        log.info("로그인 성공: {} ({})", user.getEmail(), user.getRole());

        safeAudit(AuditAction.LOGIN_SUCCESS, AuditResult.SUCCESS,
                user.getId(), user.getEmail(), clientIp, null);

        return LoginResponse.of(token, user);
    }

    /**
     * v19.29 — 단말 IP 자동 로그인 (비밀번호 없이).
     *
     * <p>폐쇄망 + NAC 으로 단말–IP 가 물리적으로 1:1 고정된 환경 전용. 요청 IP 에 정확히 매핑된
     * 계정을 찾아 비밀번호 검증을 건너뛰고 세션을 발급한다. <b>JWT·권한·감사 본체는 기존 로그인과 동일</b>
     * — 비밀번호 단계만 IP 신원으로 대체.</p>
     *
     * <p>안전조건(모두 fail-closed):</p>
     * <ol>
     *   <li>{@code trust-forwarded-header=true} 면 거부 — XFF 헤더 위조로 다른 단말 IP 위장이 가능해
     *       IP 를 신원으로 쓸 수 없다. 폐쇄망 직결(기본 false)에서만 허용.</li>
     *   <li>요청 IP 에 <b>정확히 1개</b> 계정이 매핑돼야 함(단일 IP 규칙만, 대역 제외). 0개·2개↑면 거부.</li>
     *   <li>계정 active + 자동 로그인 허용 역할(관리자 제외)이어야 함.</li>
     * </ol>
     *
     * @param clientIp 호출부(AuthController)가 {@code ClientIpResolver} 로 해석한 IP
     */
    @Transactional
    public LoginResponse loginByIp(String clientIp) {
        // 1. XFF 신뢰 모드면 IP 신원을 믿을 수 없음 — 자동 로그인 비활성
        if (ipAccessProperties.isTrustForwardedHeader()) {
            log.warn("IP 자동 로그인 거부: trust-forwarded-header=true (XFF 위조 위험) ip={}", clientIp);
            safeAudit(AuditAction.LOGIN_FAILURE, AuditResult.FAILURE,
                    null, null, clientIp, "IP 자동 로그인 비활성(trust-forwarded-header)");
            throw new BusinessException(
                    "이 환경에서는 단말 자동 로그인을 사용할 수 없습니다. 계정으로 로그인하세요.",
                    HttpStatus.FORBIDDEN);
        }

        // 2. IP → 정확히 1개 계정 매핑 (단일 IP 규칙만, fail-closed)
        Long userId = ipAccessService.resolveExactMappedUserId(clientIp).orElse(null);
        if (userId == null) {
            log.warn("IP 자동 로그인 실패: 단일 매핑 계정 없음 ip={}", clientIp);
            safeAudit(AuditAction.LOGIN_FAILURE, AuditResult.FAILURE,
                    null, null, clientIp, "IP 자동 로그인: 단일 매핑 계정 없음");
            throw new BusinessException(
                    "이 단말로 자동 로그인할 수 없습니다. 계정으로 로그인하세요.",
                    HttpStatus.UNAUTHORIZED);
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            safeAudit(AuditAction.LOGIN_FAILURE, AuditResult.FAILURE,
                    null, null, clientIp, "IP 자동 로그인: 계정 조회 실패 userId=" + userId);
            throw new BusinessException(
                    "이 단말로 자동 로그인할 수 없습니다. 계정으로 로그인하세요.",
                    HttpStatus.UNAUTHORIZED);
        }

        // 3. 계정 상태
        if (user.getStatus() != UserStatus.active) {
            safeAudit(AuditAction.LOGIN_FAILURE, AuditResult.FAILURE,
                    user.getId(), user.getEmail(), clientIp, "IP 자동 로그인: 비활성 계정");
            throw new BusinessException(
                    "비활성화된 계정입니다. 관리자에게 문의하세요.", HttpStatus.FORBIDDEN);
        }

        // 4. 역할 — 관리자는 IP 만으로 열지 않음(단말 점유자가 전체 권한 획득 방지)
        if (!isAutoLoginAllowedRole(user.getRole())) {
            log.warn("IP 자동 로그인 거부: 미허용 역할 {} ip={}", user.getRole(), clientIp);
            safeAudit(AuditAction.LOGIN_FAILURE, AuditResult.FAILURE,
                    user.getId(), user.getEmail(), clientIp,
                    "IP 자동 로그인 미허용 역할: " + user.getRole());
            throw new BusinessException(
                    "이 계정은 단말 자동 로그인을 사용할 수 없습니다. 계정으로 로그인하세요.",
                    HttpStatus.FORBIDDEN);
        }

        // 5. 로그인 확정 — 기존과 동일한 토큰/세션
        user.updateLastLogin();
        String token = jwtTokenProvider.createToken(
                user.getId(), user.getEmail(), user.getRole().name());

        log.info("IP 자동 로그인 성공: {} ({}) ip={}", user.getEmail(), user.getRole(), clientIp);
        safeAudit(AuditAction.LOGIN_BY_IP, AuditResult.SUCCESS,
                user.getId(), user.getEmail(), clientIp, null);

        return LoginResponse.of(token, user);
    }

    /**
     * v19.29 — 단말 자동 로그인 허용 역할.
     *
     * <p>v19.29 최초: 관리자(admin) 제외 — 관리자까지 IP 만으로 열면 단말 점유자가 전체 권한을
     * 획득하는 위험이 있어 비밀번호를 유지했다.</p>
     *
     * <p>v19.29a: 운영 결정으로 <b>전 역할 허용</b>(관리자 포함). 폐쇄망 + NAC 으로 단말이 물리적으로
     * 통제되고 단말–사람 1:1 이라는 전제하에 관리자도 자동 로그인. 단말 점유자 권한 상승 리스크는
     * 물리 통제로 감수. 정책 재변경 시 이 한 곳만 수정.</p>
     */
    private boolean isAutoLoginAllowedRole(UserRole role) {
        // v19.29 최초 정책 (관리자 제외) — 운영 결정으로 비활성, 이력 보존:
        // return role != UserRole.admin;
        return true;   // v19.29a — 전 역할 자동 로그인 허용 (관리자 포함)
    }

    @Transactional(readOnly = true)
    public LoginResponse.UserInfo getMyInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));

        return LoginResponse.UserInfo.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .team(user.getTeam())
                .role(user.getRole().name())
                .permissionEvidence(user.getPermissionEvidence())
                .build();
    }

    /** 감사 기록 — 실패가 로그인 흐름을 절대 막지 않도록 예외를 삼킨다. */
    private void safeAudit(AuditAction action, AuditResult result,
                           Long actorUserId, String actorEmail, String clientIp, String detail) {
        try {
            auditService.record(action, result, "User",
                    actorUserId == null ? null : String.valueOf(actorUserId),
                    detail, actorUserId, actorEmail, clientIp);
        } catch (Exception ignore) {
            // 감사 실패는 인증 결과에 영향 주지 않음
        }
    }
}