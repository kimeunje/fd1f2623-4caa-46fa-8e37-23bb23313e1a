package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.user.dto.LoginRequest;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.entity.UserStatus;
import com.secuhub.domain.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * JWT 인증 시스템 통합 테스트
 *
 * 검증 항목:
 * - JwtTokenProvider: 토큰 생성, 검증, 파싱
 * - 로그인 API: 정상 로그인, 잘못된 비밀번호, 비활성 계정
 * - 인증 보호: 토큰 없이 접근 시 401
 * - 인가 보호: 권한 없는 리소스 접근 시 403
 * - /api/v1/auth/me: 토큰으로 내 정보 조회
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthenticationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    // ========================================
    // 1. JwtTokenProvider 단위 검증
    // ========================================

    @Test
    @Order(1)
    @DisplayName("[JWT] 토큰 생성 및 파싱")
    void testTokenCreateAndParse() {
        String token = jwtTokenProvider.createToken(1L, "test@test.com", "admin");

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getUserId(token)).isEqualTo(1L);
        assertThat(jwtTokenProvider.getEmail(token)).isEqualTo("test@test.com");
        assertThat(jwtTokenProvider.getRole(token)).isEqualTo("admin");

        System.out.println("✅ [JWT] 토큰 생성 및 파싱 정상");
    }

    @Test
    @Order(2)
    @DisplayName("[JWT] 잘못된 토큰 검증 실패")
    void testInvalidToken() {
        assertThat(jwtTokenProvider.validateToken("invalid.token.here")).isFalse();
        assertThat(jwtTokenProvider.validateToken("")).isFalse();
        assertThat(jwtTokenProvider.validateToken(null)).isFalse();

        System.out.println("✅ [JWT] 잘못된 토큰 검증 실패 정상");
    }

    // ========================================
    // 2. 로그인 API
    // ========================================

    @Test
    @Order(3)
    @DisplayName("[Auth] 정상 로그인 → 토큰 반환")
    @Transactional
    void testLoginSuccess() throws Exception {
        // 테스트 사용자 생성
        userRepository.save(User.builder()
                .email("login@test.com")
                .name("테스트관리자")
                .hashedPassword(passwordEncoder.encode("password123"))
                .team("보안팀")
                .role(UserRole.admin)
                .permissionEvidence(true)
                .permissionVuln(true)
                .build());

        LoginRequest request = new LoginRequest("login@test.com", "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("로그인 성공"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.user.email").value("login@test.com"))
                .andExpect(jsonPath("$.data.user.name").value("테스트관리자"))
                .andExpect(jsonPath("$.data.user.role").value("admin"));

        System.out.println("✅ [Auth] 정상 로그인 → 토큰 반환 정상");
    }

    @Test
    @Order(4)
    @DisplayName("[Auth] 잘못된 비밀번호 → 401")
    @Transactional
    void testLoginWrongPassword() throws Exception {
        userRepository.save(User.builder()
                .email("wrong@test.com")
                .name("테스트")
                .hashedPassword(passwordEncoder.encode("correctPassword"))
                .role(UserRole.developer)
                .build());

        LoginRequest request = new LoginRequest("wrong@test.com", "wrongPassword");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));

        System.out.println("✅ [Auth] 잘못된 비밀번호 → 401 정상");
    }

    @Test
    @Order(5)
    @DisplayName("[Auth] 비활성 계정 → 403")
    @Transactional
    void testLoginInactiveAccount() throws Exception {
        User user = userRepository.save(User.builder()
                .email("inactive@test.com")
                .name("비활성")
                .hashedPassword(passwordEncoder.encode("password123"))
                .role(UserRole.developer)
                .build());
        user.updateStatus(UserStatus.inactive);
        userRepository.save(user);

        LoginRequest request = new LoginRequest("inactive@test.com", "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("비활성화된 계정입니다. 관리자에게 문의하세요."));

        System.out.println("✅ [Auth] 비활성 계정 → 403 정상");
    }

    @Test
    @Order(6)
    @DisplayName("[Auth] 존재하지 않는 이메일 → 401")
    void testLoginNonExistentEmail() throws Exception {
        LoginRequest request = new LoginRequest("nonexist@test.com", "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        System.out.println("✅ [Auth] 존재하지 않는 이메일 → 401 정상");
    }

    // ========================================
    // 3. 인증 보호 (토큰 없이 접근)
    // ========================================

    @Test
    @Order(7)
    @DisplayName("[Security] 토큰 없이 보호된 API 접근 → 401")
    void testUnauthorizedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다. 로그인 후 이용해주세요."));

        System.out.println("✅ [Security] 토큰 없이 보호된 API 접근 → 401 정상");
    }

    // ========================================
    // 4. 인가 보호 (권한 없는 리소스 접근)
    // ========================================

    @Test
    @Order(8)
    @DisplayName("[Security] 개발자 역할로 관리자 API 접근 → 403")
    void testForbiddenAccess() throws Exception {
        // 개발자 토큰 생성
        String devToken = jwtTokenProvider.createToken(999L, "dev@test.com", "developer");

        mockMvc.perform(get("/api/v1/frameworks")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden());

        System.out.println("✅ [Security] 개발자 역할로 관리자 API 접근 → 403 정상");
    }

    // ========================================
    // 5. /api/v1/auth/me (내 정보 조회)
    // ========================================

    @Test
    @Order(9)
    @DisplayName("[Auth] 토큰으로 내 정보 조회")
    @Transactional
    void testGetMyInfo() throws Exception {
        User user = userRepository.save(User.builder()
                .email("me@test.com")
                .name("나자신")
                .hashedPassword(passwordEncoder.encode("password123"))
                .team("백엔드팀")
                .role(UserRole.developer)
                .permissionEvidence(false)
                .permissionVuln(true)
                .build());

        String token = jwtTokenProvider.createToken(user.getId(), user.getEmail(), user.getRole().name());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("me@test.com"))
                .andExpect(jsonPath("$.data.name").value("나자신"))
                .andExpect(jsonPath("$.data.team").value("백엔드팀"))
                .andExpect(jsonPath("$.data.role").value("developer"))
                .andExpect(jsonPath("$.data.permissionEvidence").value(false))
                .andExpect(jsonPath("$.data.permissionVuln").value(true));

        System.out.println("✅ [Auth] 토큰으로 내 정보 조회 정상");
    }

    // ========================================
    // 6. 유효성 검증
    // ========================================

    @Test
    @Order(10)
    @DisplayName("[Auth] 빈 이메일/비밀번호 → 400 Validation Error")
    void testLoginValidation() throws Exception {
        LoginRequest request = new LoginRequest("", "");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        System.out.println("✅ [Auth] 빈 이메일/비밀번호 → 400 Validation Error 정상");
    }

    // ========================================
    // 7. Swagger는 인증 없이 접근 가능
    // ========================================

    @Test
    @Order(11)
    @DisplayName("[Security] Swagger UI는 인증 없이 접근 가능")
    void testSwaggerAccessible() throws Exception {
        // Swagger UI 리다이렉트 확인 (실제 정적 리소스는 없으므로 404가 아닌 것만 확인)
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // 401이 아닌 것만 확인 (200 또는 302 또는 404)
                    assertThat(status).isNotEqualTo(401);
                });

        System.out.println("✅ [Security] Swagger UI 인증 없이 접근 가능 확인");
    }
}
