package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.dto.FrameworkDto;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 5-14f — JPA 매핑 정합화 + leaf 카운트 본격 집계 + 트리 재귀 복제 통합 검증.
 *
 * <h3>v15 Phase 5-15a — testAddEvidenceTypeLeafOnly 삭제</h3>
 * <p>5-14f 의 leaf-only invariant (category 호출 시 IllegalStateException) 가 v15
 * 5-15a 의 hybrid 모델 채택으로 제거됨 → 본 테스트도 무효화 → 삭제. 5-15a hybrid
 * 검증은 신규 클래스 {@code Phase515aHybridIntegrationTest} 가 커버
 * (Order=1 testHybridAddEvidenceTypeAllNodes 가 inverted 후속). 5-14f 의 mapping
 * 정합성 검증은 본 클래스의 {@code testEvidenceTypeMapsToControlNode} (Order=1) 가
 * 충분히 커버.</p>
 *
 * <h3>검증 항목 (4건 — 5-15a 후 testAddEvidenceTypeLeafOnly 삭제로 5 → 4)</h3>
 * <ul>
 *   <li>{@link EvidenceType#getControl()} 의 타입이 {@link ControlNode} (leaf) 직접 매칭</li>
 *   <li>{@code GET /api/v1/frameworks/{id}/tree} 응답의 leaf {@code evidenceTypeCount} /
 *       {@code pendingReviewCount} 가 본격 집계됨 (5-14c 의 0 고정 → 양수)</li>
 *   <li>{@code POST /api/v1/frameworks/inherit} 의 source 가 5단 mixed-depth 트리일 때
 *       target 도 같은 5단 트리 + evidence_types 의 owner/dueDate 보존</li>
 *   <li>5-14e 의 {@code GET /api/v1/control-nodes/{id}/impact-summary} 가 5-14f 후 자연
 *       정상 카운트 (dev/test 환경에서 ControlNode.id 직접 매칭)</li>
 * </ul>
 *
 * <h3>LAZY 처리 패턴 (5-14a 학습 재적용)</h3>
 * <ul>
 *   <li>mockMvc 안 쓰는 테스트 (testEvidenceTypeMapsToControlNode) — 메서드 레벨
 *       {@code @Transactional} 추가로 lazy proxy hydrate 보장</li>
 *   <li>mockMvc 쓰는 테스트 (testInheritRecursiveTree) — outer {@code @Transactional}
 *       이 mockMvc 의 controller TX commit 을 rollback 하므로 못 씀.
 *       lazy chain 검증을 id 비교로 회피 (의미 보존)</li>
 * </ul>
 *
 * <p>spec §3.3.1.3 / §3.3.1.4 / §6.4 정합. v14.5 신규 (Phase 5-14f).
 * v15 Phase 5-15a 시점에 Order=2 케이스 + helper 2개 삭제.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-14f — JPA 매핑 정합화 + 카운트 집계 + 트리 재귀 복제")
class Phase514fIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;
    @Autowired private CollectionJobRepository collectionJobRepository;

    private String adminToken;
    private User adminUser;

    @BeforeEach
    void setUp() {
        // FK 역순 cleanup
        evidenceFileRepository.deleteAll();
        collectionJobRepository.deleteAll();
        evidenceTypeRepository.deleteAll();
        controlNodeRepository.deleteAll();
        frameworkRepository.deleteAll();
        userRepository.deleteAll();

        adminUser = userRepository.save(User.builder()
                .email("phase-5-14f-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin)
                .permissionEvidence(true)
                .build());
        adminToken = jwtTokenProvider.createToken(adminUser.getId(), adminUser.getEmail(), "admin");
    }

    // ====================================================================
    // 1. EvidenceType.control 의 타입이 ControlNode 자연 매칭
    //    @Transactional — reloaded.getControlNode().getNodeType() lazy hydrate 보장
    //    (mockMvc 안 쓰므로 outer TX rollback issue 없음)
    // ====================================================================

    @Test
    @Order(1)
    @Transactional
    @DisplayName("[Mapping] EvidenceType.control 이 leaf ControlNode 직접 매칭")
    void testEvidenceTypeMapsToControlNode() {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW-mapping").build());

        ControlNode root = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("대분류").displayOrder(0).depth(1).build());
        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(root).nodeType(NodeType.control)
                .code("1.1").name("leaf 통제").displayOrder(0).depth(2).build());

        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf)            // 5-14f: ControlNode 직접 전달
                .name("정책 문서")
                .build());

        // EvidenceType.control 의 타입이 ControlNode 매핑 자연 작동
        // @Transactional 의 TX 안에서 lazy proxy hydrate (.getNodeType()) 가능
        EvidenceType reloaded = evidenceTypeRepository.findById(et.getId()).orElseThrow();
        assertThat(reloaded.getControlNode()).isInstanceOf(ControlNode.class);
        assertThat(reloaded.getControlNode().getId()).isEqualTo(leaf.getId());
        assertThat(reloaded.getControlNode().getNodeType()).isEqualTo(NodeType.control);

        // EvidenceTypeRepository.findByControlId 의 의미가 자연 변경됨 — ControlNode.id 매칭
        List<EvidenceType> byLeaf = evidenceTypeRepository.findByControlNodeId(leaf.getId());
        assertThat(byLeaf).hasSize(1);
        assertThat(byLeaf.get(0).getId()).isEqualTo(et.getId());

        System.out.println("✅ [Mapping] EvidenceType.control = ControlNode (leaf) 자연 매칭");
    }

    // ====================================================================
    // 2. ✂ v15 Phase 5-15a — testAddEvidenceTypeLeafOnly 삭제
    //
    //    5-14f 의 leaf-only invariant 가 v15 5-15a 에서 hybrid 모델 채택으로 제거됨
    //    (ControlNode.addEvidenceType 의 IllegalStateException 가드 제거).
    //    하이브리드 검증은 신규 Phase515aHybridIntegrationTest.testHybridAddEvidenceTypeAllNodes
    //    (Order=1) 가 inverted version 으로 커버.
    //
    //    삭제: 본 케이스 + helper 2개 (catchThrowable, ThrowingRunnable interface)
    // ====================================================================

    // ====================================================================
    // 3. GET /tree 의 leaf 두 카운트 본격 집계 (5-14c 의 0 고정 → 양수)
    // ====================================================================

    @Test
    @Order(3)
    @DisplayName("[GetTree] leaf evidenceTypeCount + pendingReviewCount 본격 집계")
    void testTreeLeafCountsAggregated() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW-counts").build());

        ControlNode root = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("대분류").displayOrder(0).depth(1).build());
        ControlNode leafA = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(root).nodeType(NodeType.control)
                .code("1.1").name("통제 A").displayOrder(0).depth(2).build());
        ControlNode leafB = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(root).nodeType(NodeType.control)
                .code("1.2").name("통제 B").displayOrder(1).depth(2).build());

        // leafA: evidence_types 3개 + pending file 2개
        EvidenceType etA1 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leafA).name("증빙 A1").build());
        evidenceTypeRepository.save(EvidenceType.builder().controlNode(leafA).name("증빙 A2").build());
        evidenceTypeRepository.save(EvidenceType.builder().controlNode(leafA).name("증빙 A3").build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(etA1).fileName("a1.pdf").filePath("/p/a1.pdf").fileSize(1L)
                .version(1).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.pending)
                .build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(etA1).fileName("a2.pdf").filePath("/p/a2.pdf").fileSize(1L)
                .version(2).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.pending)
                .build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(etA1).fileName("a3.pdf").filePath("/p/a3.pdf").fileSize(1L)
                .version(3).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.approved)
                .reviewedBy(adminUser).reviewedAt(LocalDateTime.now())
                .build());

        // leafB: evidence_types 1개 + 0 pending
        evidenceTypeRepository.save(EvidenceType.builder().controlNode(leafB).name("증빙 B").build());

        // GET /tree
        var result = mockMvc.perform(get("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nodes").isArray())
                .andReturn();

        // leafA: evidenceTypeCount=3, pendingReviewCount=2
        // leafB: evidenceTypeCount=1, pendingReviewCount=0
        // root (category): 두 필드 omit
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"evidenceTypeCount\":3");
        assertThat(body).contains("\"pendingReviewCount\":2");
        assertThat(body).contains("\"evidenceTypeCount\":1");

        System.out.println("✅ [GetTree] leaf 두 카운트 본격 집계 (5-14c 의 0 고정 → 양수)");
    }

    // ====================================================================
    // 4. inherit() 의 5단 mixed-depth 트리 재귀 복제
    //    @Transactional 안 씀 — mockMvc 호출의 inner TX 가 commit 되어야 검증 가능
    //    (outer @Transactional 의 rollback 동작이 inherit 결과 데이터를 날림)
    //    검증의 lazy chain 은 id 비교로 회피.
    // ====================================================================

    @Test
    @Order(4)
    @DisplayName("[Inherit] 5단 mixed-depth 트리 source → 동일 트리 target + evidence 보존")
    void testInheritRecursiveTree() throws Exception {
        // source: 5단 mixed-depth 트리 (5-14e FrameworkExportTest 와 같은 패턴)
        Framework source = frameworkRepository.save(Framework.builder().name("source FW").build());

        ControlNode r1 = controlNodeRepository.save(ControlNode.builder()
                .framework(source).parent(null).nodeType(NodeType.category)
                .code("1").name("대분류 A").displayOrder(0).depth(1).build());
        ControlNode l2_1 = controlNodeRepository.save(ControlNode.builder()
                .framework(source).parent(r1).nodeType(NodeType.category)
                .code("1.1").name("중분류").displayOrder(0).depth(2).build());
        ControlNode leaf_3_1 = controlNodeRepository.save(ControlNode.builder()
                .framework(source).parent(l2_1).nodeType(NodeType.control)
                .code("1.1.1").name("leaf A").displayOrder(0).depth(3).build());
        ControlNode l3_2 = controlNodeRepository.save(ControlNode.builder()
                .framework(source).parent(l2_1).nodeType(NodeType.category)
                .code("1.1.2").name("소분류").displayOrder(1).depth(3).build());
        ControlNode l4_1 = controlNodeRepository.save(ControlNode.builder()
                .framework(source).parent(l3_2).nodeType(NodeType.category)
                .code("1.1.2.1").name("세부분류").displayOrder(0).depth(4).build());
        ControlNode leaf_5_1 = controlNodeRepository.save(ControlNode.builder()
                .framework(source).parent(l4_1).nodeType(NodeType.control)
                .code("1.1.2.1.1").name("leaf B (5단)").displayOrder(0).depth(5).build());

        // 담당자 + evidence_type 매달기 (leaf_3_1 에 1개)
        User owner = userRepository.save(User.builder()
                .email("owner@test.com").name("담당자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.developer).permissionEvidence(true).build());
        evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf_3_1).name("정책 문서")
                .ownerUser(owner)
                .dueDate(java.time.LocalDate.of(2026, 6, 30))
                .build());

        // POST /inherit
        var body = objectMapper.writeValueAsString(new FrameworkDto.InheritRequest(
                source.getId(), "target FW (상속)", null));
        mockMvc.perform(post("/api/v1/frameworks/inherit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // target 검증 — 같은 6 노드 트리 (1 + 1.1 + 1.1.1 leaf + 1.1.2 + 1.1.2.1 + 1.1.2.1.1 leaf)
        Framework target = frameworkRepository.findAll().stream()
                .filter(f -> "target FW (상속)".equals(f.getName()))
                .findFirst().orElseThrow();
        List<ControlNode> targetNodes = controlNodeRepository
                .findByFrameworkIdOrderByDepthAscDisplayOrderAsc(target.getId());
        assertThat(targetNodes).hasSize(6);

        // depth=1 root: 코드 "1"
        ControlNode targetRoot = targetNodes.stream()
                .filter(n -> n.getDepth() == 1).findFirst().orElseThrow();
        assertThat(targetRoot.getCode()).isEqualTo("1");
        assertThat(targetRoot.getNodeType()).isEqualTo(NodeType.category);

        // depth=5 leaf: 코드 "1.1.2.1.1"
        ControlNode target5Leaf = targetNodes.stream()
                .filter(n -> n.getDepth() == 5).findFirst().orElseThrow();
        assertThat(target5Leaf.getCode()).isEqualTo("1.1.2.1.1");
        assertThat(target5Leaf.getNodeType()).isEqualTo(NodeType.control);
        assertThat(target5Leaf.getId()).isNotEqualTo(leaf_5_1.getId());  // 새 id

        // evidence_type: target 의 leaf_3_1 (코드 "1.1.1") 에 매달려 있어야 함, owner/due 보존
        ControlNode target3Leaf = targetNodes.stream()
                .filter(n -> "1.1.1".equals(n.getCode())).findFirst().orElseThrow();
        List<EvidenceType> targetEts = evidenceTypeRepository.findByControlNodeId(target3Leaf.getId());
        assertThat(targetEts).hasSize(1);
        assertThat(targetEts.get(0).getName()).isEqualTo("정책 문서");
        // owner 의 .getId() 만 호출 — proxy id 는 lazy 안 함 (FK 그대로)
        assertThat(targetEts.get(0).getOwnerUser().getId()).isEqualTo(owner.getId());
        assertThat(targetEts.get(0).getDueDate()).isEqualTo(java.time.LocalDate.of(2026, 6, 30));

        // source 의 evidence_type 은 그대로 (isolation) — id 비교로 lazy chain 회피
        // 의미: source 와 target 의 EvidenceType 이 별도 객체 (target 이 새로 복제됨)
        List<EvidenceType> sourceEts = evidenceTypeRepository.findByControlNodeId(leaf_3_1.getId());
        assertThat(sourceEts).hasSize(1);
        assertThat(sourceEts.get(0).getId()).isNotEqualTo(targetEts.get(0).getId());
        // source ET 의 control id 는 leaf_3_1.id 자연 매칭 (proxy id, lazy 안 함)
        assertThat(sourceEts.get(0).getControlNode().getId()).isEqualTo(leaf_3_1.getId());

        System.out.println("✅ [Inherit] 5단 mixed-depth 트리 재귀 복제 + evidence 보존");
    }

    // ====================================================================
    // 5. 5-14e impact-summary 자연 정상 카운트 (5-14f 후 dev/test 매칭)
    // ====================================================================

    @Test
    @Order(5)
    @DisplayName("[ImpactSummary 자연화] 5-14e 의 카운트가 5-14f 후 dev/test 정상 응답")
    void testImpactSummaryNaturalized() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW-impact").build());
        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("1").name("leaf").displayOrder(0).depth(1).build());

        // evidence_types 2개 + pending file 1개 + reviewed file 1개
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf).name("ET1").build());
        evidenceTypeRepository.save(EvidenceType.builder().controlNode(leaf).name("ET2").build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et).fileName("p.pdf").filePath("/p/p.pdf").fileSize(1L)
                .version(1).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.pending)
                .build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et).fileName("a.pdf").filePath("/p/a.pdf").fileSize(1L)
                .version(2).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.approved)
                .reviewedBy(adminUser).reviewedAt(LocalDateTime.now())
                .build());

        // GET /controls/{id}/impact-summary — 5-14f 후 dev/test 자연 정상화
        mockMvc.perform(get("/api/v1/control-nodes/{id}/impact-summary", leaf.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ownEvidenceFileCount").value(2))    // pending 1 + approved 1
                .andExpect(jsonPath("$.data.ownReviewCount").value(1));         // approved 만 reviewedAt 있음

        System.out.println("✅ [ImpactSummary 자연화] 5-14e endpoint 가 5-14f 후 dev/test 정상 카운트");
    }
}