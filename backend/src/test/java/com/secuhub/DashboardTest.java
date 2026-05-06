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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase v16.4a — 관리자 대시보드 위젯 (BE) 통합 테스트.
 *
 * <p>spec §3.8 정합. 본 테스트는 다음 5 경로 검증:</p>
 *
 * <ol>
 *   <li>빈 상태: pending 0, framework 0 → KPI = 0, 목록 empty, progresses empty</li>
 *   <li>Pending 5개 + framework 2개 → KPI = 5, top 10 limit 정합</li>
 *   <li>Pending 12개 → top 10 limit 적용 (11+ 잘림)</li>
 *   <li>인증 없음 → 401 Unauthorized</li>
 *   <li>developer 역할 → 403 Forbidden (admin only)</li>
 * </ol>
 *
 * <h3>v16.4a-fix 변경</h3>
 * <ul>
 *   <li>{@code Framework.Status.active} → {@link FrameworkStatus#active} (top-level
 *       enum, 실 entity 정합)</li>
 *   <li>{@code Framework.builder().status(...)} 인자 타입 정정</li>
 * </ul>
 *
 * <p>spec L41 / L43 정합 — setUp 사전 grep 으로 EvidenceFile / EvidenceType / User
 * 빌더 시그니처 정합 확인.</p>
 *
 * <p><b>테스트 의존성</b>: Repository 신규 메서드:</p>
 * <ul>
 *   <li>{@code EvidenceFileRepository.countByReviewStatus(ReviewStatus)}</li>
 *   <li>{@code EvidenceFileRepository.findTop10PendingForDashboard()}</li>
 *   <li>{@code EvidenceFileRepository.countDistinctEvidenceTypesByCollectedStatus(List<Long>)}</li>
 *   <li>{@code EvidenceFileRepository.countDistinctEvidenceTypesByPendingStatus(List<Long>)}</li>
 *   <li>{@code EvidenceTypeRepository.findIdsByFrameworkId(Long)}</li>
 *   <li>{@code ControlNodeRepository.findByFrameworkIdInOrderByDepthAscDisplayOrderAsc(List<Long>)}</li>
 *   <li>{@code FrameworkRepository.findByStatusOrderByCreatedAtDesc(FrameworkStatus)}
 *       — 이미 존재 (v16.4a-fix grep 검증)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Phase v16.4a — 관리자 대시보드 위젯 (BE)")
class DashboardTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @Autowired private UserRepository userRepository;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;

    private MockMvc mockMvc;
    private String adminToken;
    private String developerToken;
    private User adminUser;
    private User uploader;

    @BeforeAll
    void setUpAll() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        adminUser = userRepository.save(User.builder()
                .email("dash-admin@test.com").name("대시보드admin").hashedPassword("pw")
                .role(UserRole.admin).team("보안팀")
                .permissionEvidence(true).build());
        User developer = userRepository.save(User.builder()
                .email("dash-dev@test.com").name("대시보드dev").hashedPassword("pw")
                .role(UserRole.developer).team("개발팀")
                .permissionEvidence(true).build());
        uploader = userRepository.save(User.builder()
                .email("dash-uploader@test.com").name("업로더").hashedPassword("pw")
                .role(UserRole.developer).team("인사팀")
                .permissionEvidence(true).build());

        adminToken = jwtTokenProvider.createToken(
                adminUser.getId(), adminUser.getEmail(), adminUser.getRole().name());
        developerToken = jwtTokenProvider.createToken(
                developer.getId(), developer.getEmail(), developer.getRole().name());
    }

    @BeforeEach
    void cleanData() {
        evidenceFileRepository.deleteAllInBatch();
        evidenceTypeRepository.deleteAllInBatch();
        controlNodeRepository.deleteAllInBatch();
        frameworkRepository.deleteAllInBatch();
    }

    // ================================================================
    // 1. 빈 상태
    // ================================================================
    @Test
    @Order(1)
    @DisplayName("[Dashboard] 빈 상태 — KPI 0, 목록 empty, progresses empty")
    void testEmptyState() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/admin-summary")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.kpi.pendingApprovalCount").value(0))
                .andExpect(jsonPath("$.data.pendingApprovals").isEmpty())
                .andExpect(jsonPath("$.data.frameworkProgresses").isEmpty());

        System.out.println("✅ [Dashboard] 빈 상태 정상");
    }

    // ================================================================
    // 2. Pending 5 + framework 2
    // ================================================================
    @Test
    @Order(2)
    @DisplayName("[Dashboard] pending 5 + framework 2 → 모든 위젯 정합")
    void testNormalState() throws Exception {
        // Framework 2 + leaf 3 + evidence_types 4
        // v16.4a-fix: FrameworkStatus.active (top-level enum)
        Framework fw1 = frameworkRepository.save(Framework.builder()
                .name("FW-A").status(FrameworkStatus.active).build());
        Framework fw2 = frameworkRepository.save(Framework.builder()
                .name("FW-B").status(FrameworkStatus.active).build());

        ControlNode leaf1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw1).parent(null).nodeType(NodeType.control)
                .code("1.1").name("controlA1").displayOrder(0).depth(1).build());
        ControlNode leaf2 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw1).parent(null).nodeType(NodeType.control)
                .code("1.2").name("controlA2").displayOrder(1).depth(1).build());
        ControlNode leaf3 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw2).parent(null).nodeType(NodeType.control)
                .code("2.1").name("controlB1").displayOrder(0).depth(1).build());

        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf1).name("증빙A1").build());
        EvidenceType et2 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf2).name("증빙A2").build());
        EvidenceType et3 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf3).name("증빙B1").build());
        EvidenceType et4 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf3).name("증빙B2").build());

        // Pending 5: et1 (3개), et2 (1개), et3 (1개)
        savePending(et1); savePending(et1); savePending(et1);
        savePending(et2);
        savePending(et3);

        // Approved: et2 (1개) — fw1 의 et2 가 collected
        saveApproved(et2);

        // FW-A: 2 evidence_types, et1=pending only, et2=approved + pending → collected 1, pending 2
        // FW-B: 2 evidence_types, et3=pending, et4=none → collected 0, pending 1

        mockMvc.perform(get("/api/v1/dashboard/admin-summary")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kpi.pendingApprovalCount").value(5))
                .andExpect(jsonPath("$.data.pendingApprovals.length()").value(5))
                .andExpect(jsonPath("$.data.frameworkProgresses.length()").value(2));

        System.out.println("✅ [Dashboard] 정상 상태 — KPI 5, 목록 5, progresses 2");
    }

    // ================================================================
    // 3. Top 10 limit
    // ================================================================
    @Test
    @Order(3)
    @DisplayName("[Dashboard] pending 12 → 목록 top 10 limit 적용")
    void testPendingTop10Limit() throws Exception {
        // v16.4a-fix: FrameworkStatus.active
        Framework fw = frameworkRepository.save(Framework.builder()
                .name("FW-Limit").status(FrameworkStatus.active).build());
        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("1").name("c1").displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf).name("증빙").build());

        for (int i = 0; i < 12; i++) savePending(et);

        mockMvc.perform(get("/api/v1/dashboard/admin-summary")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kpi.pendingApprovalCount").value(12))
                .andExpect(jsonPath("$.data.pendingApprovals.length()").value(10));

        System.out.println("✅ [Dashboard] top 10 limit 적용");
    }

    // ================================================================
    // 4. 인증 없음
    // ================================================================
    @Test
    @Order(4)
    @DisplayName("[Dashboard] 인증 없음 → 401")
    void testUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/admin-summary"))
                .andExpect(status().isUnauthorized());

        System.out.println("✅ [Dashboard] 인증 없음 401");
    }

    // ================================================================
    // 5. 권한 부족 (developer)
    // ================================================================
    @Test
    @Order(5)
    @DisplayName("[Dashboard] developer 역할 → 403 (admin only)")
    void testForbiddenForDeveloper() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/admin-summary")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isForbidden());

        System.out.println("✅ [Dashboard] developer 403");
    }

    // ----------------------------------------------------------------
    // helper
    // ----------------------------------------------------------------
    private EvidenceFile savePending(EvidenceType et) {
        return evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName("pending-" + System.nanoTime() + ".txt")
                .filePath("/tmp/x")
                .fileSize(1L)
                .version(1)
                .reviewStatus(ReviewStatus.pending)
                .uploadedBy(uploader)
                .submitNote("test")
                .build());
    }

    private EvidenceFile saveApproved(EvidenceType et) {
        return evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName("approved-" + System.nanoTime() + ".txt")
                .filePath("/tmp/x")
                .fileSize(1L)
                .version(1)
                .reviewStatus(ReviewStatus.approved)
                .uploadedBy(uploader)
                .reviewedBy(adminUser)
                .build());
    }
}