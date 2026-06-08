package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.access.entity.IpAccessRule;
import com.secuhub.domain.access.repository.IpAccessRuleRepository;
import com.secuhub.domain.access.util.IpCidr;
import com.secuhub.domain.user.dto.LoginRequest;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v19.0 — 계정별 IP 접근 제어 (BE-1).
 *
 * <p>단위(IpCidr 매칭/검증) + 통합(로그인 거부/허용, 인증된 요청 거부/허용, 비활성 규칙 무시).
 * 규칙 없는 계정은 어떤 IP 에서도 허용 — 기존 테스트(151+) 회귀 0 을 보장하는 기본 동작.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("v19.0 - 계정별 IP 접근 제어")
class IpAccessTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private IpAccessRuleRepository ipAccessRuleRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private static final String ALLOWED_IP = "203.0.113.50";   // 203.0.113.0/24 안
    private static final String DENIED_IP = "198.51.100.7";    // 규칙 밖
    private static final String OFFICE_CIDR = "203.0.113.0/24";
    private static final String PASSWORD = "password123";

    private RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private User createUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .name("테스터")
                .hashedPassword(passwordEncoder.encode(PASSWORD))
                .role(UserRole.admin)
                .build());
    }

    private void addRule(Long userId, String cidr, boolean enabled) {
        ipAccessRuleRepository.save(IpAccessRule.builder()
                .userId(userId)
                .cidr(cidr)
                .description("사무실")
                .enabled(enabled)
                .build());
    }

    // ========================================
    // 1. IpCidr 단위 — 매칭
    // ========================================

    @Test
    @Order(1)
    @DisplayName("[IpCidr] IPv4 CIDR / 단일 IP / IPv6 매칭")
    void testCidrMatching() {
        assertThat(IpCidr.matches("203.0.113.0/24", "203.0.113.50")).isTrue();
        assertThat(IpCidr.matches("203.0.113.0/24", "198.51.100.7")).isFalse();
        assertThat(IpCidr.matches("192.168.1.10", "192.168.1.10")).isTrue();   // 단일 IP = /32
        assertThat(IpCidr.matches("192.168.1.10", "192.168.1.11")).isFalse();
        assertThat(IpCidr.matches("::1/128", "::1")).isTrue();
        assertThat(IpCidr.matches("10.0.0.0/8", "::1")).isFalse();             // 패밀리 불일치
        assertThat(IpCidr.matches("bogus", "203.0.113.50")).isFalse();        // 오타 흡수
        System.out.println("✅ [IpCidr] 매칭 정상");
    }

    // ========================================
    // 2. IpCidr 단위 — 검증
    // ========================================

    @Test
    @Order(2)
    @DisplayName("[IpCidr] CIDR/IP 표기 검증")
    void testCidrValidation() {
        assertThat(IpCidr.isValid("10.0.0.0/8")).isTrue();
        assertThat(IpCidr.isValid("192.168.1.10")).isTrue();
        assertThat(IpCidr.isValid("2001:db8::/32")).isTrue();
        assertThat(IpCidr.isValid("10.0.0.0/33")).isFalse();   // IPv4 prefix 초과
        assertThat(IpCidr.isValid("::1/129")).isFalse();       // IPv6 prefix 초과
        assertThat(IpCidr.isValid("not-an-ip")).isFalse();
        assertThat(IpCidr.isValid("")).isFalse();
        assertThat(IpCidr.isValid(null)).isFalse();
        System.out.println("✅ [IpCidr] 검증 정상");
    }

    // ========================================
    // 3. 로그인 enforcement
    // ========================================

    @Test
    @Order(3)
    @DisplayName("[Login] 규칙 없는 계정은 어떤 IP 에서도 로그인 → 200 (기본 동작)")
    @Transactional
    void testLoginAllowedWhenNoRules() throws Exception {
        createUser("norule@test.com");
        LoginRequest req = new LoginRequest("norule@test.com", PASSWORD);

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp(DENIED_IP))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        System.out.println("✅ [Login] 규칙 없음 → 전체 허용");
    }

    @Test
    @Order(4)
    @DisplayName("[Login] 허용 IP 에서 로그인 → 200")
    @Transactional
    void testLoginAllowedFromAllowedIp() throws Exception {
        User user = createUser("allowed@test.com");
        addRule(user.getId(), OFFICE_CIDR, true);
        LoginRequest req = new LoginRequest("allowed@test.com", PASSWORD);

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp(ALLOWED_IP))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        System.out.println("✅ [Login] 허용 IP → 200");
    }

    @Test
    @Order(5)
    @DisplayName("[Login] 비허용 IP 에서 로그인 → 403 (자격증명 정상이어도)")
    @Transactional
    void testLoginDeniedFromDisallowedIp() throws Exception {
        User user = createUser("denied@test.com");
        addRule(user.getId(), OFFICE_CIDR, true);
        LoginRequest req = new LoginRequest("denied@test.com", PASSWORD);

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp(DENIED_IP))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("허용되지 않은 IP 에서의 접근입니다."));

        System.out.println("✅ [Login] 비허용 IP → 403");
    }

    @Test
    @Order(6)
    @DisplayName("[Login] 비활성(enabled=false) 규칙만 있으면 제한 없음 → 200")
    @Transactional
    void testDisabledRuleIgnored() throws Exception {
        User user = createUser("disabled@test.com");
        addRule(user.getId(), OFFICE_CIDR, false);   // 비활성 규칙
        LoginRequest req = new LoginRequest("disabled@test.com", PASSWORD);

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp(DENIED_IP))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        System.out.println("✅ [Login] 비활성 규칙 무시 → 200");
    }

    // ========================================
    // 4. 인증된 요청 enforcement (필터)
    // ========================================

    @Test
    @Order(7)
    @DisplayName("[Filter] 허용 IP + 유효 토큰 → 200")
    @Transactional
    void testRequestAllowedFromAllowedIp() throws Exception {
        User user = createUser("req-allowed@test.com");
        addRule(user.getId(), OFFICE_CIDR, true);
        String token = jwtTokenProvider.createToken(
                user.getId(), user.getEmail(), user.getRole().name());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token)
                        .with(fromIp(ALLOWED_IP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        System.out.println("✅ [Filter] 허용 IP + 토큰 → 200");
    }

    @Test
    @Order(8)
    @DisplayName("[Filter] 비허용 IP + 유효 토큰 → 403 (토큰 재사용 차단)")
    @Transactional
    void testRequestDeniedFromDisallowedIp() throws Exception {
        User user = createUser("req-denied@test.com");
        addRule(user.getId(), OFFICE_CIDR, true);
        String token = jwtTokenProvider.createToken(
                user.getId(), user.getEmail(), user.getRole().name());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token)
                        .with(fromIp(DENIED_IP)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("허용되지 않은 IP 에서의 접근입니다."));

        System.out.println("✅ [Filter] 비허용 IP + 토큰 → 403");
    }
}