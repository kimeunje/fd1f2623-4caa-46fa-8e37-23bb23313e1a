package com.secuhub;

import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.audit.AuditAction;
import com.secuhub.domain.audit.AuditLog;
import com.secuhub.domain.audit.AuditLogRepository;
import com.secuhub.domain.audit.AuditResult;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AUDIT-2 — 감사 로그 조회 API (admin 전용, 필터 + 페이지네이션).
 *
 * <p>v19.14 — actorUserId 필터 케이스를 통합 검색어(keyword: 이메일/IP/대상명) 케이스로 교체.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AUDIT-2 — 감사 로그 조회 API")
class AuditLogQueryTest {

    private static final String URL = "/api/v1/admin/audit-logs";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String adminToken;
    private String developerToken;
    private User admin;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();

        admin = userRepository.save(User.builder()
                .email("audit2-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin).permissionEvidence(true)
                .build());
        User developer = userRepository.save(User.builder()
                .email("audit2-dev@test.com").name("담당자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.developer).permissionEvidence(false)
                .build());

        adminToken = jwtTokenProvider.createToken(admin.getId(), admin.getEmail(), "admin");
        developerToken = jwtTokenProvider.createToken(developer.getId(), developer.getEmail(), "developer");

        // 4건 시드 (created_at 을 서로 다른 과거로 보정 → 정렬/기간 필터 결정적)
        // r4 에는 targetName("패치 관리 내역.txt") 부여 → keyword(파일명) 검색 검증용.
        Long r1 = saveAudit(AuditAction.LOGIN_SUCCESS, AuditResult.SUCCESS, admin.getId(), admin.getEmail(), null);
        Long r2 = saveAudit(AuditAction.LOGIN_FAILURE, AuditResult.FAILURE, null, "attempt@test.com", null);
        Long r3 = saveAudit(AuditAction.ACL_BLOCKED, AuditResult.BLOCKED, admin.getId(), admin.getEmail(), null);
        Long r4 = saveAudit(AuditAction.SCRIPT_DELETE, AuditResult.SUCCESS, admin.getId(), admin.getEmail(),
                "패치 관리 내역.txt");
        backdate(r1, LocalDateTime.now().minusDays(10));
        backdate(r2, LocalDateTime.now().minusDays(5));
        backdate(r3, LocalDateTime.now().minusDays(1));
        backdate(r4, LocalDateTime.now().minusHours(1));
    }

    @Test
    @Order(1)
    @DisplayName("[Auth] 비로그인 → 401")
    void testRequiresAuth() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
        System.out.println("✅ [Auth] 비로그인 401 확인");
    }

    @Test
    @Order(2)
    @DisplayName("[Auth] 비admin(developer) → 403")
    void testForbidsNonAdmin() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isForbidden());
        System.out.println("✅ [Auth] 비admin 403 확인");
    }

    @Test
    @Order(3)
    @DisplayName("[List] admin → 전체 4건, created_at DESC 정렬")
    void testListAllSortedDesc() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content[0].action").value("SCRIPT_DELETE")) // 최신 r4
                .andExpect(jsonPath("$.data.content[3].action").value("LOGIN_SUCCESS")); // 최古 r1
        System.out.println("✅ [List] 전체 4건 + DESC 정렬 확인");
    }

    @Test
    @Order(4)
    @DisplayName("[Filter:action] action=LOGIN_FAILURE → 해당만")
    void testFilterByAction() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + adminToken)
                        .param("action", "LOGIN_FAILURE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].action").value("LOGIN_FAILURE"))
                .andExpect(jsonPath("$.data.content[0].actorEmail").value("attempt@test.com"));
        System.out.println("✅ [Filter:action] action 필터 확인");
    }

    @Test
    @Order(5)
    @DisplayName("[Filter:result] result=BLOCKED → 해당만")
    void testFilterByResult() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + adminToken)
                        .param("result", "BLOCKED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].result").value("BLOCKED"));
        System.out.println("✅ [Filter:result] result 필터 확인");
    }

    @Test
    @Order(6)
    @DisplayName("[Filter:keyword] 검색어 — 파일명/이메일/IP 부분일치")
    void testFilterByKeyword() throws Exception {
        // (1) 대상명(파일명) 부분일치 → r4
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + adminToken)
                        .param("keyword", "패치"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].action").value("SCRIPT_DELETE"))
                .andExpect(jsonPath("$.data.content[0].targetName").value("패치 관리 내역.txt"));

        // (2) 이메일 부분일치 → r2
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + adminToken)
                        .param("keyword", "attempt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].actorEmail").value("attempt@test.com"));

        // (3) IP 부분일치 → 전체 4건(모두 10.0.0.1)
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + adminToken)
                        .param("keyword", "10.0.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4));

        System.out.println("✅ [Filter:keyword] 파일명/이메일/IP 검색 확인");
    }

    @Test
    @Order(7)
    @DisplayName("[Filter:period] from=최근 2일 → 기간 내만")
    void testFilterByPeriod() throws Exception {
        String from = LocalDateTime.now().minusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + adminToken)
                        .param("from", from))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2)); // r3(-1d), r4(-1h)
        System.out.println("✅ [Filter:period] 기간 필터 확인");
    }

    @Test
    @Order(8)
    @DisplayName("[Paging] size=2 → 1페이지 2건 + hasNext")
    void testPagination() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + adminToken)
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        System.out.println("✅ [Paging] 페이지네이션 확인");
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private Long saveAudit(AuditAction action, AuditResult result,
                           Long actorUserId, String actorEmail, String targetName) {
        AuditLog row = auditLogRepository.save(AuditLog.builder()
                .action(action).result(result)
                .actorUserId(actorUserId).actorEmail(actorEmail)
                .targetName(targetName)
                .clientIp("10.0.0.1")
                .build());
        return row.getId();
    }

    private void backdate(Long id, LocalDateTime when) {
        jdbcTemplate.update("UPDATE audit_logs SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(when), id);
    }
}