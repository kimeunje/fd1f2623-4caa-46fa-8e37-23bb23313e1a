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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase v16.4a — 관리자 대시보드 위젯 (BE) 통합 테스트.
 *
 * <h3>v18.4 — Dashboard 정공 진단 + 회귀 fix 완료 (가설 A 확정)</h3>
 * <p>v18.3 시점 {@code @Disabled} 처리됐던 6 케이스 모두 정공 fix 후 active green.
 * v18.3 의 1차 원인 진단 ("SecurityConfig dashboard URL 매핑 부재") 은 SecurityConfig 에
 * {@code /api/v1/dashboard/**.hasRole("ADMIN")} 추가로 운영 환경 안전망 확보 (v18.3 commit
 * 보존). 그러나 본 테스트 환경 (MockMvc) 의 회귀 원인은 그것이 아니라 본 클래스의
 * <b>MockMvc 빌드 패턴</b>이었음.</p>
 *
 * <h3>가설 A — MockMvc 빌드 패턴 outlier (v18.4 확정)</h3>
 * <p>본 프로젝트의 다른 통합 테스트 (TreeUpdateTest / EvidencePermissionTest / MyTasksTest /
 * Phase514fIntegrationTest / Phase514gBackendTest 등) 는 모두 다음 표준 패턴 사용:</p>
 * <pre>
 *   {@literal @}SpringBootTest
 *   {@literal @}ActiveProfiles("test")
 *   {@literal @}AutoConfigureMockMvc          ← Spring Boot 가 SpringSecurityFilterChain 자동 wiring
 *   class XxxTest {
 *       {@literal @}Autowired private MockMvc mockMvc;
 * </pre>
 *
 * <p>v18.3 시점 본 클래스는 유일한 outlier 였음:</p>
 * <pre>
 *   {@literal @}SpringBootTest
 *   {@literal @}ActiveProfiles("test")
 *   // {@literal @}AutoConfigureMockMvc 없음                    ← 회귀 원인
 *   class DashboardTest {
 *       {@literal @}Autowired private WebApplicationContext context;
 *       private MockMvc mockMvc;
 *       {@literal @}BeforeEach void setUp() {
 *           mockMvc = MockMvcBuilders.webAppContextSetup(context).build();  // ← 필터 chain 자동 attach 안 됨
 *       }
 * </pre>
 *
 * <p><b>회귀 메커니즘</b>: {@code webAppContextSetup(context).build()} 는 기본적으로
 * SpringSecurityFilterChain 을 자동 적용하지 않음 ({@code apply(springSecurity())} 명시 필요).
 * 결과적으로 anonymous 요청이 URL 레벨 hasRole 매핑을 건너뛰고 controller method 레벨
 * {@code @PreAuthorize} 평가까지 도달 → SecurityContext 가 비어있는 anonymous 상태에서
 * {@code AuthenticationCredentialsNotFoundException} throw → {@code GlobalExceptionHandler}
 * 의 catch-all 핸들러가 500 매핑. 6 케이스 모두 같은 경로로 500.</p>
 *
 * <h3>v18.4 정공 fix</h3>
 * <ol>
 *   <li>클래스 어노테이션에 {@code @AutoConfigureMockMvc} 추가 → SpringBootTest 가 MockMvc 빈을
 *       자동 wiring, SpringSecurityFilterChain 자동 attach.</li>
 *   <li>{@code @Autowired private WebApplicationContext context;} 필드 제거.</li>
 *   <li>{@code private MockMvc mockMvc;} → {@code @Autowired private MockMvc mockMvc;} 로 전환.</li>
 *   <li>{@code @BeforeEach setUp} 의 첫 줄 (수동 MockMvc 빌드) 제거. cleanup 로직만 보존.</li>
 *   <li>{@code @Disabled} 어노테이션 제거 — 6 케이스 모두 active 로 복원.</li>
 *   <li>관련 import 정리: {@code MockMvcBuilders} / {@code WebApplicationContext} 제거,
 *       {@code AutoConfigureMockMvc} 추가.</li>
 * </ol>
 *
 * <h3>v18.4 후속 진단 단계 (가설 A 가 맞으므로 미실행)</h3>
 * <p>다음 단계는 가설 A 가 어긋난 경우 진입 예정이었음 — v18.4 에서 가설 A 단독으로 6 케이스
 * 모두 green 회복 시 미실행 (v18.3 commit 본질 보존):</p>
 * <ul>
 *   <li>gradle daemon 재시작 + build/.gradle 캐시 강제 삭제 후 재빌드</li>
 *   <li>{@code .andDo(print())} 추가하여 backend exception stack trace 노출</li>
 * </ul>
 *
 * <h3>아래 6 케이스 의도 (v16.4a 시점 원본 보존)</h3>
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
 * <h3>setUp 패턴 의도 보존 (v18.3 회귀 fix 사후 보존)</h3>
 * <p>본 클래스의 setUp 패턴은 v18.3 fix 작업 시점에 갱신됨 — v18.4 에서도 그대로 보존:</p>
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
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase v16.4a — 관리자 대시보드 위젯 (BE)")
class DashboardTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @Autowired private UserRepository userRepository;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;

    private String adminToken;
    private String developerToken;
    private User adminUser;
    private User uploader;

    @BeforeEach
    void setUp() {
        // v18.3 회귀 fix 보존 — user 도 cleanup 후 매 케이스 재생성.
        // 이전 @BeforeAll setUpAll + @BeforeEach cleanData (evidence/control/framework 만)
        // 패턴은 다른 테스트 클래스의 userRepository.deleteAll() leftover 영향 (테스트
        // 클래스 실행 순서에 의존). v18.3 cleanup 패턴 변경 후 재현 확인되어 패턴 정합:
        // 매 케이스 격리 (다른 테스트 클래스 leftover 무관 보장).
        //
        // v18.4: MockMvc 수동 빌드 라인 제거 (가설 A fix). @Autowired MockMvc 가
        // @AutoConfigureMockMvc 어노테이션 효과로 SpringSecurityFilterChain 자동 attach.
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