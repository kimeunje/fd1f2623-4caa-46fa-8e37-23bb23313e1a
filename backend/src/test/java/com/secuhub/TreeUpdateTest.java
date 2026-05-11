package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.entity.CollectionJob;
import com.secuhub.domain.evidence.entity.CollectionMethod;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.EvidenceFile;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.JobType;
import com.secuhub.domain.evidence.entity.NodeType;
import com.secuhub.domain.evidence.entity.ReviewStatus;
import com.secuhub.domain.evidence.repository.CollectionJobRepository;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5-14d — PATCH /api/v1/frameworks/{id}/tree 검증
 *
 * <h3>v15 Phase 5-15a — testLeafParentBlocked 삭제</h3>
 * <p>5-14d 의 {@code parent_must_be_category} 검증이 v15 5-15a 의 hybrid 모델 채택으로
 * 제거됨 → 본 케이스 (Order=8) 도 무효화 → 삭제. v15 hybrid 검증은 신규 클래스
 * {@code Phase515aHybridIntegrationTest.testMoveNodeUnderLeaf} (Order=4) 가 inverted
 * version 으로 커버 (이전 422 → 이제 200 기대).</p>
 *
 * <h3>v18.3 — 회귀 보호 3 케이스 추가 (Order=9/10/11)</h3>
 * <p>v18.2 silent skip 시나리오 의 정공 fix (V_v18_3 Flyway + entity
 * {@code @OnDelete(CASCADE)} 3 추가) 회귀 차단. testCascadingDelete (Order=4, 단순
 * graph) 의 한계 — evidence_types / collection_jobs / evidence_files 매달림 부재 —
 * 를 본 3 케이스가 확장 커버. {@code @BeforeEach cleanup} 에 evidence_files /
 * collection_jobs DELETE 추가.</p>
 *
 * <h3>10 케이스 (v18.3 시점: 7 + 3, spec §3.3.1.4 의 12 검증 규칙 중 10개 + 응답 shape + cascade chain)</h3>
 * <ol>
 *   <li>{@link #testHappyPath} — created 2 + updated 1 + moved 1 + deleted 1 → 200, version+1</li>
 *   <li>{@link #testVersionMismatch_409} — expectedVersion 불일치 → 409 currentVersion 노출</li>
 *   <li>{@link #testValidationFailed_422_multipleDetails} — code 중복 + max_depth → 422 details 2개</li>
 *   <li>{@link #testCascadingDelete} — category 삭제 → 자손 leaf 자동 삭제 (DB CASCADE, 단순 graph)</li>
 *   <li>{@link #testTempIdMapping} — created 의 parentId="tempA" → 위상 정렬 INSERT 후 mappings 정합</li>
 *   <li>{@link #testMoveAction} — parent / displayOrder / depth 갱신 정합</li>
 *   <li>{@link #testCycleDetected} — moved.newParentId 가 자기 자손 → 422</li>
 *   <li>(✂ Order=8) <s>{@code testLeafParentBlocked}</s> — v15 5-15a 에서 삭제됨.
 *       hybrid 모델: leaf 아래로 이동 가능 (Phase515aHybridIntegrationTest.testMoveNodeUnderLeaf 가 inverted 커버)</li>
 *   <li>{@link #testCascadingDeleteWithEvidenceTypes} — v18.3: evidence_types 매달린 cascade</li>
 *   <li>{@link #testCascadingDeleteWithCollectionJobs} — v18.3: collection_jobs 까지 cascade</li>
 *   <li>{@link #testCascadingDeleteWithEvidenceFiles} — v18.3: evidence_files N+M 그래프 (v18.2 재현 시나리오)</li>
 * </ol>
 *
 * <p>5-14b/c 학습 패턴 — FK off + DELETE + 본 테스트 전용 admin 정리.
 * 5-14c 학습: mockMvc 통합 테스트라 entityManager.flush() 호출 금지.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-14d — PATCH /tree 엔드포인트")
class TreeUpdateTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    // v18.3 — cascade chain 회귀 보호 케이스용 Repository
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private CollectionJobRepository collectionJobRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private String adminToken;

    @BeforeEach
    void cleanup() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            // v18.3 — evidence_files / collection_jobs cleanup 추가 (회귀 케이스 정합)
            jdbcTemplate.execute("DELETE FROM evidence_files");
            jdbcTemplate.execute("DELETE FROM collection_jobs");
            jdbcTemplate.execute("DELETE FROM evidence_types");
            jdbcTemplate.execute("DELETE FROM control_nodes");
            jdbcTemplate.execute("DELETE FROM frameworks");
            jdbcTemplate.execute(
                    "DELETE FROM users WHERE email LIKE '%-5-14d@test.com'");
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        entityManager.clear();

        User admin = userRepository.save(User.builder()
                .email("admin-5-14d@test.com")
                .name("관리자")
                .hashedPassword(passwordEncoder.encode("password"))
                .role(UserRole.admin)
                .permissionEvidence(true)
                .build());
        adminToken = jwtTokenProvider.createToken(
                admin.getId(), admin.getEmail(), admin.getRole().name());
    }

    // ========================================================================
    // helper — PATCH 페이로드 생성
    // ========================================================================
    private Map<String, Object> patchBody(Long expectedVersion,
                                            List<Map<String, Object>> created,
                                            List<Map<String, Object>> updated,
                                            List<Map<String, Object>> moved,
                                            List<Map<String, Object>> deleted) {
        Map<String, Object> nodes = new java.util.LinkedHashMap<>();
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
     * created 노드 빌더 — Map.of(...) 가 null 값 허용 안 하므로 LinkedHashMap 사용.
     * parentId 가 null 인 depth=1 노드 표현용 ({@code Map.of("parentId", null, ...)}
     * 는 NPE 던짐).
     */
    private Map<String, Object> createdNode(String tempId, Object parentId, String nodeType,
                                              String code, String name,
                                              int displayOrder, int depth) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("tempId", tempId);
        m.put("parentId", parentId);     // null 허용
        m.put("nodeType", nodeType);
        m.put("code", code);
        m.put("name", name);
        m.put("displayOrder", displayOrder);
        m.put("depth", depth);
        return m;
    }

    // ========================================================================
    // 1. happy path
    // ========================================================================
    @Test
    @Order(1)
    @DisplayName("[Patch] happy path — created 2 + updated 1 + moved 1 + deleted 1 → 200, version+1")
    void testHappyPath() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("Happy FW").build());

        ControlNode cat1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("cat1").displayOrder(0).depth(1).build());
        ControlNode leaf1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat1).nodeType(NodeType.control)
                .code("1.1").name("leaf1").displayOrder(0).depth(2).build());
        ControlNode leaf2 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat1).nodeType(NodeType.control)
                .code("1.2").name("leaf2").displayOrder(1).depth(2).build());
        ControlNode leafToDelete = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat1).nodeType(NodeType.control)
                .code("1.3").name("toDelete").displayOrder(2).depth(2).build());

        // Framework 의 초기 version=0

        Map<String, Object> body = patchBody(0L,
                // created — 새 cat (depth=1) + 새 leaf (그 cat 의 자식)
                List.of(
                        createdNode("tempA", null, "category", "2", "newCat", 1, 1),
                        createdNode("tempB", "tempA", "control", "2.1", "newLeaf", 0, 2)
                ),
                // updated — leaf1 의 name 만 변경
                List.of(Map.of("id", leaf1.getId(), "name", "leaf1 (수정됨)")),
                // moved — leaf2 의 displayOrder 만 변경 (같은 부모 안)
                List.of(Map.of("id", leaf2.getId(),
                        "newParentId", cat1.getId(),
                        "newDisplayOrder", 5,
                        "newDepth", 2)),
                // deleted
                List.of(Map.of("id", leafToDelete.getId()))
        );

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.mappings.nodes").isArray())
                .andExpect(jsonPath("$.data.mappings.nodes.length()").value(2));

        // DB 검증
        long totalNodes = controlNodeRepository.count();
        assertThat(totalNodes).isEqualTo(5L);  // 4 - 1 (deleted) + 2 (created)

        // toDelete 사라짐
        assertThat(controlNodeRepository.findById(leafToDelete.getId())).isEmpty();

        // leaf1 name 수정
        ControlNode reloadedLeaf1 = controlNodeRepository.findById(leaf1.getId()).orElseThrow();
        assertThat(reloadedLeaf1.getName()).isEqualTo("leaf1 (수정됨)");

        // leaf2 displayOrder 변경
        ControlNode reloadedLeaf2 = controlNodeRepository.findById(leaf2.getId()).orElseThrow();
        assertThat(reloadedLeaf2.getDisplayOrder()).isEqualTo(5);

        System.out.println("✅ [Patch] happy path — version 0→1, mappings 2, 4 액션 모두 적용");
    }

    // ========================================================================
    // 2. 409 충돌 — expectedVersion 불일치
    // ========================================================================
    @Test
    @Order(2)
    @DisplayName("[Patch] 409 — expectedVersion 불일치 시 currentVersion 노출")
    void testVersionMismatch_409() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("VM FW").build());
        // current version = 0

        Map<String, Object> body = patchBody(99L, List.of(), List.of(), List.of(), List.of());

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("version_mismatch"))
                .andExpect(jsonPath("$.currentVersion").value(0));

        System.out.println("✅ [Patch] 409 — version_mismatch + currentVersion 정합");
    }

    // ========================================================================
    // 3. 422 — 다중 검증 실패
    // ========================================================================
    @Test
    @Order(3)
    @DisplayName("[Patch] 422 — code 중복 + max_depth 동시 위반, details 2개 노출")
    void testValidationFailed_422_multipleDetails() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("VF FW").build());

        // 같은 부모 안 같은 code 두 개 → duplicate
        // 하나는 depth=11 (MAX_DEPTH=10 초과)
        Map<String, Object> body = patchBody(0L,
                List.of(
                        createdNode("tempA", null, "category", "DUP", "n1", 0, 1),
                        createdNode("tempB", null, "category", "DUP", "n2", 1, 1),  // 같은 부모 안 같은 code
                        createdNode("tempC", null, "category", "X",   "n3", 2, 11)  // max_depth 초과
                ),
                List.of(), List.of(), List.of()
        );

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.details").isArray())
                // depth_mismatch + max_depth_exceeded + duplicate_code 등 — 최소 2개 이상
                .andExpect(jsonPath("$.details.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.details[*].code").value(
                        org.hamcrest.Matchers.hasItem("duplicate_code")))
                .andExpect(jsonPath("$.details[*].code").value(
                        org.hamcrest.Matchers.hasItem("max_depth_exceeded")));

        // 전체 롤백 — DB 에 노드 없음
        long count = controlNodeRepository.count();
        assertThat(count).isEqualTo(0L);

        System.out.println("✅ [Patch] 422 — duplicate_code + max_depth_exceeded details 노출, 전체 롤백");
    }

    // ========================================================================
    // 4. cascading delete (단순 graph, v18.2 시점 부족했던 회귀 보호 — Order=9~11 가 보강)
    // ========================================================================
    @Test
    @Order(4)
    @DisplayName("[Patch] cascading delete — category 삭제 시 자손 leaf 자동 삭제")
    void testCascadingDelete() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("CD FW").build());

        ControlNode cat = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("cat").displayOrder(0).depth(1).build());
        controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat).nodeType(NodeType.control)
                .code("1.1").name("c1").displayOrder(0).depth(2).build());
        controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat).nodeType(NodeType.control)
                .code("1.2").name("c2").displayOrder(1).depth(2).build());
        controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat).nodeType(NodeType.control)
                .code("1.3").name("c3").displayOrder(2).depth(2).build());

        // 4 노드 → cat 삭제 후 0 노드 (DB ON DELETE CASCADE)
        Map<String, Object> body = patchBody(0L,
                List.of(), List.of(), List.of(),
                List.of(Map.of("id", cat.getId()))
        );

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        long count = controlNodeRepository.count();
        assertThat(count).isEqualTo(0L);

        System.out.println("✅ [Patch] cascading delete — 부모 + 자손 3개 모두 삭제됨");
    }

    // ========================================================================
    // 5. tempId 매핑 — 위상 정렬 후 INSERT
    // ========================================================================
    @Test
    @Order(5)
    @DisplayName("[Patch] tempId 매핑 — created 의 의존 순서 무관, 위상 정렬 후 INSERT")
    void testTempIdMapping() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("TM FW").build());

        // 의도: tempA(depth1, parent=null) → tempB(depth2, parent=tempA) → tempC(depth3, parent=tempB)
        // 요청 순서는 일부러 뒤섞어서 — tempC, tempA, tempB
        Map<String, Object> body = patchBody(0L,
                List.of(
                        createdNode("tempC", "tempB", "control",  "1.1.1", "c", 0, 3),
                        createdNode("tempA", null,    "category", "1",     "a", 0, 1),
                        createdNode("tempB", "tempA", "category", "1.1",   "b", 0, 2)
                ),
                List.of(), List.of(), List.of()
        );

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mappings.nodes.length()").value(3))
                .andExpect(jsonPath("$.data.mappings.nodes[*].tempId").value(
                        org.hamcrest.Matchers.hasItems("tempA", "tempB", "tempC")));

        // DB 검증 — 3 노드, parent_id 정확
        List<ControlNode> all =
                controlNodeRepository.findByFrameworkIdOrderByDepthAscDisplayOrderAsc(fw.getId());
        assertThat(all).hasSize(3);

        ControlNode a = all.stream().filter(n -> n.getDepth() == 1).findFirst().orElseThrow();
        ControlNode b = all.stream().filter(n -> n.getDepth() == 2).findFirst().orElseThrow();
        ControlNode c = all.stream().filter(n -> n.getDepth() == 3).findFirst().orElseThrow();

        assertThat(a.getParent()).isNull();
        assertThat(b.getParent().getId()).isEqualTo(a.getId());
        assertThat(c.getParent().getId()).isEqualTo(b.getId());

        System.out.println("✅ [Patch] tempId 매핑 — 위상 정렬 후 a→b→c INSERT, parent 체인 정합");
    }

    // ========================================================================
    // 6. move 액션
    // ========================================================================
    @Test
    @Order(6)
    @DisplayName("[Patch] move 액션 — parent / displayOrder / depth 갱신")
    void testMoveAction() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("MV FW").build());

        ControlNode cat1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("cat1").displayOrder(0).depth(1).build());
        ControlNode cat2 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("2").name("cat2").displayOrder(1).depth(1).build());
        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat1).nodeType(NodeType.control)
                .code("1.1").name("leaf").displayOrder(0).depth(2).build());

        // leaf 를 cat1 → cat2 로 이동
        Map<String, Object> body = patchBody(0L,
                List.of(), List.of(),
                List.of(Map.of("id", leaf.getId(),
                        "newParentId", cat2.getId(),
                        "newDisplayOrder", 0,
                        "newDepth", 2)),
                List.of()
        );

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        ControlNode reloaded = controlNodeRepository.findById(leaf.getId()).orElseThrow();
        assertThat(reloaded.getParent().getId()).isEqualTo(cat2.getId());
        assertThat(reloaded.getDisplayOrder()).isEqualTo(0);
        assertThat(reloaded.getDepth()).isEqualTo(2);

        System.out.println("✅ [Patch] move 액션 — leaf 가 cat1 → cat2 로 이동, parent/displayOrder/depth 정합");
    }

    // ========================================================================
    // 7. 사이클 방지
    // ========================================================================
    @Test
    @Order(7)
    @DisplayName("[Patch] cycle_detected — moved.newParentId 가 자기 자손이면 422")
    void testCycleDetected() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("CY FW").build());

        // cat1 → cat2 → cat3
        ControlNode cat1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("cat1").displayOrder(0).depth(1).build());
        ControlNode cat2 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat1).nodeType(NodeType.category)
                .code("1.1").name("cat2").displayOrder(0).depth(2).build());
        ControlNode cat3 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat2).nodeType(NodeType.category)
                .code("1.1.1").name("cat3").displayOrder(0).depth(3).build());

        // cat1 을 cat3 (자기 자손) 아래로 이동 시도 → 사이클
        Map<String, Object> body = patchBody(0L,
                List.of(), List.of(),
                List.of(Map.of("id", cat1.getId(),
                        "newParentId", cat3.getId(),
                        "newDisplayOrder", 0,
                        "newDepth", 4)),
                List.of()
        );

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.details[*].code").value(
                        org.hamcrest.Matchers.hasItem("cycle_detected")));

        System.out.println("✅ [Patch] cycle_detected — 자기 자손으로 이동 차단됨");
    }

    // ========================================================================
    // 8. ✂ v15 Phase 5-15a — testLeafParentBlocked 삭제
    //
    //    5-14d 의 parent_must_be_category 검증이 v15 5-15a hybrid 모델 채택으로
    //    제거됨 (TreeUpdateService.validateMoved 의 newParent.getNodeType() == control
    //    체크 제거). 본 케이스는 status=422 를 기대하지만 hybrid 후 200 응답이라 fail.
    //
    //    하이브리드 시나리오 inverted version 은 신규 클래스
    //    Phase515aHybridIntegrationTest.testMoveNodeUnderLeaf (Order=4) 가 커버
    //    (이전 422 → 이제 200 기대).
    // ========================================================================

    // ========================================================================
    // 9. v18.3 회귀 보호 — evidence_types 매달림 cascade
    //
    // v18.2 silent skip 시나리오 (자식 + evidence_types 매달림) 의 정공 fix
    // (V_v18_3 CASCADE FK) 회귀 차단. evidence_types.control_id FK 가
    // ON DELETE CASCADE 인지 검증. testCascadingDelete (Order=4) 의 단순 graph
    // 한계를 확장.
    // ========================================================================
    @Test
    @Order(9)
    @DisplayName("[v18.3] cascading delete — evidence_types 매달린 카테고리 삭제 → " +
            "control_nodes + evidence_types 모두 cascade 삭제")
    void testCascadingDeleteWithEvidenceTypes() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("CD-ET FW").build());

        ControlNode cat = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("cat").displayOrder(0).depth(1).build());
        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat).nodeType(NodeType.control)
                .code("1.1").name("leaf").displayOrder(0).depth(2).build());

        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf).name("증빙1").build());
        EvidenceType et2 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf).name("증빙2").build());

        Long et1Id = et1.getId();
        Long et2Id = et2.getId();

        // 카테고리 삭제 → cascade chain 으로 leaf + 2 evidence_types 모두 삭제
        Map<String, Object> body = patchBody(0L,
                List.of(), List.of(), List.of(),
                List.of(Map.of("id", cat.getId()))
        );

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        // DB 검증 — control_nodes 0, evidence_types 0
        assertThat(controlNodeRepository.count()).isEqualTo(0L);
        assertThat(evidenceTypeRepository.findById(et1Id)).isEmpty();
        assertThat(evidenceTypeRepository.findById(et2Id)).isEmpty();

        System.out.println("✅ [v18.3] evidence_types 매달림 cascade — " +
                "control_nodes 0 / evidence_types 0 (V_v18_3 CASCADE 작동)");
    }

    // ========================================================================
    // 10. v18.3 회귀 보호 — collection_jobs 매달림 cascade
    //
    // collection_jobs.evidence_type_id FK 가 ON DELETE CASCADE 인지 검증.
    // 카테고리 → leaf → evidence_type → collection_job 의 3-level cascade chain.
    // ========================================================================
    @Test
    @Order(10)
    @DisplayName("[v18.3] cascading delete — collection_jobs 매달린 노드 삭제 → " +
            "control_nodes + evidence_types + collection_jobs 모두 cascade 삭제")
    void testCascadingDeleteWithCollectionJobs() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("CD-CJ FW").build());

        ControlNode cat = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("cat").displayOrder(0).depth(1).build());
        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat).nodeType(NodeType.control)
                .code("1.1").name("leaf").displayOrder(0).depth(2).build());

        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf).name("증빙").build());
        CollectionJob job = collectionJobRepository.save(CollectionJob.builder()
                .name("자동수집작업")
                .jobType(JobType.web_scraping)   // ✅ 실 enum 값 (script | excel_extract | log_extract 중 1)
                .scriptPath("/scripts/test.sh")
                .evidenceType(et)
                .isActive(true)
                .build());

        Long etId = et.getId();
        Long jobId = job.getId();

        // 카테고리 삭제 → cascade chain 으로 leaf + et + job 모두 삭제
        Map<String, Object> body = patchBody(0L,
                List.of(), List.of(), List.of(),
                List.of(Map.of("id", cat.getId()))
        );

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        // DB 검증
        assertThat(controlNodeRepository.count()).isEqualTo(0L);
        assertThat(evidenceTypeRepository.findById(etId)).isEmpty();
        assertThat(collectionJobRepository.findById(jobId)).isEmpty();

        System.out.println("✅ [v18.3] collection_jobs 매달림 cascade — " +
                "control_nodes 0 / evidence_types 0 / collection_jobs 0");
    }

    // ========================================================================
    // 11. v18.3 회귀 보호 — evidence_files 매달림 cascade (N+M 그래프)
    //
    // v18.2 silent skip 의 가장 복잡한 시나리오. L_HIBERNATE_CASCADE_SILENT 가
    // 명시한 "evidence_types/files N+M 매달림 추가 시 재현" 의 정공 검증.
    // ========================================================================
    @Test
    @Order(11)
    @DisplayName("[v18.3] cascading delete — evidence_files 매달린 N+M 그래프 " +
            "(v18.2 재현 시나리오) → 전체 cascade 삭제")
    void testCascadingDeleteWithEvidenceFiles() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("CD-EF FW").build());

        ControlNode cat = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("cat").displayOrder(0).depth(1).build());
        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(cat).nodeType(NodeType.control)
                .code("1.1").name("leaf").displayOrder(0).depth(2).build());

        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf).name("증빙1").build());
        EvidenceType et2 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf).name("증빙2").build());

        // 각 et 에 2 / 1 EvidenceFile 매달기 (N+M 그래프)
        EvidenceFile ef1a = evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et1).fileName("f1a.pdf").filePath("/tmp/f1a.pdf")
                .fileSize(100L).version(1).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now()).reviewStatus(ReviewStatus.auto_approved)
                .build());
        EvidenceFile ef1b = evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et1).fileName("f1b.pdf").filePath("/tmp/f1b.pdf")
                .fileSize(200L).version(2).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now()).reviewStatus(ReviewStatus.auto_approved)
                .build());
        EvidenceFile ef2a = evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et2).fileName("f2a.pdf").filePath("/tmp/f2a.pdf")
                .fileSize(300L).version(1).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now()).reviewStatus(ReviewStatus.auto_approved)
                .build());

        List<Long> efIds = List.of(ef1a.getId(), ef1b.getId(), ef2a.getId());

        // 카테고리 삭제 → 전체 N+M cascade
        Map<String, Object> body = patchBody(0L,
                List.of(), List.of(), List.of(),
                List.of(Map.of("id", cat.getId()))
        );

        mockMvc.perform(patch("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        // DB 검증 — 전체 0
        assertThat(controlNodeRepository.count()).isEqualTo(0L);
        assertThat(evidenceTypeRepository.count()).isEqualTo(0L);
        for (Long efId : efIds) {
            assertThat(evidenceFileRepository.findById(efId)).isEmpty();
        }

        System.out.println("✅ [v18.3] N+M 그래프 cascade — " +
                "control_nodes 0 / evidence_types 0 / evidence_files 0 " +
                "(v18.2 재현 시나리오 정공 차단)");
    }
}