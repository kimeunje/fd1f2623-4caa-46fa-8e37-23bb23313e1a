package com.secuhub.domain.user.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.config.jwt.JwtTokenProvider;
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

    /**
     * 로그인 처리
     * - 이메일로 사용자 조회
     * - 비밀번호 검증
     * - 계정 상태 확인 (active만 허용)
     * - JWT 토큰 생성 및 반환
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
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

        // 마지막 로그인 시각 갱신
        user.updateLastLogin();

        // JWT 토큰 생성
        String token = jwtTokenProvider.createToken(
                user.getId(), user.getEmail(), user.getRole().name());

        log.info("로그인 성공: {} ({})", user.getEmail(), user.getRole());

        return LoginResponse.of(token, user);
    }

    /**
     * 현재 로그인된 사용자 정보 조회
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
                .permissionVuln(user.getPermissionVuln())
                .build();
    }
}
