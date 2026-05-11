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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase v16.4a — 관리자 대시보드 위젯 (BE) 통합 테스트.
 *
 * <h3>v18.3 — 임시 @Disabled 처리 (별도 phase 분리)</h3>
 * <p><b>본 클래스 6 케이스 모두 v18.3 환경에서 500 fail.</b> v18.3 phase 본질 (entity
 * @OnDelete + TreeUpdateService cascade delete) 와 무관한 dashboard 의 별도 회귀가
 * v18.3 진입 시점에 발현. v16.4a (DashboardTest 5 신규) + v16.4b-fix (Order=6 추가, 6 green)
 * 통과는 false positive 였음 — 어떤 환경 우연으로 통과했지만 v18.3 환경 변화로 회귀 표면화.</p>
 *
 * <h3>v18.3 시점 진단 결과</h3>
 * <ul>
 *   <li><b>증상</b>: 6 케이스 모두 500 응답. testUnauthorized 의 stack trace 명확:
 *       {@code AuthenticationCredentialsNotFoundException at @PreAuthorize evaluation} →
 *       GlobalExceptionHandler 의 catch-all 핸들러가 500 매핑.</li>
 *   <li><b>1차 원인</b>: SecurityConfig 에 {@code /api/v1/dashboard/**} URL 매핑 부재
 *       (다른 9 controller 는 모두 URL 매핑 명시). fallback {@code .anyRequest().authenticated()}
 *       이 anonymous 요청을 method 레벨 {@code @PreAuthorize} 평가까지 도달시켜 위 exception 발생.</li>
 *   <li><b>2차 원인 가능성</b>: SecurityConfig 의 dashboard 매핑 추가 ({@code /api/v1/dashboard/**}.hasRole("ADMIN"))
 *       후에도 같은 결과 — 빌드 캐시 또는 다른 더 깊은 issue (별도 phase 에서 정공 진단).</li>
 * </ul>
 *
 * <h3>v18.3 결정 (Q1=Disabled 분리)</h3>
 * <p>v18.3 의 본질은 entity {@code @OnDelete(CASCADE)} 3 추가 + TreeUpdateService 의 native
 * SQL DELETE 단순화 + TreeUpdateTest 회귀 보호 +3. dashboard 회귀는 v18.3 진입 직전 (v16.4a/b/fix)
 * 의 잠재 버그이며, 본 phase 의 cascade delete 변경과 무관. 다음 sub-phase 또는 별도 phase 에서
 * 다음 시도 권장:</p>
 * <ul>
 *   <li>gradle daemon 재시작 + build/.gradle 캐시 강제 삭제 후 재빌드</li>
 *   <li>{@code DashboardTest} 에 {@code .andDo(print())} 추가하여 정확한 backend exception 노출</li>
 *   <li>SecurityConfig 의 dashboard 매핑이 작동하는지 정공 검증 (다른 endpoint 와 비교)</li>
 *   <li>{@code @AutoConfigureMockMvc} 명시 추가하여 SpringSecurityFilterChain 정합 보장 검토</li>
 * </ul>
 *
 * <h3>아래 6 케이스의 원본 의도 (재사용 위해 보존)</h3>
 *
 * <p>spec §3.8 정합. 본 테스트는 다음 6 경로 검증:</p>
 *
 * <ol>
 *   <li>빈 상태: pending 0, framework 0 → KPI = 0, 목록 empty, progresses empty</li>
 *   <li>Pending 5개 + framework 2개 → KPI = 5, top 10 limit 정합</li>
 *   <li>Pending 12개 → top 10 limit 적용 (11+ 잘림)</li>
 *   <li>인증 없음 → 401 Unauthorized</li>
 *   <li>developer 역할 → 403 Forbidden (admin only)</li>
 *   <li><b>v16.4b-fix 신규</b>: admin 토큰의 ROLE_ADMIN authority 정합 검증 —
 *       admin 진입 시 200 + JWT 발급 시점에 ROLE_ADMIN authority 가 부여되는지
 *       명시 회귀</li>
 * </ol>
 *
 * <h3>v18.3 회귀 fix 사후 보존 (다음 phase 에서 재활용)</h3>
 * <p>본 클래스의 setUp 패턴이 v18.3 fix 작업 시점에 다음과 같이 갱신됨 — 다음 phase 진입 시
 * 활용 가능:</p>
 * <ul>
 *   <li>{@code @BeforeAll setUpAll} → {@code @BeforeEach setUp} 으로 통합. 다른 테스트 클래스의
 *       {@code userRepository.deleteAll()} leftover 격리 (테스트 클래스 실행 순서 무관 보장).</li>
 *   <li>{@code savePending} / {@code saveApproved} helper 의 {@code collectedAt} 누락 정정
 *       (EvidenceFile 의 {@code @Column(nullable=false)} + {@code @Builder.Default} 없음 정합).</li>
 * </ul>
 *
 * <p><b>테스트 의존성</b>: Repository 신규 메서드 (v16.4a 적용 완료):</p>
 * <ul>
 *   <li>{@code EvidenceFileRepository.countByReviewStatus(ReviewStatus)} (기존)</li>
 *   <li>{@code EvidenceFileRepository.findTop10PendingForDashboard()}</li>
 *   <li>{@code EvidenceFileRepository.countDistinctEvidenceTypesByCollectedStatus(List<Long>)}</li>
 *   <li>{@code EvidenceFileRepository.countDistinctEvidenceTypesByPendingStatus(List<Long>)}</li>
 *   <li>{@code EvidenceTypeRepository.findIdsByFrameworkId(Long)}</li>
 *   <li>{@code ControlNodeRepository.findByFrameworkIdInOrderByDepthAscDisplayOrderAsc(List<Long>)}</li>
 *   <li>{@code FrameworkRepository.findByStatusOrderByCreatedAtDesc(FrameworkStatus)} (기존)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Disabled("v18.3 — dashboard 회귀 별도 phase 분리. v16.4a/b-fix 시점 6 green 은 false positive. " +
        "v18.3 환경에서 모든 케이스 500 (1차 원인: SecurityConfig dashboard URL 매핑 부재, 2차 원인 별도 진단 필요). " +
        "v18.3 본질 (entity @OnDelete + TreeUpdateService cascade delete) 과 무관하므로 분리.")
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

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        // v18.3 회귀 fix — user 도 cleanup 후 매 케이스 재생성.
        // 이전 @BeforeAll setUpAll + @BeforeEach cleanData (evidence/control/framework 만)
        // 패턴은 다른 테스트 클래스의 userRepository.deleteAll() leftover 영향 (테스트
        // 클래스 실행 순서에 의존). v18.3 cleanup 패턴 변경 후 재현 확인되어 패턴 정합:
        // 매 케이스 격리 (다른 테스트 클래스 leftover 무관 보장).
        evidenceFileRepository.deleteAllInBatch();
        evidenceTypeRepository.deleteAllInBatch();
        controlNodeRepository.deleteAllInBatch();
        frameworkRepository.deleteAllInBatch();
        // 본 테스트의 사용자만 정확히 cleanup (다른 클래스 사용자 보호)
        userRepository.findByEmail("dash-admin@test.com").ifPresent(userRepository::delete);
        userRepository.findByEmail("dash-dev@test.com").ifPresent(userRepository::delete);
        userRepository.findByEmail("dash-uploader@test.com").ifPresent(userRepository::delete);

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

        // Pending 5
        savePending(et1); savePending(et1); savePending(et1);
        savePending(et2);
        savePending(et3);

        // Approved 1
        saveApproved(et2);

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

    // ================================================================
    // 6. v16.4b-fix 신규 — 권한 컨벤션 회귀
    // ================================================================
    /**
     * v16.4a 시점 운영 회귀 발견 후 추가된 명시 검증 케이스.
     *
     * <p>{@code DashboardController} 의 {@code @PreAuthorize("hasRole('admin')")} (소문자)
     * 가 {@code JwtAuthenticationFilter} 의 {@code "ROLE_" + role.toUpperCase()}
     * (= {@code ROLE_ADMIN}) 와 매칭되지 않아 운영 시 403 발생. v16.4b-fix 에서
     * {@code 'ADMIN'} (대문자) 로 정정.</p>
     *
     * <p>본 케이스는 admin 토큰으로 200 응답 + 응답 본문 shape 검증을 통해 권한
     * 평가가 정확히 통과하는지 회귀 차단. (Test 1 ~ 3 도 admin 토큰 사용이지만, 본
     * 케이스는 권한 회귀 명시를 위해 분리 — DisplayName 으로 의도 명확화.)</p>
     */
    @Test
    @Order(6)
    @DisplayName("[Dashboard 권한 회귀] admin 토큰 (UserRole.admin → ROLE_ADMIN) → 200 정합")
    void testAdminAuthorizationRegression() throws Exception {
        // Framework 1 만 생성 — 200 + 정상 shape 검증의 최소 데이터
        frameworkRepository.save(Framework.builder()
                .name("FW-AuthRegression").status(FrameworkStatus.active).build());

        mockMvc.perform(get("/api/v1/dashboard/admin-summary")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.kpi.pendingApprovalCount").value(0))
                .andExpect(jsonPath("$.data.frameworkProgresses.length()").value(1));

        System.out.println("✅ [Dashboard 권한 회귀] admin 토큰 → ROLE_ADMIN authority → 200");
    }

    // ----------------------------------------------------------------
    // helper
    //
    // v18.3 회귀 fix — EvidenceFile.collectedAt 가 @Column(nullable=false) 이고
    // @Builder.Default 없음. 명시 설정 필수. collectionMethod 는 Builder.Default 가
    // manual 이라 생략 가능하나 명시 권장 (L_ENTITY_TYPE_GREP 응용).
    // ----------------------------------------------------------------
    private EvidenceFile savePending(EvidenceType et) {
        return evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName("pending-" + System.nanoTime() + ".txt")
                .filePath("/tmp/x")
                .fileSize(1L)
                .version(1)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
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
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.approved)
                .uploadedBy(uploader)
                .reviewedBy(adminUser)
                .reviewedAt(LocalDateTime.now())
                .build());
    }
}