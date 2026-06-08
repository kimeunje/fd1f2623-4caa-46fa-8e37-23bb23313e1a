package com.secuhub.domain.user.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.access.service.IpAccessService;
import com.secuhub.domain.user.dto.LoginRequest;
import com.secuhub.domain.user.dto.LoginResponse;
import com.secuhub.domain.user.entity.User;
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

    /**
     * 로그인 처리
     * - 이메일로 사용자 조회
     * - 비밀번호 검증
     * - 계정 상태 확인 (active만 허용)
     * - v19.0: 계정별 IP 접근 제어 검사 (clientIp 가 허용 규칙에 없으면 403)
     * - JWT 토큰 생성 및 반환
     *
     * @param clientIp 호출부(AuthController)가 {@code ClientIpResolver} 로 해석한 클라이언트 IP
     */
    @Transactional
    public LoginResponse login(LoginRequest request, String clientIp) {
        // 사용자 조회
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException(
                        "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED));

        // 비밀번호 검증
        if (!passwordEncoder.matches(request.getPassword(), user.getHashedPassword())) {
            throw new BusinessException(
                    "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
        }

        // 계정 상태 확인
        if (user.getStatus() != UserStatus.active) {
            throw new BusinessException(
                    "비활성화된 계정입니다. 관리자에게 문의하세요.", HttpStatus.FORBIDDEN);
        }

        // v19.0 — 계정별 IP 접근 제어. 자격증명이 맞아도 허용 IP 밖이면 거부.
        if (!ipAccessService.isAllowed(user.getId(), clientIp)) {
            log.warn("로그인 IP 거부: {} ip={}", user.getEmail(), clientIp);
            throw new BusinessException(
                    "허용되지 않은 IP 에서의 접근입니다.", HttpStatus.FORBIDDEN);
        }

        // 마지막 로그인 시각 갱신
        user.updateLastLogin();

        // JWT 토큰 생성
        String token = jwtTokenProvider.createToken(
                user.getId(), user.getEmail(), user.getRole().name());

        log.info("로그인 성공: {} ({})", user.getEmail(), user.getRole());

        return LoginResponse.of(token, user);
    }

    /**
     * 현재 로그인된 사용자 정보 조회.
     *
     * <p>Phase 3 cleanup (2026-05-04): permissionVuln 매핑 제거.</p>
     */
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
}