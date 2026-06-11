package com.secuhub;

import com.secuhub.config.audit.AuditProperties;
import com.secuhub.domain.audit.AuditAction;
import com.secuhub.domain.audit.AuditLog;
import com.secuhub.domain.audit.AuditLogRepository;
import com.secuhub.domain.audit.AuditResult;
import com.secuhub.domain.audit.AuditRetentionGcService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUDIT-1 — 보존정책 GC. retention-days=30 활성 상태에서 만료 정리 +
 * 비활성(≤0) 시 아무것도 지우지 않음 검증.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.audit.retention-days=30")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AUDIT-1 — 보존정책 GC")
class AuditRetentionGcServiceTest {

    @Autowired private AuditRetentionGcService gcService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
    }

    @Test
    @Order(1)
    @DisplayName("[Purge] retention 초과 로그는 정리, 최근 로그는 보존")
    void testPurgesExpiredKeepsRecent() {
        Long oldId = saveRow();
        Long recentId = saveRow();
        backdate(oldId, LocalDateTime.now().minusDays(60)); // 보존 30일 초과

        gcService.purgeExpired();

        assertThat(auditLogRepository.findById(oldId)).isEmpty();
        assertThat(auditLogRepository.findById(recentId)).isPresent();

        System.out.println("✅ [Purge] 만료 정리 + 최근 보존 확인");
    }

    @Test
    @Order(2)
    @DisplayName("[Disabled] retention-days ≤ 0 이면 아무것도 지우지 않음")
    void testDisabledDeletesNothing() {
        Long oldId = saveRow();
        backdate(oldId, LocalDateTime.now().minusDays(60));

        // 기본값 -1(비활성) props 로 직접 구성 — TestPropertySource(30) 무시
        AuditRetentionGcService disabled =
                new AuditRetentionGcService(auditLogRepository, new AuditProperties());
        disabled.purgeExpired();

        assertThat(auditLogRepository.findById(oldId)).isPresent();

        System.out.println("✅ [Disabled] 비활성 시 미삭제 확인");
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private Long saveRow() {
        AuditLog row = auditLogRepository.save(AuditLog.builder()
                .action(AuditAction.LOGIN_SUCCESS)
                .result(AuditResult.SUCCESS)
                .actorEmail("u@test.com")
                .build());
        return row.getId();
    }

    /** @CreationTimestamp 가 now 로 채운 created_at 을 과거로 보정 (GC 검증용). */
    private void backdate(Long id, LocalDateTime when) {
        jdbcTemplate.update("UPDATE audit_logs SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(when), id);
    }
}