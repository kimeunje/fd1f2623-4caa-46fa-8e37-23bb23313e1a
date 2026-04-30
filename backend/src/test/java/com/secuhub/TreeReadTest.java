package com.secuhub;

import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.NodeType;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5-14c — GET /api/v1/frameworks/{id}/tree 검증
 *
 * <h3>테스트 환경</h3>
 * <ul>
 *   <li>H2 + MockMvc + admin token (다른 통합 테스트와 동일 패턴)</li>
 *   <li>{@code @BeforeEach cleanup} — 5-14a/b 학습 패턴 적용 (FK off + DELETE +
 *       본 테스트 전용 admin user 정리)</li>
 *   <li>5-14b 의 IDENTITY sequence reset 은 5-14c 에서 불필요 — explicit id
 *       INSERT 가 없음</li>
 * </ul>
 *
 * <h3>4 케이스</h3>
 * <ol>
 *   <li>{@link #testEmptyFramework} — 빈 Framework (nodes=[], version=0)</li>
 *   <li>{@link #testSimpleTree} — 1 cat + 2 leaf (depth/parentId 정합, leaf 만 두 카운트)</li>
 *   <li>{@link #testFiveLevelMixedDepth} — 5단 mixed-depth (정렬 spec 검증)</li>
 *   <li>{@link #testVersionExposed} — Framework 변경 후 version 자동 증가 정합</li>
 * </ol>
 *
 * <p>Spec: SecuHub_Project_Specification_v14.md §3.3.1.4</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-14c — GET /tree 엔드포인트")
class TreeReadTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;  // v15.2 5-15a 후속-1
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private String adminToken;

    // ========================================================================
    // 5-14a/b 학습 패턴 — 테스트 격리 (FK off + DELETE + 본 테스트 전용 admin)
    // ========================================================================
    @BeforeEach
    void cleanup() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.execute("DELETE FROM evidence_types");
            jdbcTemplate.execute("DELETE FROM control_nodes");
            jdbcTemplate.execute("DELETE FROM controls");
            jdbcTemplate.execute("DELETE FROM frameworks");
            // 본 테스트 전용 admin 만 정리 (다른 테스트 클래스 leftover 보존)
            jdbcTemplate.execute(
                    "DELETE FROM users WHERE email LIKE '%-5-14c@test.com'");
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        entityManager.clear();

        // admin token 발급
        User admin = userRepository.save(User.builder()
                .email("admin-5-14c@test.com")
                .name("관리자")
                .hashedPassword(passwordEncoder.encode("password"))
                .role(UserRole.admin)
                .permissionEvidence(true)
                .build());
        adminToken = jwtTokenProvider.createToken(
                admin.getId(), admin.getEmail(), admin.getRole().name());
    }

    // ========================================================================
    // 1. 빈 Framework — nodes=[], framework.version=0
    // ========================================================================
    @Test
    @Order(1)
    @DisplayName("[Tree] 빈 Framework — nodes=[], framework.version=0")
    void testEmptyFramework() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("Empty FW").build());

        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.framework.id").value(fw.getId()))
                .andExpect(jsonPath("$.data.framework.name").value("Empty FW"))
                .andExpect(jsonPath("$.data.framework.version").value(0))
                .andExpect(jsonPath("$.data.nodes").isArray())
                .andExpect(jsonPath("$.data.nodes.length()").value(0));

        System.out.println("✅ [Tree] 빈 Framework — nodes=[], version=0 정합");
    }

    // ========================================================================
    // 2. 단순 트리 — 1 cat + 2 leaf, leaf 만 두 카운트 노출
    // ========================================================================
    @Test
    @Order(2)
    @DisplayName("[Tree] 단순 트리 — depth/parentId 정합, 모든 노드에 카운트 0 명시 노출 (v15.2 5-15a 후속-1)")
    void testSimpleTree() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("Simple FW").build());

        ControlNode cat = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("관리체계")
                .displayOrder(0).depth(1).build());
        ControlNode leaf1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat).nodeType(NodeType.control)
                .code("1.1").name("경영진의 참여").description("CEO 의사결정 참여 증빙")
                .displayOrder(0).depth(2).build());
        ControlNode leaf2 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat).nodeType(NodeType.control)
                .code("1.2").name("최고책임자 지정")
                .displayOrder(1).depth(2).build());

        // mockMvc 가 별도 TX 로 fresh load — repository.save() 의 자체 TX commit 후라
        // entityManager.flush()/clear() 호출은 불필요 (5-14c 학습: TX 없는 상태에서
        // flush() 는 TransactionRequiredException 던짐).

        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(3))

                // [0] category (depth=1, parent=null)
                .andExpect(jsonPath("$.data.nodes[0].id").value(cat.getId()))
                .andExpect(jsonPath("$.data.nodes[0].nodeType").value("category"))
                .andExpect(jsonPath("$.data.nodes[0].depth").value(1))
                .andExpect(jsonPath("$.data.nodes[0].code").value("1"))
                .andExpect(jsonPath("$.data.nodes[0].name").value("관리체계"))
                // v15.2 5-15a 후속-1 — hybrid 정합: 카테고리도 own 카운트 명시 노출 (Q1=A own only, Q2=A 0 명시).
                // 5-14c~5-14g 시점에는 doesNotExist() (@JsonInclude NON_NULL) 었음.
                .andExpect(jsonPath("$.data.nodes[0].evidenceTypeCount").value(0))
                .andExpect(jsonPath("$.data.nodes[0].collectedCount").value(0))
                .andExpect(jsonPath("$.data.nodes[0].pendingReviewCount").value(0))

                // [1] leaf1 — displayOrder=0
                .andExpect(jsonPath("$.data.nodes[1].id").value(leaf1.getId()))
                .andExpect(jsonPath("$.data.nodes[1].nodeType").value("control"))
                .andExpect(jsonPath("$.data.nodes[1].parentId").value(cat.getId()))
                .andExpect(jsonPath("$.data.nodes[1].depth").value(2))
                .andExpect(jsonPath("$.data.nodes[1].displayOrder").value(0))
                .andExpect(jsonPath("$.data.nodes[1].description").value("CEO 의사결정 참여 증빙"))
                // leaf 는 두 카운트 항상 노출 (5-14c 에서는 0 고정 — TODO 5-14f)
                .andExpect(jsonPath("$.data.nodes[1].evidenceTypeCount").value(0))
                .andExpect(jsonPath("$.data.nodes[1].pendingReviewCount").value(0))

                // [2] leaf2 — displayOrder=1
                .andExpect(jsonPath("$.data.nodes[2].id").value(leaf2.getId()))
                .andExpect(jsonPath("$.data.nodes[2].displayOrder").value(1))
                .andExpect(jsonPath("$.data.nodes[2].evidenceTypeCount").value(0));

        System.out.println("✅ [Tree] 단순 트리 — depth/parentId 정합, 모든 노드에 0 명시 (v15.2 hybrid 정합)");
    }

    // ========================================================================
    // 3. 5단 mixed-depth — 정렬 spec (depth ASC, parent.id ASC NULL FIRST, displayOrder ASC)
    // ========================================================================
    @Test
    @Order(3)
    @DisplayName("[Tree] 5단 mixed-depth — 정렬 spec (depth ASC, parent.id ASC NULL FIRST, displayOrder ASC)")
    void testFiveLevelMixedDepth() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("Deep FW").build());

        // 트리 구조:
        //   d1 (cat)
        //   ├── d2cat (cat,    displayOrder=0)
        //   │   └── d3 (cat)
        //   │       └── d4 (cat)
        //   │           └── d5 (control, leaf — depth=5)
        //   └── d2leaf (control, leaf, displayOrder=1)
        ControlNode d1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("d1").displayOrder(0).depth(1).build());

        ControlNode d2cat = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(d1).nodeType(NodeType.category)
                .code("1.1").name("d2cat").displayOrder(0).depth(2).build());
        ControlNode d2leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(d1).nodeType(NodeType.control)
                .code("1.2").name("d2leaf").displayOrder(1).depth(2).build());

        ControlNode d3 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(d2cat).nodeType(NodeType.category)
                .code("1.1.1").name("d3").displayOrder(0).depth(3).build());
        ControlNode d4 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(d3).nodeType(NodeType.category)
                .code("1.1.1.1").name("d4").displayOrder(0).depth(4).build());
        ControlNode d5 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(d4).nodeType(NodeType.control)
                .code("1.1.1.1.1").name("d5").displayOrder(0).depth(5).build());

        // mockMvc 가 별도 TX 로 fresh load — flush/clear 불필요 (testSimpleTree 와 동일)

        // 기대 정렬:
        //   [0] d1     (depth=1, parent=null)
        //   [1] d2cat  (depth=2, parent=d1, displayOrder=0)
        //   [2] d2leaf (depth=2, parent=d1, displayOrder=1)
        //   [3] d3     (depth=3, parent=d2cat)
        //   [4] d4     (depth=4, parent=d3)
        //   [5] d5     (depth=5, parent=d4) — 유일한 leaf
        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(6))

                .andExpect(jsonPath("$.data.nodes[0].id").value(d1.getId()))
                .andExpect(jsonPath("$.data.nodes[0].depth").value(1))

                .andExpect(jsonPath("$.data.nodes[1].id").value(d2cat.getId()))
                .andExpect(jsonPath("$.data.nodes[1].depth").value(2))
                .andExpect(jsonPath("$.data.nodes[1].displayOrder").value(0))

                .andExpect(jsonPath("$.data.nodes[2].id").value(d2leaf.getId()))
                .andExpect(jsonPath("$.data.nodes[2].depth").value(2))
                .andExpect(jsonPath("$.data.nodes[2].displayOrder").value(1))
                // 유일한 d2 leaf — 두 카운트 노출
                .andExpect(jsonPath("$.data.nodes[2].evidenceTypeCount").value(0))

                .andExpect(jsonPath("$.data.nodes[3].id").value(d3.getId()))
                .andExpect(jsonPath("$.data.nodes[3].depth").value(3))

                .andExpect(jsonPath("$.data.nodes[4].id").value(d4.getId()))
                .andExpect(jsonPath("$.data.nodes[4].depth").value(4))

                .andExpect(jsonPath("$.data.nodes[5].id").value(d5.getId()))
                .andExpect(jsonPath("$.data.nodes[5].depth").value(5))
                .andExpect(jsonPath("$.data.nodes[5].nodeType").value("control"))
                // depth=5 leaf — 두 카운트 노출
                .andExpect(jsonPath("$.data.nodes[5].evidenceTypeCount").value(0))
                .andExpect(jsonPath("$.data.nodes[5].pendingReviewCount").value(0));

        System.out.println("✅ [Tree] 5단 mixed-depth — 정렬 spec 정합 (depth, parent.id NULL FIRST, displayOrder)");
    }

    // ========================================================================
    // 4. version 노출 — Framework 만들 때 0, update 후 1
    // ========================================================================
    @Test
    @Order(4)
    @DisplayName("[Tree] version 노출 — Framework 만들 때 0, name 변경 후 1")
    void testVersionExposed() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("Version FW").build());
        Long fwId = fw.getId();

        // 첫 GET — version=0 (새로 생성된 Framework 의 @Version 기본값)
        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", fwId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.framework.name").value("Version FW"))
                .andExpect(jsonPath("$.data.framework.version").value(0));

        // Framework name 변경 + flush — JPA @Version 자동 증가 (UPDATE 시점)
        // saveAndFlush 가 자체 TX 안에서 commit, TX 종료 시 1차 캐시 자동 정리되므로
        // entityManager.clear() 불필요.
        Framework managed = frameworkRepository.findById(fwId).orElseThrow();
        managed.update("Version FW (수정됨)", null);
        frameworkRepository.saveAndFlush(managed);

        // 두 번째 GET — version=1
        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", fwId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.framework.name").value("Version FW (수정됨)"))
                .andExpect(jsonPath("$.data.framework.version").value(1));

        System.out.println("✅ [Tree] version 노출 — 0 → 1, name 변경 정합");
    }

    // ========================================================================
    // 5. v15.2 5-15a 후속-1 — hybrid 노드 own 카운트 노출
    //    카테고리에 자체 evidence_types 매달림 + 자식 leaf 도 자체 evidence_types
    //    각자 own 카운트 (Q1=A own only, 자손 합산 X).
    //    카테고리 카운트도 응답에 명시 (Q2=A Map miss 시 0, NON_NULL 가드 폐기).
    // ========================================================================
    @Test
    @Order(5)
    @DisplayName("[Tree] hybrid 노드 — 카테고리/leaf 모두 own evidenceTypeCount 노출 (v15.2 5-15a 후속-1)")
    void testHybridNodeOwnCountsExposed() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("Hybrid FW").build());

        // 카테고리 (hybrid: 자체 evidence_types 1개 매달림 + 자식 1개)
        ControlNode hybridCat = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("범위 설정 (자체 증빙 보유)")
                .displayOrder(0).depth(1).build());

        // 자식 leaf (자체 evidence_types 2개 매달림)
        ControlNode childLeaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(hybridCat).nodeType(NodeType.control)
                .code("1.1").name("범위 문서화")
                .displayOrder(0).depth(2).build());

        // 카테고리 자체에 evidence_types 1개 (v15.0 hybrid backend 가 leaf-only 가드 제거)
        evidenceTypeRepository.save(EvidenceType.builder()
                .control(hybridCat).name("범위 정의서 (카테고리 자체)").build());

        // leaf 에 evidence_types 2개
        evidenceTypeRepository.save(EvidenceType.builder()
                .control(childLeaf).name("범위 문서 v1").build());
        evidenceTypeRepository.save(EvidenceType.builder()
                .control(childLeaf).name("범위 문서 v2").build());

        // 응답 검증:
        //   nodes[0] = hybridCat (depth=1)   — own evidenceTypeCount=1 (자체만, 자손 평탄화 X)
        //   nodes[1] = childLeaf (depth=2)   — own evidenceTypeCount=2
        // 두 노드 모두 collectedCount=0, pendingReviewCount=0 명시 (Q2=A NULL 없음).
        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nodes.length()").value(2))

                // [0] hybridCat — 카테고리도 own 카운트 노출 (5-14g 까지는 omit)
                .andExpect(jsonPath("$.data.nodes[0].nodeType").value("category"))
                .andExpect(jsonPath("$.data.nodes[0].evidenceTypeCount").value(1))
                .andExpect(jsonPath("$.data.nodes[0].collectedCount").value(0))
                .andExpect(jsonPath("$.data.nodes[0].pendingReviewCount").value(0))

                // [1] childLeaf — leaf own 카운트 (5-14f 부터 본격 집계, 정합 보존)
                .andExpect(jsonPath("$.data.nodes[1].nodeType").value("control"))
                .andExpect(jsonPath("$.data.nodes[1].evidenceTypeCount").value(2))
                .andExpect(jsonPath("$.data.nodes[1].collectedCount").value(0))
                .andExpect(jsonPath("$.data.nodes[1].pendingReviewCount").value(0));

        System.out.println("✅ [Tree] hybrid 카테고리 own 카운트 노출 — cat.evidenceTypeCount=1 (자체) / leaf=2");
    }
}