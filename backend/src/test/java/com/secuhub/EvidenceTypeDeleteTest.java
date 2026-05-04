package com.secuhub;

import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.NodeType;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v15 Phase 5-15g (v15.11) — DELETE /api/v1/evidence-types/{id} 회귀 테스트.
 *
 * <p>본 phase 의 핵심 산출. v15.6 (5-15b R3) 에서 FE evidenceTypesApi.delete
 * 신설 후 BE controller 매핑 누락 잔존, v15.8 ApiSurfaceTest known gap 등재
 * 후 v15.11 에서 EvidenceTypeController 신설로 회수.</p>
 *
 * <h3>케이스</h3>
 * <ol>
 *   <li>[200] 정상 삭제 — admin token + 존재하는 id → 200 + 실 DB 삭제</li>
 *   <li>[404] 미존재 id — admin token + 99999 → 404 (ResourceNotFoundException
 *       의 GlobalExceptionHandler 매핑)</li>
 *   <li>[401] 익명 호출 — Authorization 헤더 없음 → 401 (Spring Security 기본)</li>
 * </ol>
 *
 * <h3>패턴 정합 (TreeReadTest / Phase514fIntegrationTest / ImpactSummaryTest)</h3>
 * <ul>
 *   <li>5-14d / 5-14f / 5-15a 의 mockMvc 통합 테스트 패턴 재사용
 *       — {@code @Transactional} 미사용 (controller TX commit 보존)</li>
 *   <li>JdbcTemplate FK off + DELETE 역순 + 본 클래스 user email pattern
 *       ({@code admin-5-15g@test.com}) 매칭 cleanup — 다른 테스트 leftover 보존</li>
 *   <li>Token 발급 = {@code jwtTokenProvider.createToken(id, email, role.name())}
 *       (TreeReadTest:setUp / Phase514fIntegrationTest:setUp 패턴 정합)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-15g — EvidenceType 단건 삭제 (DELETE /api/v1/evidence-types/{id})")
class EvidenceTypeDeleteTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String adminToken;

    @BeforeEach
    void setUp() {
        // FK 역순 cleanup — 본 클래스 격리 (5-14d / 5-15a 패턴 정합)
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.execute("DELETE FROM evidence_files");
            jdbcTemplate.execute("DELETE FROM evidence_types");
            jdbcTemplate.execute("DELETE FROM control_nodes");
            jdbcTemplate.execute("DELETE FROM frameworks");
            jdbcTemplate.execute("DELETE FROM users WHERE email LIKE '%-5-15g@test.com'");
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }

        User admin = userRepository.save(User.builder()
                .email("admin-5-15g@test.com")
                .name("Admin 5-15g")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin)
                .permissionEvidence(true)
                
                .build());
        adminToken = jwtTokenProvider.createToken(
                admin.getId(), admin.getEmail(), admin.getRole().name());
    }

    // ====================================================================
    // 1. [200] 정상 삭제
    // ====================================================================

    @Test
    @Order(1)
    @DisplayName("[200] admin token + 존재하는 id → 200 + DB 실 삭제")
    void testDeleteOk() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW-5-15g").build());
        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("1").name("leaf-5-15g").displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf).name("증빙 유형 5-15g").build());

        Long etId = et.getId();
        assertThat(evidenceTypeRepository.existsById(etId)).isTrue();

        mockMvc.perform(delete("/api/v1/evidence-types/{id}", etId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(evidenceTypeRepository.existsById(etId)).isFalse();

        System.out.println("✅ [200] DELETE /evidence-types/{id} → 200 + DB 실 삭제 (id=" + etId + ")");
    }

    // ====================================================================
    // 2. [404] 미존재 id
    // ====================================================================

    @Test
    @Order(2)
    @DisplayName("[404] admin token + 미존재 id → 404 (ResourceNotFoundException)")
    void testDeleteNotFound() throws Exception {
        long missingId = 999_999L;
        assertThat(evidenceTypeRepository.existsById(missingId)).isFalse();

        mockMvc.perform(delete("/api/v1/evidence-types/{id}", missingId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        System.out.println("✅ [404] 미존재 id → 404 (id=" + missingId + ")");
    }

    // ====================================================================
    // 3. [401] 익명 호출
    // ====================================================================

    @Test
    @Order(3)
    @DisplayName("[401] Authorization 헤더 부재 → 401")
    void testDeleteAnonymous() throws Exception {
        mockMvc.perform(delete("/api/v1/evidence-types/{id}", 1L))
                .andExpect(status().isUnauthorized());

        System.out.println("✅ [401] 익명 호출 → 401");
    }
}