package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 5-14e — leaf 통제 코드 변경 사전 경고 (impact-summary) API 검증.
 *
 * <h3>검증 항목</h3>
 * <ul>
 *   <li>{@code GET /api/v1/controls/{id}/impact-summary} 응답 shape — { evidenceFileCount, jobCount, reviewCount }</li>
 *   <li>{@code evidenceFileCount} — 모든 review_status 의 file 카운트 (version 무관)</li>
 *   <li>{@code jobCount} — 통제 산하 EvidenceType 에 바인딩된 CollectionJob 수</li>
 *   <li>{@code reviewCount} — {@code reviewed_at IS NOT NULL} 카운트 (Q4 결정)</li>
 *   <li>다른 통제의 데이터는 누적되지 않음 (집계 분리)</li>
 *   <li>빈 통제 → 모두 0</li>
 *   <li>존재하지 않는 controlId → 404 아님, 모두 0 (Q2 결정 — 단순함이 핵심)</li>
 * </ul>
 *
 * <p>spec §3.3.1.5 정합. v14.5 신규 (Phase 5-14e).</p>
 *
 * <p>5-14e 시점 의미: 본 테스트는 dev/test 환경 (V6 미실행) 에서 {@code controls.id} 로 호출.
 * impact-summary service 의 JPQL 매칭 ({@code evidence_types.control.id == :controlId}) 과
 * 자연 매칭. prod V6 후 클라이언트가 {@code leaf control_node.id} 로 호출 시에도 자연 매칭
 * (V6 Step 3b 에서 {@code evidence_types.control_id += 1,000,000} 이주됨).</p>
 *
 * <p>FrameworkListTest / EvidenceApprovalTest 와 동일한 cleanup + 토큰 발급 패턴.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-14e — impact-summary API")
class ImpactSummaryTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlRepository controlRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;
    @Autowired private CollectionJobRepository collectionJobRepository;

    private String adminToken;
    private User reviewerUser;

    @BeforeEach
    void setUp() {
        evidenceFileRepository.deleteAll();
        collectionJobRepository.deleteAll();
        evidenceTypeRepository.deleteAll();
        controlRepository.deleteAll();
        frameworkRepository.deleteAll();
        userRepository.deleteAll();

        User admin = userRepository.save(User.builder()
                .email("impact-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin)
                .permissionEvidence(true).permissionVuln(true)
                .build());
        reviewerUser = admin;
        adminToken = jwtTokenProvider.createToken(admin.getId(), admin.getEmail(), "admin");
    }

    // ==================================================================
    // 1. 정상 카운트
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("[Counts] file 3 / job 2 / reviewedAt 1 → 정확히 집계")
    void testCountsAccuracy() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW-Impact").build());

        // 대상 통제
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("1.1.1").name("정책 수립").build());
        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl).name("정책 문서").build());
        EvidenceType et2 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl).name("회의록").build());

        // EvidenceFile 3개 — 그 중 1개만 reviewedAt 명시 설정 (관리자가 명시 검토)
        // Phase 5-4 EvidenceApprovalService 가 approve/reject 시 reviewedAt 채움.
        // 여기서는 직접 빌드.
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et1).fileName("a.pdf").filePath("/tmp/a.pdf")
                .fileSize(1L).version(1)
                .collectionMethod(CollectionMethod.manual).collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.auto_approved)  // 자동 승인 → reviewedAt null
                .build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et1).fileName("b.pdf").filePath("/tmp/b.pdf")
                .fileSize(1L).version(2)
                .collectionMethod(CollectionMethod.manual).collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.pending)  // 검토 대기 → reviewedAt null
                .build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et2).fileName("c.pdf").filePath("/tmp/c.pdf")
                .fileSize(1L).version(1)
                .collectionMethod(CollectionMethod.manual).collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.approved)
                .reviewedBy(reviewerUser)
                .reviewedAt(LocalDateTime.now())  // 명시 검토
                .build());

        // CollectionJob 2개 — et1 / et2 에 각 하나
        collectionJobRepository.save(CollectionJob.builder()
                .name("자동수집1").jobType(JobType.excel_extract)
                .scriptPath("/scripts/a.py").evidenceType(et1).build());
        collectionJobRepository.save(CollectionJob.builder()
                .name("자동수집2").jobType(JobType.web_scraping)
                .scriptPath("/scripts/b.py").evidenceType(et2).build());

        // 다른 통제 (집계 누적 검증용 — 이쪽 카운트는 응답에 포함되면 안 됨)
        Control other = controlRepository.save(Control.builder()
                .framework(fw).code("1.1.2").name("다른 통제").build());
        EvidenceType etOther = evidenceTypeRepository.save(EvidenceType.builder()
                .control(other).name("다른 증빙").build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(etOther).fileName("other.pdf").filePath("/tmp/other.pdf")
                .fileSize(1L).version(1)
                .collectionMethod(CollectionMethod.manual).collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.approved)
                .reviewedBy(reviewerUser)
                .reviewedAt(LocalDateTime.now())
                .build());
        collectionJobRepository.save(CollectionJob.builder()
                .name("자동수집-기타").jobType(JobType.excel_extract)
                .scriptPath("/scripts/x.py").evidenceType(etOther).build());

        // ── 검증
        mockMvc.perform(get("/api/v1/controls/{id}/impact-summary", ctrl.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.evidenceFileCount").value(3))
                .andExpect(jsonPath("$.data.jobCount").value(2))
                .andExpect(jsonPath("$.data.reviewCount").value(1));

        System.out.println("✅ [Counts] file 3 / job 2 / review 1 정확히 집계 (다른 통제 분리됨)");
    }

    // ==================================================================
    // 2. 빈 카운트 + 존재 안 하는 id
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("[Empty] 빈 통제 + 존재 안 하는 id → 모두 0")
    void testZeroCounts() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW-Empty").build());
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("2.1.1").name("빈 통제").build());

        // 데이터 0개 — 모두 0 자연
        mockMvc.perform(get("/api/v1/controls/{id}/impact-summary", ctrl.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.evidenceFileCount").value(0))
                .andExpect(jsonPath("$.data.jobCount").value(0))
                .andExpect(jsonPath("$.data.reviewCount").value(0));

        // 존재하지 않는 controlId 도 모두 0 (404 아님 — Q2 결정 단순함)
        mockMvc.perform(get("/api/v1/controls/{id}/impact-summary", 99999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.evidenceFileCount").value(0))
                .andExpect(jsonPath("$.data.jobCount").value(0))
                .andExpect(jsonPath("$.data.reviewCount").value(0));

        System.out.println("✅ [Empty] 빈 통제 + 미존재 id 모두 0 0 0 (Q2-A 정합)");
    }
}