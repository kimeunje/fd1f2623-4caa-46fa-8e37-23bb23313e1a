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
 * Phase 5-3 — Framework 목록 API 집계 필드 검증
 *
 * <h3>검증 항목</h3>
 * <ul>
 *   <li>GET /api/v1/frameworks 응답에 status, parentFrameworkId/Name 포함</li>
 *   <li>controlCount, evidenceTypeCount, jobCount, pendingReviewCount 정확성</li>
 *   <li>상속 관계 (parent_framework_id) 반영</li>
 *   <li>archived 상태 반영</li>
 *   <li>pending 아닌 파일(approved, rejected, auto_approved)은 pendingReviewCount 에서 제외</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-3 — Framework 목록 API 집계")
class FrameworkListTest {

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

    @BeforeEach
    void setUp() {
        evidenceFileRepository.deleteAll();
        collectionJobRepository.deleteAll();
        evidenceTypeRepository.deleteAll();
        controlRepository.deleteAll();
        frameworkRepository.deleteAll();
        userRepository.deleteAll();

        User admin = userRepository.save(User.builder()
                .email("fwlist-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin).permissionEvidence(true).permissionVuln(true)
                .build());

        adminToken = jwtTokenProvider.createToken(admin.getId(), admin.getEmail(), "admin");
    }

    // ==================================================================
    // 1. 빈 Framework → 모든 집계 0
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("[Empty] 빈 Framework → controlCount=0, pendingReviewCount=0")
    void testEmptyFramework() throws Exception {
        frameworkRepository.save(Framework.builder().name("Empty FW").build());

        mockMvc.perform(get("/api/v1/frameworks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Empty FW"))
                .andExpect(jsonPath("$.data[0].status").value("active"))
                .andExpect(jsonPath("$.data[0].controlCount").value(0))
                .andExpect(jsonPath("$.data[0].evidenceTypeCount").value(0))
                .andExpect(jsonPath("$.data[0].jobCount").value(0))
                .andExpect(jsonPath("$.data[0].pendingReviewCount").value(0))
                .andExpect(jsonPath("$.data[0].parentFrameworkId").doesNotExist());

        System.out.println("✅ [Empty] 빈 Framework 집계 모두 0");
    }

    // ==================================================================
    // 2. 집계 정확성
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("[Counts] 통제 2 / 증빙 3 / 작업 1 / pending 2 → 정확히 집계")
    void testCountsAccuracy() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("Counted FW").build());

        // 통제 2개
        Control c1 = controlRepository.save(Control.builder().framework(fw).code("A-1").name("통제 1").build());
        Control c2 = controlRepository.save(Control.builder().framework(fw).code("A-2").name("통제 2").build());

        // 증빙 유형 3개 (c1 에 2, c2 에 1)
        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder().control(c1).name("증빙 A").build());
        EvidenceType et2 = evidenceTypeRepository.save(EvidenceType.builder().control(c1).name("증빙 B").build());
        EvidenceType et3 = evidenceTypeRepository.save(EvidenceType.builder().control(c2).name("증빙 C").build());

        // 수집 작업 1개 (et1 연결)
        collectionJobRepository.save(CollectionJob.builder()
                .name("자동수집1")
                .jobType(JobType.excel_extract)
                .scriptPath("/scripts/a.py")
                .evidenceType(et1)
                .build());

        // pending 파일 2개 (et1, et2 에 각 1건) + approved 1개 (et3) → pending 만 집계
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et1).fileName("p1.pdf").filePath("/tmp/p1.pdf").fileSize(1L).version(1)
                .collectionMethod(CollectionMethod.manual).collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.pending).build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et2).fileName("p2.pdf").filePath("/tmp/p2.pdf").fileSize(1L).version(1)
                .collectionMethod(CollectionMethod.manual).collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.pending).build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et3).fileName("done.pdf").filePath("/tmp/done.pdf").fileSize(1L).version(1)
                .collectionMethod(CollectionMethod.manual).collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.approved).build());

        mockMvc.perform(get("/api/v1/frameworks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].controlCount").value(2))
                .andExpect(jsonPath("$.data[0].evidenceTypeCount").value(3))
                .andExpect(jsonPath("$.data[0].jobCount").value(1))
                .andExpect(jsonPath("$.data[0].pendingReviewCount").value(2));

        System.out.println("✅ [Counts] 집계 정확 (pending 만 카운트됨)");
    }

    // ==================================================================
    // 3. 상속 관계
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("[Parent] 상속 관계 → parentFrameworkId / Name 반영")
    void testParentFrameworkField() throws Exception {
        Framework parent = frameworkRepository.save(Framework.builder().name("ISMS-P 2025").build());
        Framework child = Framework.builder().name("ISMS-P 2026").build();
        child.setParentFramework(parent);
        frameworkRepository.save(child);

        mockMvc.perform(get("/api/v1/frameworks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                // 응답에 2건 모두 있음. 자식을 찾아 parent 정보 확인.
                .andExpect(jsonPath("$.data[?(@.name == 'ISMS-P 2026')].parentFrameworkName")
                        .value("ISMS-P 2025"));

        System.out.println("✅ [Parent] parentFrameworkId / Name 정상 반영");
    }

    // ==================================================================
    // 4. 상태 반영
    // ==================================================================

    @Test
    @Order(4)
    @DisplayName("[Status] Framework archive → status='archived' 응답")
    void testStatusArchived() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("Old FW").build());
        fw.archive();
        frameworkRepository.save(fw);

        mockMvc.perform(get("/api/v1/frameworks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.name == 'Old FW')].status").value("archived"));

        System.out.println("✅ [Status] archive 상태 응답 반영");
    }

    // ==================================================================
    // 5. pending 외 다른 상태는 모두 제외
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("[Filter] approved/rejected/auto_approved 파일은 pendingReviewCount 에서 제외")
    void testPendingOnly() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("Mixed FW").build());
        Control c = controlRepository.save(Control.builder().framework(fw).code("B-1").name("통제").build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder().control(c).name("증빙").build());

        // 4가지 상태 파일 각 1건
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et).fileName("a.pdf").filePath("/tmp/a.pdf").fileSize(1L).version(1)
                .collectionMethod(CollectionMethod.manual).collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.pending).build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et).fileName("b.pdf").filePath("/tmp/b.pdf").fileSize(1L).version(2)
                .collectionMethod(CollectionMethod.manual).collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.approved).build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et).fileName("c.pdf").filePath("/tmp/c.pdf").fileSize(1L).version(3)
                .collectionMethod(CollectionMethod.manual).collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.rejected).build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et).fileName("d.pdf").filePath("/tmp/d.pdf").fileSize(1L).version(4)
                .collectionMethod(CollectionMethod.manual).collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.auto_approved).build());

        mockMvc.perform(get("/api/v1/frameworks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pendingReviewCount").value(1));

        System.out.println("✅ [Filter] pending 만 집계 (approved/rejected/auto_approved 제외)");
    }
}