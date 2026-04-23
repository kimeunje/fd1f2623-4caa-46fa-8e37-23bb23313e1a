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
 * Phase 5-9 — Control 목록 API 의 pendingReviewCount 집계 검증
 *
 * <h3>검증 항목</h3>
 * <ul>
 *   <li>GET /api/v1/frameworks/{fwId}/controls 응답에 pendingReviewCount 포함</li>
 *   <li>Control 간 집계 분리 — A control 의 pending 이 B control 에 누적되지 않음</li>
 *   <li>pending 아닌 상태(approved / rejected / auto_approved)는 집계 제외</li>
 *   <li>빈 Control / 파일 0개인 Control → pendingReviewCount=0</li>
 * </ul>
 *
 * <p>FrameworkListTest 와 동일한 스타일의 데이터 빌드 + 어설션.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-9 — Control 목록 API pending 집계")
class ControlListPendingTest {

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
    private Framework framework;

    @BeforeEach
    void setUp() {
        evidenceFileRepository.deleteAll();
        collectionJobRepository.deleteAll();
        evidenceTypeRepository.deleteAll();
        controlRepository.deleteAll();
        frameworkRepository.deleteAll();
        userRepository.deleteAll();

        User admin = userRepository.save(User.builder()
                .email("ctrlpending-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin).permissionEvidence(true).permissionVuln(true)
                .build());

        adminToken = jwtTokenProvider.createToken(admin.getId(), admin.getEmail(), "admin");
        framework = frameworkRepository.save(Framework.builder().name("FW-Pending").build());
    }

    // ==================================================================
    // 1. 빈 Control → pendingReviewCount=0
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("[Empty] 파일 없는 Control → pendingReviewCount=0")
    void testEmptyControlHasZeroPending() throws Exception {
        controlRepository.save(Control.builder()
                .framework(framework).code("X-1").name("빈 통제").build());

        mockMvc.perform(get("/api/v1/frameworks/" + framework.getId() + "/controls")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("X-1"))
                .andExpect(jsonPath("$.data[0].pendingReviewCount").value(0));

        System.out.println("✅ [Empty] 빈 Control pendingReviewCount=0");
    }

    // ==================================================================
    // 2. Control 간 집계 분리
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("[Isolation] Control A 에 pending 2건, Control B 에 0건 → 서로 격리")
    void testPendingIsolatedPerControl() throws Exception {
        Control a = controlRepository.save(Control.builder()
                .framework(framework).code("A-1").name("통제 A").build());
        Control b = controlRepository.save(Control.builder()
                .framework(framework).code("B-1").name("통제 B").build());

        EvidenceType etA = evidenceTypeRepository.save(EvidenceType.builder()
                .control(a).name("증빙 A").build());
        evidenceTypeRepository.save(EvidenceType.builder()
                .control(b).name("증빙 B").build());

        // A 에만 pending 2건
        savePending(etA, "a1.pdf", 1);
        savePending(etA, "a2.pdf", 2);

        mockMvc.perform(get("/api/v1/frameworks/" + framework.getId() + "/controls")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'A-1')].pendingReviewCount").value(2))
                .andExpect(jsonPath("$.data[?(@.code == 'B-1')].pendingReviewCount").value(0));

        System.out.println("✅ [Isolation] Control 간 pending 집계 격리 확인");
    }

    // ==================================================================
    // 3. pending 외 상태는 모두 제외
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("[Filter] approved/rejected/auto_approved 는 pendingReviewCount 에서 제외")
    void testOnlyPendingCounted() throws Exception {
        Control c = controlRepository.save(Control.builder()
                .framework(framework).code("C-1").name("혼합 통제").build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(c).name("증빙").build());

        // 4가지 상태 각 1건, pending 만 집계되어야 함
        saveWithStatus(et, "p.pdf", 1, ReviewStatus.pending);
        saveWithStatus(et, "a.pdf", 2, ReviewStatus.approved);
        saveWithStatus(et, "r.pdf", 3, ReviewStatus.rejected);
        saveWithStatus(et, "auto.pdf", 4, ReviewStatus.auto_approved);

        mockMvc.perform(get("/api/v1/frameworks/" + framework.getId() + "/controls")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pendingReviewCount").value(1));

        System.out.println("✅ [Filter] pending 만 집계 (다른 상태 제외)");
    }

    // ==================================================================
    // 4. 여러 evidence_type 의 pending 이 같은 Control 로 합산
    // ==================================================================

    @Test
    @Order(4)
    @DisplayName("[Aggregation] 같은 Control 의 서로 다른 증빙 유형 pending 은 합산")
    void testPendingAggregatedAcrossEvidenceTypes() throws Exception {
        Control c = controlRepository.save(Control.builder()
                .framework(framework).code("D-1").name("다중증빙 통제").build());
        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(c).name("증빙 1").build());
        EvidenceType et2 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(c).name("증빙 2").build());
        EvidenceType et3 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(c).name("증빙 3").build());

        // 3 evidence_type 에 pending 각 1건씩 → 총 3
        savePending(et1, "1.pdf", 1);
        savePending(et2, "2.pdf", 1);
        savePending(et3, "3.pdf", 1);

        mockMvc.perform(get("/api/v1/frameworks/" + framework.getId() + "/controls")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pendingReviewCount").value(3));

        System.out.println("✅ [Aggregation] 증빙 유형별 pending 합산 확인");
    }

    // ==================================================================
    // 5. 다른 Framework 의 pending 은 절대 섞이지 않음
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("[Scope] 다른 Framework 의 pending 은 무관")
    void testPendingScopedByFramework() throws Exception {
        // 현재 framework 에 Control E, pending 1건
        Control e = controlRepository.save(Control.builder()
                .framework(framework).code("E-1").name("통제 E").build());
        EvidenceType etE = evidenceTypeRepository.save(EvidenceType.builder()
                .control(e).name("증빙 E").build());
        savePending(etE, "e.pdf", 1);

        // 다른 Framework 에 Control F, pending 5건 (영향 없어야 함)
        Framework otherFw = frameworkRepository.save(Framework.builder().name("Other FW").build());
        Control f = controlRepository.save(Control.builder()
                .framework(otherFw).code("F-1").name("통제 F").build());
        EvidenceType etF = evidenceTypeRepository.save(EvidenceType.builder()
                .control(f).name("증빙 F").build());
        for (int i = 1; i <= 5; i++) {
            savePending(etF, "f" + i + ".pdf", i);
        }

        mockMvc.perform(get("/api/v1/frameworks/" + framework.getId() + "/controls")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("E-1"))
                .andExpect(jsonPath("$.data[0].pendingReviewCount").value(1));

        System.out.println("✅ [Scope] Framework 단위 pending 격리 확인");
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private EvidenceFile savePending(EvidenceType et, String name, int version) {
        return saveWithStatus(et, name, version, ReviewStatus.pending);
    }

    private EvidenceFile saveWithStatus(EvidenceType et, String name, int version, ReviewStatus status) {
        return evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName(name)
                .filePath("/tmp/" + name)
                .fileSize(1L)
                .version(version)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .reviewStatus(status)
                .build());
    }
}