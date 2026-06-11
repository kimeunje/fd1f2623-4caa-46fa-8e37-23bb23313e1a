package com.secuhub;

import com.secuhub.config.audit.Auditable;
import com.secuhub.domain.audit.AuditAction;
import com.secuhub.domain.audit.AuditLogRepository;
import com.secuhub.domain.audit.AuditResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AUDIT-1 — AuditAspect 가 @Auditable 메서드의 성공/실패를 기록하는지 검증.
 *
 * <p>실제 도메인 서비스 의존을 피하려고 테스트 전용 @Auditable 빈({@link AuditableProbe})을
 * @TestConfiguration 으로 띄운다 — Spring AOP 프록시 적용 대상이 된다.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AUDIT-1 — AuditAspect @Auditable 기록")
class AuditAspectTest {

    @Autowired private AuditableProbe probe;
    @Autowired private AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
    }

    @Test
    @Order(1)
    @DisplayName("[Success] 정상 반환 시 SUCCESS 1건 기록")
    void testRecordsSuccess() {
        String r = probe.doWork(false);

        assertThat(r).isEqualTo("ok");
        var all = auditLogRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getAction()).isEqualTo(AuditAction.SCRIPT_CREATE);
        assertThat(all.get(0).getResult()).isEqualTo(AuditResult.SUCCESS);
        assertThat(all.get(0).getTargetType()).isEqualTo("Script");

        System.out.println("✅ [Success] @Auditable 성공 → SUCCESS 기록 확인");
    }

    @Test
    @Order(2)
    @DisplayName("[Failure] 예외 발생 시 FAILURE 기록 후 예외 재전파")
    void testRecordsFailureAndRethrows() {
        assertThatThrownBy(() -> probe.doWork(true))
                .isInstanceOf(IllegalStateException.class);

        var all = auditLogRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getResult()).isEqualTo(AuditResult.FAILURE);
        assertThat(all.get(0).getDetail()).contains("IllegalStateException");

        System.out.println("✅ [Failure] @Auditable 예외 → FAILURE 기록 + 재전파 확인");
    }

    // ==================================================================
    // 테스트 전용 @Auditable 빈
    // ==================================================================

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        AuditableProbe auditableProbe() {
            return new AuditableProbe();
        }
    }

    /** AOP 가 적용되도록 Spring 빈 + public 메서드로 노출. */
    static class AuditableProbe {
        @Auditable(action = AuditAction.SCRIPT_CREATE, targetType = "Script")
        public String doWork(boolean fail) {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return "ok";
        }
    }
}