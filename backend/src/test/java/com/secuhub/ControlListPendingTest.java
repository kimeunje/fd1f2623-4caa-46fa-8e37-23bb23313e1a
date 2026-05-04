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
 * Phase 5-9 — Tree API 의 pendingReviewCount 집계 검증 (v15.4 /tree 이전)
 *
 * <h3>v14 Phase 5-14f 회귀 픽스 (패턴 A — 평면 leaf depth=1)</h3>
 * <p>{@code Control.builder()} → {@code ControlNode.builder().nodeType(NodeType.control)
 * .displayOrder(0).depth(1).build()}. {@link EvidenceType#getControl()} 의 타입이
 * {@link ControlNode} 로 변경되어 builder 의 {@code .control(leaf)} 호출 자연 매칭.</p>
 *
 * <h3>v15.4 회귀 정리 — endpoint 이전 (의미 보존, 케이스 5 그대로)</h3>
 * <p>v15.3 의 {@code ControlController.java} 통째 삭제로 GET
 * {@code /api/v1/frameworks/{fwId}/controls} 폐기 (v14.3 결정 — TreeController 분리,
 * v15 에서 ControlController 자연 제거 시 잔존). 본 회귀 픽스는 GET
 * {@code /api/v1/frameworks/{fwId}/tree} 로 이전. v15.2 후속-1 의 {@code toNodeSummary}
 * leaf 한정 분기 제거로 모든 노드 (category + leaf, hybrid 포함) 에 evidenceTypeCount /
 * pendingReviewCount / collectedCount 가 노출되어 의미 동등 보존. assertion path 만
 * {@code $.data[i]} → {@code $.data.nodes[i]} 로 변경, 5 케이스 의미 그대로.</p>
 *
 * <h3>검증 항목 (의미 보존)</h3>
 * <ul>
 *   <li>GET /api/v1/frameworks/{fwId}/tree 응답의 모든 노드에 pendingReviewCount 포함 (v15.2 후속-1)</li>
 *   <li>Control 간 집계 분리 — A control 의 pending 이 B control 에 누적되지 않음</li>
 *   <li>pending 아닌 상태(approved / rejected / auto_approved)는 집계 제외</li>
 *   <li>빈 Control / 파일 0개인 Control → pendingReviewCount=0</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-9 — Tree API pending 집계 (v15.4 /tree 이전)")
class ControlListPendingTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;   // ← 5-14f
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
        controlNodeRepository.deleteAll();   // ← 5-14f
        frameworkRepository.deleteAll();
        userRepository.deleteAll();

        User admin = userRepository.save(User.builder()
                .email("ctrlpending-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin).permissionEvidence(true)
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
        createLeaf(framework, "X-1", "빈 통제");

        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", framework.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].code").value("X-1"))
                .andExpect(jsonPath("$.data.nodes[0].pendingReviewCount").value(0));

        System.out.println("✅ [Empty] 빈 Control pendingReviewCount=0");
    }

    // ==================================================================
    // 2. Control 간 집계 분리
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("[Isolation] Control A 에 pending 2건, Control B 에 0건 → 서로 격리")
    void testPendingIsolatedPerControl() throws Exception {
        ControlNode a = createLeaf(framework, "A-1", "통제 A");
        ControlNode b = createLeaf(framework, "B-1", "통제 B");

        EvidenceType etA = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(a).name("증빙 A").build());
        evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(b).name("증빙 B").build());

        // A 에만 pending 2건
        savePending(etA, "a1.pdf", 1);
        savePending(etA, "a2.pdf", 2);

        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", framework.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[?(@.code == 'A-1')].pendingReviewCount").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.code == 'B-1')].pendingReviewCount").value(0));

        System.out.println("✅ [Isolation] Control 간 pending 집계 격리 확인");
    }

    // ==================================================================
    // 3. pending 외 상태는 모두 제외
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("[Filter] approved/rejected/auto_approved 는 pendingReviewCount 에서 제외")
    void testOnlyPendingCounted() throws Exception {
        ControlNode c = createLeaf(framework, "C-1", "혼합 통제");
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(c).name("증빙").build());

        // 4가지 상태 각 1건, pending 만 집계되어야 함
        saveWithStatus(et, "p.pdf", 1, ReviewStatus.pending);
        saveWithStatus(et, "a.pdf", 2, ReviewStatus.approved);
        saveWithStatus(et, "r.pdf", 3, ReviewStatus.rejected);
        saveWithStatus(et, "auto.pdf", 4, ReviewStatus.auto_approved);

        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", framework.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].pendingReviewCount").value(1));

        System.out.println("✅ [Filter] pending 만 집계 (다른 상태 제외)");
    }

    // ==================================================================
    // 4. 여러 evidence_type 의 pending 이 같은 Control 로 합산
    // ==================================================================

    @Test
    @Order(4)
    @DisplayName("[Aggregation] 같은 Control 의 서로 다른 증빙 유형 pending 은 합산")
    void testPendingAggregatedAcrossEvidenceTypes() throws Exception {
        ControlNode c = createLeaf(framework, "D-1", "다중증빙 통제");
        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(c).name("증빙 1").build());
        EvidenceType et2 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(c).name("증빙 2").build());
        EvidenceType et3 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(c).name("증빙 3").build());

        // 3 evidence_type 에 pending 각 1건씩 → 총 3
        savePending(et1, "1.pdf", 1);
        savePending(et2, "2.pdf", 1);
        savePending(et3, "3.pdf", 1);

        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", framework.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].pendingReviewCount").value(3));

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
        ControlNode e = createLeaf(framework, "E-1", "통제 E");
        EvidenceType etE = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(e).name("증빙 E").build());
        savePending(etE, "e.pdf", 1);

        // 다른 Framework 에 Control F, pending 5건 (영향 없어야 함)
        Framework otherFw = frameworkRepository.save(Framework.builder().name("Other FW").build());
        ControlNode f = createLeaf(otherFw, "F-1", "통제 F");
        EvidenceType etF = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(f).name("증빙 F").build());
        for (int i = 1; i <= 5; i++) {
            savePending(etF, "f" + i + ".pdf", i);
        }

        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", framework.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].code").value("E-1"))
                .andExpect(jsonPath("$.data.nodes[0].pendingReviewCount").value(1));

        System.out.println("✅ [Scope] Framework 단위 pending 격리 확인");
    }

    // ==================================================================
    // helpers — v14 Phase 5-14f
    // ==================================================================

    /**
     * v14 Phase 5-14f — 평면 ControlNode leaf 생성 (패턴 A: depth=1, displayOrder 자동 0).
     * 같은 Framework 안의 leaf 가 모두 root 직속 (parent=null) 이라 displayOrder 충돌 없음.
     */
    private ControlNode createLeaf(Framework fw, String code, String name) {
        return controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code(code).name(name).displayOrder(0).depth(1).build());
    }

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