package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 5-15a — Hybrid 노드 모델 통합 검증 (6 케이스).
 *
 * <p>spec §3.3.1.9 (v14.9 채택) 의 hybrid 모델 도입에 따른 backend 변경 6 항 중 본
 * phase 의 핵심 3항 검증:</p>
 * <ol>
 *   <li>{@link ControlNode#addEvidenceType} 의 leaf-only {@code IllegalStateException}
 *       가드 제거 — 모든 노드 (category / leaf / hybrid) 가 evidence_types 매달림 가능</li>
 *   <li>{@link com.secuhub.domain.evidence.service.TreeUpdateService} 의
 *       {@code parent_must_be_category} 검증 (validateCreated number/tempId 분기 +
 *       validateMoved) 3 occurrence 제거 — leaf 아래에 자식 노드 추가 / 이동 가능</li>
 *   <li>{@link com.secuhub.domain.evidence.dto.ImpactSummaryDto} 의 own / descendant
 *       6 신규 필드 + legacy alias 3 필드 — hybrid 노드 코드 변경 시 자체 vs 자손 영향
 *       분리 카운트</li>
 * </ol>
 *
 * <h3>검증 케이스</h3>
 * <ol>
 *   <li>[Hybrid 매달림] category 노드도 evidence_type 매달림 가능 — 5-14f 의 leaf-only
 *       invariant 가 v15 에서 제거됨 (Phase514fIntegrationTest.testAddEvidenceTypeLeafOnly
 *       의 inverted 후속). leaf / hybrid 모두 정상 작동.</li>
 *   <li>[PATCH /tree — number] 기존 leaf 아래에 자식 노드 생성 (parentId 가 number).
 *       이전 422 {@code parent_must_be_category} → 200.</li>
 *   <li>[PATCH /tree — tempId] 같은 요청 안에서 leaf + 자식 함께 생성 (parentId 가
 *       tempId string). validateCreated 의 두 번째 분기 검증.</li>
 *   <li>[PATCH /tree — move] 노드 이동 시 newParentId 가 leaf. 이전 422 → 200.</li>
 *   <li>[ImpactSummary hybrid] 자체 + 자손 모두 보유한 hybrid 노드의 own / descendant
 *       카운트 분리 검증. legacy alias = own 일치.</li>
 *   <li>[ImpactSummary 단일] 자손 0 인 노드의 descendant 모두 0 + legacy alias = own 일치.</li>
 * </ol>
 *
 * <h3>전제</h3>
 * <ul>
 *   <li>Phase514fIntegrationTest.testAddEvidenceTypeLeafOnly 삭제됨 (invariant 제거)</li>
 *   <li>TreeUpdateTest.testMoveCatUnderLeaf 도 invertion 또는 삭제 필요 (별도 처리)</li>
 *   <li>본 클래스의 테스트 user email pattern: {@code %-5-15a@test.com} — leftover 격리</li>
 * </ul>
 *
 * <h3>5-14d / 5-14f 학습 재적용</h3>
 * <ul>
 *   <li>mockMvc 사용 테스트는 {@code @Transactional} 미사용 (controller TX commit 보존)
 *       — Order=2/3/4/5/6</li>
 *   <li>mockMvc 안 쓰는 테스트는 메서드 레벨 {@code @Transactional} 추가로 lazy proxy
 *       hydrate 보장 — Order=1</li>
 *   <li>{@code Map.of(...)} 가 null 값 거부하므로 created node 빌더는 LinkedHashMap 사용</li>
 *   <li>cleanup 은 본 클래스 user email pattern 만 매칭 (다른 테스트 leftover 보존)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-15a — Hybrid 노드 모델 (mutex 폐기, parent_must_be_category 제거, ImpactSummary own/desc)")
class Phase515aHybridIntegrationTest {

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
    @Autowired private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private String adminToken;
    private User adminUser;

    @BeforeEach
    void setUp() {
        // FK 역순 cleanup — REFERENTIAL_INTEGRITY off 로 한 번에 처리
        // (5-14d TreeUpdateTest 패턴 재활용)
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.execute("DELETE FROM evidence_files");
            jdbcTemplate.execute("DELETE FROM collection_jobs");
            jdbcTemplate.execute("DELETE FROM evidence_types");
            jdbcTemplate.execute("DELETE FROM control_nodes");
            jdbcTemplate.execute("DELETE FROM controls");
            jdbcTemplate.execute("DELETE FROM frameworks");
            // 본 클래스 전용 user 만 정리 (다른 테스트 leftover 보존)
            jdbcTemplate.execute(
                    "DELETE FROM users WHERE email LIKE '%-5-15a@test.com'");
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        entityManager.clear();

        adminUser = userRepository.save(User.builder()
                .email("admin-5-15a@test.com")
                .name("관리자")
                .hashedPassword(passwordEncoder.encode("password"))
                .role(UserRole.admin)
                .permissionEvidence(true)
                .permissionVuln(true)
                .build());
        adminToken = jwtTokenProvider.createToken(
                adminUser.getId(), adminUser.getEmail(), adminUser.getRole().name());
    }

    // ====================================================================
    // 1. [Hybrid 매달림] category 노드도 evidence_type 매달림 가능
    //
    //    5-14f 의 leaf-only invariant (Phase514fIntegrationTest.testAddEvidenceTypeLeafOnly)
    //    가 v15 5-15a 에서 제거됨. category / leaf 모두 addEvidenceType 정상 작동.
    //    @Transactional — addEvidenceType 후 reload 시 lazy hydrate 보장.
    // ====================================================================

    @Test
    @Order(1)
    @Transactional
    @DisplayName("[Hybrid 매달림] category + leaf 모두 evidence_type 매달림 가능 (IllegalStateException 안 던짐)")
    void testHybridAddEvidenceTypeAllNodes() {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW-hybrid").build());

        ControlNode category = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("hybrid 분류").displayOrder(0).depth(1).build());
        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(category).nodeType(NodeType.control)
                .code("1.1").name("leaf 통제").displayOrder(0).depth(2).build());

        // category 호출 — v15 hybrid: IllegalStateException 안 던짐 (이전 5-14f 에서는 던짐)
        EvidenceType etCategory = EvidenceType.builder().name("hybrid category 증빙").build();
        category.addEvidenceType(etCategory);
        evidenceTypeRepository.save(etCategory);

        // leaf 호출 — 5-14f 호환 그대로 정상 작동
        EvidenceType etLeaf = EvidenceType.builder().name("leaf 증빙").build();
        leaf.addEvidenceType(etLeaf);
        evidenceTypeRepository.save(etLeaf);

        entityManager.flush();
        entityManager.clear();

        // category 의 evidence_types 1개 + et.control 매핑 정합
        EvidenceType reloadedCat = evidenceTypeRepository.findById(etCategory.getId()).orElseThrow();
        assertThat(reloadedCat.getControl().getId()).isEqualTo(category.getId());
        assertThat(reloadedCat.getControl().getNodeType()).isEqualTo(NodeType.category);

        // leaf 의 evidence_types 1개
        EvidenceType reloadedLeaf = evidenceTypeRepository.findById(etLeaf.getId()).orElseThrow();
        assertThat(reloadedLeaf.getControl().getId()).isEqualTo(leaf.getId());
        assertThat(reloadedLeaf.getControl().getNodeType()).isEqualTo(NodeType.control);

        // findByControlId 가 두 노드 모두 자연 매칭 (FK 통한 컬럼 매칭, nodeType 무관)
        assertThat(evidenceTypeRepository.findByControlId(category.getId())).hasSize(1);
        assertThat(evidenceTypeRepository.findByControlId(leaf.getId())).hasSize(1);

        System.out.println("✅ [Hybrid 매달림] category + leaf 모두 정상 매달림, IllegalStateException 안 던짐");
    }

    // ====================================================================
    // 2. [PATCH /tree — number] leaf 아래에 자식 노드 생성 OK
    //
    //    이전 5-14d 에서는 422 (parent_must_be_category). v15 5-15a: 200.
    //    validateCreated 의 number 분기 검증.
    // ====================================================================

    @Test
    @Order(2)
    @DisplayName("[PATCH /tree number] leaf 아래에 자식 노드 생성 — 이전 422 → 이제 200")
    void testCreateChildUnderLeafNumberId() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW-create-num").build());

        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("1").name("기존 leaf").displayOrder(0).depth(1).build());

        // PATCH /tree — leaf 아래 자식 1개 생성 (parentId number)
        Map<String, Object> body = patchBody(0L,
                List.of(createdNode("A", leaf.getId(), "category", "1.1", "신규 자식", 2, 0)),
                List.of(), List.of(), List.of()
        );

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.mappings.nodes.length()").value(1));

        // DB 검증 — leaf 아래에 자식 1개 존재, depth=2
        List<ControlNode> children =
                controlNodeRepository.findByParentIdOrderByDisplayOrderAsc(leaf.getId());
        assertThat(children).hasSize(1);
        assertThat(children.get(0).getCode()).isEqualTo("1.1");
        assertThat(children.get(0).getDepth()).isEqualTo(2);

        System.out.println("✅ [PATCH /tree number] leaf 아래 자식 생성 — parent_must_be_category 제거 확인");
    }

    // ====================================================================
    // 3. [PATCH /tree — tempId] 같은 요청 안에서 leaf + 자식 함께 생성
    //
    //    validateCreated 의 두 번째 분기 (parentId 가 string tempId, 같은 요청 안의
    //    이전 created 노드 참조). 이전 5-14d 에서는 같은 요청 안 leaf 의 자식 추가도
    //    parent_must_be_category 422.
    // ====================================================================

    @Test
    @Order(3)
    @DisplayName("[PATCH /tree tempId] 같은 요청 안에서 leaf + 자식 함께 생성")
    void testCreateLeafAndChildSameRequestTempId() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW-create-temp").build());

        // 같은 요청 안: leaf (tempId=A, parent=null) + 자식 (tempId=B, parent=A)
        Map<String, Object> body = patchBody(0L,
                List.of(
                        createdNode("A", null, "control", "1", "신규 leaf", 1, 0),
                        createdNode("B", "A", "category", "1.1", "leaf 의 자식", 2, 0)
                ),
                List.of(), List.of(), List.of()
        );

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.mappings.nodes.length()").value(2));

        // DB 검증 — 두 노드 + 부모-자식 관계 정합
        List<ControlNode> all =
                controlNodeRepository.findByFrameworkIdOrderByDepthAscDisplayOrderAsc(fw.getId());
        assertThat(all).hasSize(2);

        ControlNode parentLeaf = all.stream().filter(n -> "1".equals(n.getCode())).findFirst().orElseThrow();
        ControlNode child = all.stream().filter(n -> "1.1".equals(n.getCode())).findFirst().orElseThrow();
        assertThat(parentLeaf.getNodeType()).isEqualTo(NodeType.control);
        assertThat(child.getParent().getId()).isEqualTo(parentLeaf.getId());
        assertThat(child.getDepth()).isEqualTo(2);

        System.out.println("✅ [PATCH /tree tempId] 같은 요청 안 leaf + 자식 동시 생성 — tempId 분기 정합");
    }

    // ====================================================================
    // 4. [PATCH /tree — move] 노드 이동 시 newParentId 가 leaf
    //
    //    이전 5-14d (TreeUpdateTest.testMoveCatUnderLeaf) 에서는 422 (parent_must_be_category).
    //    v15 5-15a: 200. validateMoved 분기 검증.
    // ====================================================================

    @Test
    @Order(4)
    @DisplayName("[PATCH /tree move] leaf 아래로 노드 이동 — 이전 422 → 이제 200")
    void testMoveNodeUnderLeaf() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW-move").build());

        ControlNode leaf1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("1").name("leaf1 (이동 대상의 새 부모)").displayOrder(0).depth(1).build());
        ControlNode leaf2 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("2").name("leaf2 (이동될 노드)").displayOrder(1).depth(1).build());

        // leaf2 를 leaf1 아래로 이동 (newParentId 가 leaf)
        Map<String, Object> body = patchBody(0L,
                List.of(), List.of(),
                List.of(Map.of("id", leaf2.getId(),
                        "newParentId", leaf1.getId(),
                        "newDisplayOrder", 0,
                        "newDepth", 2)),
                List.of()
        );

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ControlNode reloaded = controlNodeRepository.findById(leaf2.getId()).orElseThrow();
        assertThat(reloaded.getParent().getId()).isEqualTo(leaf1.getId());
        assertThat(reloaded.getDepth()).isEqualTo(2);

        System.out.println("✅ [PATCH /tree move] leaf 아래로 이동 — parent_must_be_category 제거 확인");
    }

    // ====================================================================
    // 5. [ImpactSummary hybrid] 자체 + 자손 동시 보유 노드의 own / descendant 분리
    //
    //    트리:
    //      parent (category, 자체 ET 2개 + 각 file 1개 = own 2 file)
    //        └─ child (leaf, ET 1개 + file 1개 = desc 1 file)
    //
    //    GET /api/v1/controls/{parent.id}/impact-summary
    //    응답: ownEvidenceFileCount=2, descendantEvidenceFileCount=1
    //          evidenceFileCount=2 (legacy = own, BC 보존)
    //          reviewCount=0 (file 모두 auto_approved → reviewedAt NULL)
    // ====================================================================

    @Test
    @Order(5)
    @DisplayName("[ImpactSummary hybrid] hybrid 노드 own=2 / descendant=1 분리, legacy=own")
    void testImpactSummaryHybridOwnVsDescendant() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW-impact-hybrid").build());

        // parent — category nodeType 이지만 hybrid (자체 + 자식 동시 보유)
        ControlNode parent = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("hybrid parent").displayOrder(0).depth(1).build());
        // child — leaf
        ControlNode child = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(parent).nodeType(NodeType.control)
                .code("1.1").name("자손 leaf").displayOrder(0).depth(2).build());

        // parent 자체에 evidence_types 2개 + 각 file 1개
        EvidenceType pEt1 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(parent).name("parent ET1").build());
        EvidenceType pEt2 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(parent).name("parent ET2").build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(pEt1).fileName("p1.pdf").filePath("/p/p1.pdf").fileSize(1L)
                .version(1).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.auto_approved)
                .build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(pEt2).fileName("p2.pdf").filePath("/p/p2.pdf").fileSize(1L)
                .version(1).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.auto_approved)
                .build());

        // child 에 evidence_types 1개 + file 1개 (명시 검토됨 = reviewedAt NOT NULL)
        EvidenceType cEt = evidenceTypeRepository.save(EvidenceType.builder()
                .control(child).name("child ET").build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(cEt).fileName("c.pdf").filePath("/c/c.pdf").fileSize(1L)
                .version(1).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.approved)
                .reviewedBy(adminUser).reviewedAt(LocalDateTime.now())
                .build());

        // parent 의 impact-summary
        mockMvc.perform(get("/api/v1/controls/{id}/impact-summary", parent.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // own
                .andExpect(jsonPath("$.data.ownEvidenceFileCount").value(2))
                .andExpect(jsonPath("$.data.ownReviewCount").value(0))   // 둘 다 auto_approved
                // descendant
                .andExpect(jsonPath("$.data.descendantEvidenceFileCount").value(1))
                .andExpect(jsonPath("$.data.descendantReviewCount").value(1))   // child file 명시 검토됨
                // legacy alias = own (5-14h FE BC)
                .andExpect(jsonPath("$.data.evidenceFileCount").value(2))
                .andExpect(jsonPath("$.data.reviewCount").value(0));

        System.out.println("✅ [ImpactSummary hybrid] own=2/desc=1 분리, legacy=own (BC 보존)");
    }

    // ====================================================================
    // 6. [ImpactSummary 단일] 자손 0 노드의 descendant 모두 0 + legacy = own
    //
    //    단일 leaf, 자손 0, ET 1개 + file 1개. 5-14e legacy 동작과 동일.
    // ====================================================================

    @Test
    @Order(6)
    @DisplayName("[ImpactSummary 단일] 자손 0 노드 — descendant 모두 0, legacy = own")
    void testImpactSummarySoloLeafNoDescendants() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW-impact-solo").build());

        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("1").name("자손 0 leaf").displayOrder(0).depth(1).build());

        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(leaf).name("ET").build());
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et).fileName("a.pdf").filePath("/p/a.pdf").fileSize(1L)
                .version(1).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.auto_approved)
                .build());

        mockMvc.perform(get("/api/v1/controls/{id}/impact-summary", leaf.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // own
                .andExpect(jsonPath("$.data.ownEvidenceFileCount").value(1))
                .andExpect(jsonPath("$.data.ownJobCount").value(0))
                .andExpect(jsonPath("$.data.ownReviewCount").value(0))
                // descendant 모두 0 (자손 list 빈 → IN 절 호출 회피)
                .andExpect(jsonPath("$.data.descendantEvidenceFileCount").value(0))
                .andExpect(jsonPath("$.data.descendantJobCount").value(0))
                .andExpect(jsonPath("$.data.descendantReviewCount").value(0))
                // legacy alias = own
                .andExpect(jsonPath("$.data.evidenceFileCount").value(1))
                .andExpect(jsonPath("$.data.jobCount").value(0))
                .andExpect(jsonPath("$.data.reviewCount").value(0));

        System.out.println("✅ [ImpactSummary 단일] 자손 0, descendant 모두 0, legacy = own 일치");
    }

    // ====================================================================
    // helper — PATCH /tree 페이로드 생성 (5-14d TreeUpdateTest 패턴 재활용)
    // ====================================================================

    private Map<String, Object> patchBody(Long expectedVersion,
                                            List<Map<String, Object>> created,
                                            List<Map<String, Object>> updated,
                                            List<Map<String, Object>> moved,
                                            List<Map<String, Object>> deleted) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        if (created != null) nodes.put("created", created);
        if (updated != null) nodes.put("updated", updated);
        if (moved != null) nodes.put("moved", moved);
        if (deleted != null) nodes.put("deleted", deleted);
        return Map.of(
                "expectedVersion", expectedVersion,
                "changes", Map.of("nodes", nodes)
        );
    }

    /**
     * created 노드 빌더. parentId 가 null 가능하므로 LinkedHashMap 사용
     * (Map.of(...) 는 null 값 거부).
     *
     * @param tempId 같은 PATCH 안 의존성 표현용 임시 식별자
     * @param parentId 기존 노드 id (Long) | 같은 요청 tempId (String) | framework 직속 (null)
     * @param nodeType "category" 또는 "control"
     */
    private Map<String, Object> createdNode(String tempId, Object parentId, String nodeType,
                                              String code, String name, int depth, int displayOrder) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("tempId", tempId);
        n.put("parentId", parentId);   // null 허용
        n.put("nodeType", nodeType);
        n.put("code", code);
        n.put("name", name);
        n.put("depth", depth);
        n.put("displayOrder", displayOrder);
        return n;
    }
}