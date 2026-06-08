package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.access.entity.IpAccessRule;
import com.secuhub.domain.access.repository.IpAccessRuleRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v19.1 — 계정별 IP 접근 규칙 관리 API (BE-2).
 *
 * <p>CRUD + 자기 잠금 방지(409) + 소유 검증(404) + 권한(403) + cidr 검증(400).
 * mockMvc 기본 remoteAddr 은 127.0.0.1 — 자기 잠금 케이스의 "현재 IP" 로 사용된다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("v19.1 - IP 접근 규칙 관리 API")
class IpAccessAdminTest {

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

    private User createUser(String email, UserRole role) {
        return userRepository.save(User.builder()
                .email(email)
                .name("u-" + email)
                .hashedPassword(passwordEncoder.encode("password123"))
                .role(role)
                .build());
    }

    private String tokenFor(User u) {
        return jwtTokenProvider.createToken(u.getId(), u.getEmail(), u.getRole().name());
    }

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    // ========================================
    // 권한
    // ========================================

    @Test
    @Order(1)
    @DisplayName("[권한] 비-admin → 403")
    @Transactional
    void testNonAdminForbidden() throws Exception {
        User dev = createUser("dev1@test.com", UserRole.developer);
        mockMvc.perform(get("/api/v1/users/{userId}/ip-rules", dev.getId())
                        .header("Authorization", "Bearer " + tokenFor(dev)))
                .andExpect(status().isForbidden());
        System.out.println("✅ [권한] 비-admin → 403");
    }

    // ========================================
    // 생성 / 조회
    // ========================================

    @Test
    @Order(2)
    @DisplayName("[Create] 유효 규칙 생성 → 200, 목록 1건")
    @Transactional
    void testCreateAndList() throws Exception {
        User admin = createUser("admin2@test.com", UserRole.admin);
        User target = createUser("target2@test.com", UserRole.developer);
        String adminToken = tokenFor(admin);

        mockMvc.perform(post("/api/v1/users/{userId}/ip-rules", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cidr", "203.0.113.0/24", "description", "사무실"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cidr").value("203.0.113.0/24"))
                .andExpect(jsonPath("$.data.enabled").value(true));

        mockMvc.perform(get("/api/v1/users/{userId}/ip-rules", target.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        System.out.println("✅ [Create] 생성 + 목록");
    }

    @Test
    @Order(3)
    @DisplayName("[Create] 잘못된 cidr → 400")
    @Transactional
    void testCreateInvalidCidr() throws Exception {
        User admin = createUser("admin3@test.com", UserRole.admin);
        User target = createUser("target3@test.com", UserRole.developer);

        mockMvc.perform(post("/api/v1/users/{userId}/ip-rules", target.getId())
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cidr", "10.0.0.0/33"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        System.out.println("✅ [Create] 잘못된 cidr → 400");
    }

    @Test
    @Order(4)
    @DisplayName("[Create] 빈 cidr → 400 (@NotBlank)")
    @Transactional
    void testCreateBlankCidr() throws Exception {
        User admin = createUser("admin4@test.com", UserRole.admin);
        User target = createUser("target4@test.com", UserRole.developer);

        mockMvc.perform(post("/api/v1/users/{userId}/ip-rules", target.getId())
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cidr", "   "))))
                .andExpect(status().isBadRequest());

        System.out.println("✅ [Create] 빈 cidr → 400");
    }

    // ========================================
    // 자기 잠금 방지
    // ========================================

    @Test
    @Order(5)
    @DisplayName("[Self-lock] 본인 첫 규칙이 현재 IP(127.0.0.1) 배제 → 409")
    @Transactional
    void testSelfLockoutBlocked() throws Exception {
        User admin = createUser("admin5@test.com", UserRole.admin);

        mockMvc.perform(post("/api/v1/users/{userId}/ip-rules", admin.getId())
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cidr", "203.0.113.0/24"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        System.out.println("✅ [Self-lock] 본인 IP 배제 규칙 → 409");
    }

    @Test
    @Order(6)
    @DisplayName("[Self-lock] 본인 규칙이 현재 IP(127.0.0.1) 포함 → 200")
    @Transactional
    void testSelfRuleIncludingOwnIpAllowed() throws Exception {
        User admin = createUser("admin6@test.com", UserRole.admin);

        mockMvc.perform(post("/api/v1/users/{userId}/ip-rules", admin.getId())
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cidr", "127.0.0.0/8"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cidr").value("127.0.0.0/8"));

        System.out.println("✅ [Self-lock] 본인 IP 포함 규칙 → 200");
    }

    @Test
    @Order(7)
    @DisplayName("[Self-lock] 타인 계정은 현재 IP 배제해도 → 200")
    @Transactional
    void testOtherUserNotGuarded() throws Exception {
        User admin = createUser("admin7@test.com", UserRole.admin);
        User target = createUser("target7@test.com", UserRole.developer);

        mockMvc.perform(post("/api/v1/users/{userId}/ip-rules", target.getId())
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cidr", "203.0.113.0/24"))))
                .andExpect(status().isOk());

        System.out.println("✅ [Self-lock] 타인 계정 제한 → 200");
    }

    // ========================================
    // 수정 / 삭제 / 소유 검증
    // ========================================

    @Test
    @Order(8)
    @DisplayName("[Update] 메모/활성 수정 → 200")
    @Transactional
    void testUpdate() throws Exception {
        User admin = createUser("admin8@test.com", UserRole.admin);
        User target = createUser("target8@test.com", UserRole.developer);
        IpAccessRule rule = ipAccessRuleRepository.save(IpAccessRule.builder()
                .userId(target.getId()).cidr("203.0.113.0/24").enabled(true).build());

        mockMvc.perform(patch("/api/v1/users/{userId}/ip-rules/{ruleId}", target.getId(), rule.getId())
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "수정됨", "enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("수정됨"))
                .andExpect(jsonPath("$.data.enabled").value(false));

        System.out.println("✅ [Update] 200");
    }

    @Test
    @Order(9)
    @DisplayName("[Update] 다른 사용자 소유 ruleId 접근 → 404")
    @Transactional
    void testUpdateOwnershipMismatch() throws Exception {
        User admin = createUser("admin9@test.com", UserRole.admin);
        User userA = createUser("usera9@test.com", UserRole.developer);
        User userB = createUser("userb9@test.com", UserRole.developer);
        IpAccessRule ruleOfA = ipAccessRuleRepository.save(IpAccessRule.builder()
                .userId(userA.getId()).cidr("203.0.113.0/24").enabled(true).build());

        // userB 경로로 userA 의 규칙 접근
        mockMvc.perform(patch("/api/v1/users/{userId}/ip-rules/{ruleId}", userB.getId(), ruleOfA.getId())
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "x"))))
                .andExpect(status().isNotFound());

        System.out.println("✅ [Update] 소유 불일치 → 404");
    }

    @Test
    @Order(10)
    @DisplayName("[Delete] 규칙 삭제 → 200, 목록 0건")
    @Transactional
    void testDelete() throws Exception {
        User admin = createUser("admin10@test.com", UserRole.admin);
        User target = createUser("target10@test.com", UserRole.developer);
        IpAccessRule rule = ipAccessRuleRepository.save(IpAccessRule.builder()
                .userId(target.getId()).cidr("203.0.113.0/24").enabled(true).build());
        String adminToken = tokenFor(admin);

        mockMvc.perform(delete("/api/v1/users/{userId}/ip-rules/{ruleId}", target.getId(), rule.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/{userId}/ip-rules", target.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        System.out.println("✅ [Delete] 200 + 목록 0건");
    }
}