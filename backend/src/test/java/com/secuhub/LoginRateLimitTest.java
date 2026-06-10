package com.secuhub;

import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.entity.UserStatus;
import com.secuhub.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v19.9 (SEC-2) — 로그인 Rate Limiting.
 *
 * <p>Rate limit 카운터는 필터 bean 의 in-memory 상태라 테스트 메서드 간 공유된다.
 * 간섭 방지를 위해 테스트마다 고유 IP 를 사용한다(remoteAddr 주입). 기본 임계는 5회/15분.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("v19.9 - 로그인 Rate Limiting")
class LoginRateLimitTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private RequestPostProcessor ip(String addr) {
        return request -> {
            request.setRemoteAddr(addr);
            return request;
        };
    }

    private String body(String email, String pw) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + pw + "\"}";
    }

    @Test
    @DisplayName("[RateLimit] 동일 IP 5회 실패 후 6회째 429 차단")
    void testBlockAfterMaxFailures() throws Exception {
        String addr = "198.51.100.11";
        // 없는 계정 → 401 (자격증명 불일치). 5회 카운트.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login").with(ip(addr))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("nf-rl@test.com", "wrong")))
                    .andExpect(status().isUnauthorized());
        }
        // 6회째 → bcrypt 도달 전 429
        mockMvc.perform(post("/api/v1/auth/login").with(ip(addr))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("nf-rl@test.com", "wrong")))
                .andExpect(status().isTooManyRequests());

        System.out.println("✅ [RateLimit] 5회 실패 → 429");
    }

    @Test
    @Transactional
    @DisplayName("[RateLimit] 로그인 성공 시 카운터 리셋")
    void testSuccessResetsCounter() throws Exception {
        userRepository.save(User.builder()
                .email("rl-ok@test.com")
                .name("리셋")
                .hashedPassword(passwordEncoder.encode("password123"))
                .team("팀")
                .role(UserRole.developer)
                .permissionEvidence(false)
                .status(UserStatus.active)
                .build());

        String addr = "198.51.100.22";
        // 4회 실패 (임계 미만)
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login").with(ip(addr))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("rl-ok@test.com", "wrong")))
                    .andExpect(status().isUnauthorized());
        }
        // 성공 → 카운터 리셋
        mockMvc.perform(post("/api/v1/auth/login").with(ip(addr))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("rl-ok@test.com", "password123")))
                .andExpect(status().isOk());
        // 다시 4회 실패해도 429 아님(리셋되어 새 윈도)
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login").with(ip(addr))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("rl-ok@test.com", "wrong")))
                    .andExpect(status().isUnauthorized());
        }

        System.out.println("✅ [RateLimit] 성공 시 리셋");
    }

    @Test
    @DisplayName("[RateLimit] 검증 오류(400)는 카운트되지 않음")
    void testValidationErrorNotCounted() throws Exception {
        String addr = "198.51.100.33";
        // 빈 자격증명 → 400. 임계(5)를 넘겨 8회 보내도 429 가 아님.
        for (int i = 0; i < 8; i++) {
            mockMvc.perform(post("/api/v1/auth/login").with(ip(addr))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("", "")))
                    .andExpect(status().isBadRequest());
        }

        System.out.println("✅ [RateLimit] 400 비카운트");
    }
}