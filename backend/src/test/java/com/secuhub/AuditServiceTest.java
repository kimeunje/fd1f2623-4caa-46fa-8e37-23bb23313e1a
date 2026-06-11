package com.secuhub;

import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.domain.audit.AuditAction;
import com.secuhub.domain.audit.AuditLog;
import com.secuhub.domain.audit.AuditLogRepository;
import com.secuhub.domain.audit.AuditResult;
import com.secuhub.domain.audit.AuditService;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUDIT-1 — AuditService 기록 진입점 검증.
 *
 * <p>엔드포인트가 없는 phase 라 MockMvc 는 쓰지 않고 서비스 직접 호출로 검증한다.
 * actor 해소 검증을 위해 실제 admin 사용자를 시드한다(레퍼런스 ControlListPendingTest 시드 패턴).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AUDIT-1 — AuditService 기록/actor 해소")
class AuditServiceTest {

    @Autowired private AuditService auditService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User admin;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        SecurityContextHolder.clearContext();

        admin = userRepository.save(User.builder()
                .email("audit-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin).permissionEvidence(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Order(1)
    @DisplayName("[Explicit] 명시 오버로드는 전달 필드를 그대로 기록 (미인증 actor=null)")
    void testExplicitOverloadPersistsFields() {
        auditService.record(AuditAction.LOGIN_FAILURE, AuditResult.FAILURE,
                "User", null, "{\"reason\":\"bad-credentials\"}",
                null, "attempt@test.com", "10.0.0.9");

        List<AuditLog> all = auditLogRepository.findAll();
        assertThat(all).hasSize(1);
        AuditLog row = all.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.LOGIN_FAILURE);
        assertThat(row.getResult()).isEqualTo(AuditResult.FAILURE);
        assertThat(row.getActorUserId()).isNull();
        assertThat(row.getActorEmail()).isEqualTo("attempt@test.com");
        assertThat(row.getClientIp()).isEqualTo("10.0.0.9");
        assertThat(row.getCreatedAt()).isNotNull();

        System.out.println("✅ [Explicit] 명시 오버로드 필드 기록 확인");
    }

    @Test
    @Order(2)
    @DisplayName("[Actor] SecurityContext 의 UserPrincipal 에서 actor 자동 해소")
    void testResolvesActorFromSecurityContext() {
        UserPrincipal principal = new UserPrincipal(admin.getId(), admin.getEmail(), "admin");
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        auditService.record(AuditAction.SCRIPT_DELETE, AuditResult.SUCCESS, "Script", "uuid-abc", null);

        AuditLog row = auditLogRepository.findAll().get(0);
        assertThat(row.getActorUserId()).isEqualTo(admin.getId());
        assertThat(row.getActorEmail()).isEqualTo(admin.getEmail());
        assertThat(row.getTargetType()).isEqualTo("Script");
        assertThat(row.getTargetId()).isEqualTo("uuid-abc");

        System.out.println("✅ [Actor] UserPrincipal → actor 해소 확인");
    }

    @Test
    @Order(3)
    @DisplayName("[Anonymous] SecurityContext 비면 actor=null (시스템/익명)")
    void testAnonymousActorIsNull() {
        auditService.record(AuditAction.RATE_LIMIT_BLOCKED, AuditResult.BLOCKED, null, null, null);

        AuditLog row = auditLogRepository.findAll().get(0);
        assertThat(row.getActorUserId()).isNull();
        assertThat(row.getActorEmail()).isNull();
        assertThat(row.getResult()).isEqualTo(AuditResult.BLOCKED);

        System.out.println("✅ [Anonymous] 익명 actor=null 확인");
    }

    @Test
    @Order(4)
    @DisplayName("[LongText] detail 은 TEXT 한계를 넘는 LONGTEXT 를 손실 없이 저장")
    void testLongTextDetailRoundTrip() {
        String big = "x".repeat(70_000); // > 65535 (TEXT) 한계 초과
        auditService.record(AuditAction.SCRIPT_UPDATE, AuditResult.SUCCESS,
                "Script", "1", big, admin.getId(), admin.getEmail(), "127.0.0.1");

        AuditLog row = auditLogRepository.findAll().get(0);
        assertThat(row.getDetail()).hasSize(70_000);

        System.out.println("✅ [LongText] LONGTEXT 70000자 무손실 저장 확인");
    }

    @Test
    @Order(5)
    @DisplayName("[Json] toJson 은 맵을 JSON 문자열로 직렬화")
    void testToJsonSerialization() {
        String json = auditService.toJson(Map.of("k", "v"));
        assertThat(json).contains("\"k\"").contains("\"v\"");
        assertThat(auditService.toJson(null)).isNull();

        System.out.println("✅ [Json] toJson 직렬화 확인");
    }
}