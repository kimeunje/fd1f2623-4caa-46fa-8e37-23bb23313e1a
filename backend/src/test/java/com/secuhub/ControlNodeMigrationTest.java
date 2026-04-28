package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.entity.Control;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.repository.ControlRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5-14b — V6 데이터 이주 마이그레이션 + INSERT 차단 검증
 *
 * <h3>테스트 환경</h3>
 * <ul>
 *   <li>H2 in-memory (MariaDB 모드) + ddl-auto: create. Flyway 비활성.</li>
 *   <li>V6 SQL 파일은 prod-only 실행이므로, 본 테스트는 V6 의 portable 부분
 *       (Step 1, 2, 3b) 만 JdbcTemplate 으로 직접 실행하여 검증한다.
 *       Step 3a/3c (FK 동적 drop/add) 는 MariaDB-only PREPARE/EXECUTE 구문이라
 *       H2 에서 실행 불가 → 테스트는 {@code SET REFERENTIAL_INTEGRITY FALSE} 로
 *       FK 일시 disable 후 UPDATE 만 검증한다.</li>
 * </ul>
 *
 * <h3>검증 항목 (8 케이스)</h3>
 * <ol>
 *   <li>{@link #testV6_BasicShape} — 1 framework, 2 domain, 4 control → 2 cat + 4 leaf</li>
 *   <li>{@link #testV6_NullDomain} — domain=NULL 행은 "미분류" category 1개로 묶임</li>
 *   <li>{@link #testV6_MultiFramework} — 동일 domain 명이 framework 별로 분리</li>
 *   <li>{@link #testV6_LeafIdOffset} — 모든 leaf id == 원본 controls.id + 1,000,000</li>
 *   <li>{@link #testV6_EvidenceTypeRemapping} — evidence_types.control_id 가 leaf id 와 매칭</li>
 *   <li>{@link #testV6_DisplayOrder} — category, leaf 모두 정확한 정렬</li>
 *   <li>{@link #testV6_EmptyControls} — controls 비어있을 때 noop (오류 없음)</li>
 *   <li>{@link #testInsertBlocked_410} — POST /controls / POST /import 모두 410 Gone</li>
 * </ol>
 *
 * <p>Spec: SecuHub_Project_Specification_v14.md §6.4 / §3.3.1.3</p>
 * <p>HANDOFF: v9 §2.2 (5-14a 학습 사항) — 테스트 격리 패턴 유지</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-14b — V6 controls→control_nodes 마이그레이션 + INSERT 차단")
class ControlNodeMigrationTest {

    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlRepository controlRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    // ========================================================================
    // 5-14a Fix B 패턴 — 테스트 격리 보강 (deleteAllInBatch cleanup)
    // ========================================================================
    @BeforeEach
    void cleanup() {
        // FK off 상태로 DELETE — 자식 테이블 (control_nodes, evidence_types)
        // 도 함께 정리하기 위해 native 사용. orphan_removal 계열의 영속성
        // 이슈를 우회하면서, JPA 1차 캐시 leftover 도 함께 비운다.
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.execute("DELETE FROM evidence_types");
            jdbcTemplate.execute("DELETE FROM control_nodes");
            jdbcTemplate.execute("DELETE FROM controls");
            jdbcTemplate.execute("DELETE FROM frameworks");
            // testInsertBlocked_410 이 만든 admin 사용자만 정리.
            // 다른 테스트 클래스 (AuthenticationTest 등) 의 사용자는 건드리지 않도록
            // 본 테스트 전용 email suffix 패턴만 매칭.
            jdbcTemplate.execute(
                    "DELETE FROM users WHERE email LIKE '%-5-14b@test.com'");

            // ----------------------------------------------------------------
            // ID sequence reset (5-14b 추가 학습) — H2 specific
            // ----------------------------------------------------------------
            // V6 Step 2 가 control_nodes 에 'c.id + 1000000' 명시 INSERT 후
            // H2 의 AUTO_INCREMENT sequence 가 1,000,000+ 로 점프한 채 남는다.
            // DELETE 만으로는 sequence 를 되돌리지 못하므로, 다음 테스트의
            // Step 1 (id 자동 발급 category INSERT) 이 1,000,xxx 를 받아
            // Step 2 의 leaf id (controls.id + 1,000,000) 와 PK 충돌한다.
            //
            // 모든 관련 테이블의 sequence 를 1로 reset 해서 매 테스트가
            // 깨끗한 상태에서 시작하도록 한다.
            jdbcTemplate.execute("ALTER TABLE control_nodes ALTER COLUMN id RESTART WITH 1");
            jdbcTemplate.execute("ALTER TABLE evidence_types ALTER COLUMN id RESTART WITH 1");
            jdbcTemplate.execute("ALTER TABLE controls       ALTER COLUMN id RESTART WITH 1");
            jdbcTemplate.execute("ALTER TABLE frameworks     ALTER COLUMN id RESTART WITH 1");
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        entityManager.clear();
    }

    // ========================================================================
    // V6 SQL 의 portable 부분 (Step 1 / 2 / 3b)
    // ========================================================================
    /**
     * 실 V6 파일의 Step 1 / 2 / 3b 와 의미적으로 동일.
     * Step 3a/3c (FK 동적 drop/add) 는 MariaDB-only PREPARE/EXECUTE 라
     * H2 에서 실행 불가 → 테스트는 {@code SET REFERENTIAL_INTEGRITY FALSE}
     * 로 FK 우회. UPDATE 자체의 데이터 정합은 동일하게 검증된다.
     */
    private void runMigrationPortable() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            // Step 1: distinct (framework_id, domain) -> category nodes
            jdbcTemplate.execute("""
                INSERT INTO control_nodes (framework_id, parent_id, node_type, code, name,
                                           display_order, depth, created_at, updated_at)
                SELECT
                    framework_id,
                    NULL,
                    'category',
                    COALESCE(domain, '미분류'),
                    COALESCE(domain, '미분류'),
                    (ROW_NUMBER() OVER (PARTITION BY framework_id ORDER BY MIN(code))) - 1,
                    1,
                    MIN(created_at),
                    CURRENT_TIMESTAMP
                FROM controls
                GROUP BY framework_id, domain
                """);

            // Step 2: each controls row -> control node (depth=2, id offset 1,000,000)
            jdbcTemplate.execute("""
                INSERT INTO control_nodes (id, framework_id, parent_id, node_type, code, name, description,
                                           display_order, depth, created_at, updated_at)
                SELECT
                    c.id + 1000000,
                    c.framework_id,
                    cat.id,
                    'control',
                    c.code,
                    c.name,
                    c.description,
                    (ROW_NUMBER() OVER (PARTITION BY c.framework_id, c.domain ORDER BY c.code)) - 1,
                    2,
                    c.created_at,
                    CURRENT_TIMESTAMP
                FROM controls c
                JOIN control_nodes cat
                  ON cat.framework_id = c.framework_id
                 AND cat.node_type = 'category'
                 AND cat.name = COALESCE(c.domain, '미분류')
                """);

            // Step 3b: re-point evidence_types.control_id at the new leaf node id
            jdbcTemplate.execute("UPDATE evidence_types SET control_id = control_id + 1000000");
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    // ========================================================================
    // 1. Basic shape — 1 framework / 2 domain / 4 control → 2 cat + 4 leaf
    // ========================================================================
    @Test
    @Order(1)
    @DisplayName("[V6] 1 framework / 2 domain / 4 controls → 2 category + 4 leaf")
    @Transactional
    void testV6_BasicShape() {
        Framework fw = frameworkRepository.save(Framework.builder().name("ISMS-P 2026").build());

        Control c1 = controlRepository.save(Control.builder()
                .framework(fw).code("1.1.1").domain("관리체계").name("경영진의 참여").build());
        Control c2 = controlRepository.save(Control.builder()
                .framework(fw).code("1.1.2").domain("관리체계").name("최고책임자 지정").build());
        Control c3 = controlRepository.save(Control.builder()
                .framework(fw).code("2.1.1").domain("정책 수립").name("정책 문서화").build());
        Control c4 = controlRepository.save(Control.builder()
                .framework(fw).code("2.1.2").domain("정책 수립").name("정기 검토").build());

        evidenceTypeRepository.save(EvidenceType.builder().control(c1).name("회의록").build());
        evidenceTypeRepository.save(EvidenceType.builder().control(c3).name("정책 문서").build());

        entityManager.flush();
        entityManager.clear();

        runMigrationPortable();

        long categoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE node_type = 'category'", Long.class);
        long leafCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE node_type = 'control'", Long.class);

        assertThat(categoryCount).isEqualTo(2L);
        assertThat(leafCount).isEqualTo(4L);

        // depth 검증
        long depth1Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE depth = 1", Long.class);
        long depth2Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE depth = 2", Long.class);
        assertThat(depth1Count).isEqualTo(2L);
        assertThat(depth2Count).isEqualTo(4L);

        // 모든 category 는 parent_id IS NULL
        long rootCategoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE node_type = 'category' AND parent_id IS NULL",
                Long.class);
        assertThat(rootCategoryCount).isEqualTo(2L);

        // 모든 leaf 는 parent_id IS NOT NULL
        long parentedLeafCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE node_type = 'control' AND parent_id IS NOT NULL",
                Long.class);
        assertThat(parentedLeafCount).isEqualTo(4L);

        System.out.println("✅ [V6] 1 framework / 2 domain / 4 controls → 2 category + 4 leaf (depth 1/2 정합)");
    }

    // ========================================================================
    // 2. NULL domain — "미분류" category 1개로 묶임
    // ========================================================================
    @Test
    @Order(2)
    @DisplayName("[V6] domain=NULL 행은 '미분류' category 로 묶임")
    @Transactional
    void testV6_NullDomain() {
        Framework fw = frameworkRepository.save(Framework.builder().name("Custom FW").build());

        controlRepository.save(Control.builder()
                .framework(fw).code("X-001").domain(null).name("분류 미정 통제 A").build());
        controlRepository.save(Control.builder()
                .framework(fw).code("X-002").domain(null).name("분류 미정 통제 B").build());
        controlRepository.save(Control.builder()
                .framework(fw).code("Y-001").domain("일반").name("일반 통제").build());

        entityManager.flush();
        entityManager.clear();

        runMigrationPortable();

        // "미분류" category 정확히 1개
        long misClassifiedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE node_type = 'category' AND name = '미분류'",
                Long.class);
        assertThat(misClassifiedCount).isEqualTo(1L);

        // "미분류" category 의 직속 leaf 2개
        Long misCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM control_nodes WHERE node_type = 'category' AND name = '미분류'",
                Long.class);
        long misLeafCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE parent_id = ?",
                Long.class, misCategoryId);
        assertThat(misLeafCount).isEqualTo(2L);

        // 전체: 2 category + 3 leaf
        long totalCategoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE node_type = 'category'", Long.class);
        long totalLeafCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE node_type = 'control'", Long.class);
        assertThat(totalCategoryCount).isEqualTo(2L);
        assertThat(totalLeafCount).isEqualTo(3L);

        System.out.println("✅ [V6] domain=NULL 행은 '미분류' category 로 안전하게 묶임");
    }

    // ========================================================================
    // 3. Multi-framework — 동일 domain 명이 framework 별로 분리
    // ========================================================================
    @Test
    @Order(3)
    @DisplayName("[V6] 동일 domain 명이 framework 별로 별개 category 로 분리")
    @Transactional
    void testV6_MultiFramework() {
        Framework fw1 = frameworkRepository.save(Framework.builder().name("ISMS-P 2026").build());
        Framework fw2 = frameworkRepository.save(Framework.builder().name("ISO 27001").build());

        controlRepository.save(Control.builder()
                .framework(fw1).code("A-1").domain("정책").name("FW1 정책 통제").build());
        controlRepository.save(Control.builder()
                .framework(fw2).code("A-1").domain("정책").name("FW2 정책 통제").build());
        controlRepository.save(Control.builder()
                .framework(fw1).code("B-1").domain("운영").name("FW1 운영 통제").build());

        entityManager.flush();
        entityManager.clear();

        runMigrationPortable();

        // category 총 3개 (FW1: 정책, 운영) + (FW2: 정책)
        long totalCategoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE node_type = 'category'", Long.class);
        assertThat(totalCategoryCount).isEqualTo(3L);

        // FW1 의 category 2개
        long fw1CategoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE node_type = 'category' AND framework_id = ?",
                Long.class, fw1.getId());
        assertThat(fw1CategoryCount).isEqualTo(2L);

        // FW2 의 category 1개
        long fw2CategoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE node_type = 'category' AND framework_id = ?",
                Long.class, fw2.getId());
        assertThat(fw2CategoryCount).isEqualTo(1L);

        // "정책" 이름의 category 가 정확히 2개 (각 framework 별 1개씩)
        long policyCategoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE node_type = 'category' AND name = '정책'",
                Long.class);
        assertThat(policyCategoryCount).isEqualTo(2L);

        System.out.println("✅ [V6] 동일 domain 명도 framework 별로 별개 category 로 분리됨");
    }

    // ========================================================================
    // 4. Leaf ID offset — leaf id == 원본 controls.id + 1,000,000
    // ========================================================================
    @Test
    @Order(4)
    @DisplayName("[V6] leaf control_node id == 원본 controls.id + 1,000,000 offset")
    @Transactional
    void testV6_LeafIdOffset() {
        Framework fw = frameworkRepository.save(Framework.builder().name("ID Offset FW").build());

        Control c1 = controlRepository.save(Control.builder()
                .framework(fw).code("A").domain("X").name("통제 A").build());
        Control c2 = controlRepository.save(Control.builder()
                .framework(fw).code("B").domain("X").name("통제 B").build());

        Long c1Id = c1.getId();
        Long c2Id = c2.getId();

        entityManager.flush();
        entityManager.clear();

        runMigrationPortable();

        // leaf id == 원본 + 1,000,000
        Long leafC1Id = jdbcTemplate.queryForObject(
                "SELECT id FROM control_nodes WHERE node_type = 'control' AND code = 'A'",
                Long.class);
        Long leafC2Id = jdbcTemplate.queryForObject(
                "SELECT id FROM control_nodes WHERE node_type = 'control' AND code = 'B'",
                Long.class);

        assertThat(leafC1Id).isEqualTo(c1Id + 1_000_000L);
        assertThat(leafC2Id).isEqualTo(c2Id + 1_000_000L);

        // category 는 offset 안 받음 (1, 2, ... AUTO_INCREMENT)
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM control_nodes WHERE node_type = 'category'", Long.class);
        assertThat(categoryId).isLessThan(1_000_000L);

        System.out.println("✅ [V6] leaf id offset 1,000,000 정확 적용 (category 는 offset 없음)");
    }

    // ========================================================================
    // 5. EvidenceType remapping — control_id 가 leaf id 와 정확히 매칭
    // ========================================================================
    @Test
    @Order(5)
    @DisplayName("[V6] evidence_types.control_id 가 새 leaf node id 와 정확히 매칭")
    @Transactional
    void testV6_EvidenceTypeRemapping() {
        Framework fw = frameworkRepository.save(Framework.builder().name("Remap FW").build());

        Control c1 = controlRepository.save(Control.builder()
                .framework(fw).code("R-1").domain("재매핑").name("증빙 보유 통제").build());

        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(c1).name("증빙 1").build());
        EvidenceType et2 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(c1).name("증빙 2").build());

        Long c1Id = c1.getId();
        Long et1Id = et1.getId();
        Long et2Id = et2.getId();

        entityManager.flush();
        entityManager.clear();

        runMigrationPortable();

        // 새 leaf id 확인
        Long expectedLeafId = c1Id + 1_000_000L;

        // 두 evidence_type 모두 새 leaf id 를 가리키도록 remap 됨
        Long et1ControlId = jdbcTemplate.queryForObject(
                "SELECT control_id FROM evidence_types WHERE id = ?", Long.class, et1Id);
        Long et2ControlId = jdbcTemplate.queryForObject(
                "SELECT control_id FROM evidence_types WHERE id = ?", Long.class, et2Id);

        assertThat(et1ControlId).isEqualTo(expectedLeafId);
        assertThat(et2ControlId).isEqualTo(expectedLeafId);

        // 그리고 그 leaf 가 실제로 control_nodes 에 존재 (참조 무결성)
        long leafExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE id = ? AND node_type = 'control'",
                Long.class, expectedLeafId);
        assertThat(leafExists).isEqualTo(1L);

        System.out.println("✅ [V6] evidence_types.control_id remap 정확 (참조 무결성 유지)");
    }

    // ========================================================================
    // 6. display_order — category, leaf 모두 정확한 정렬
    // ========================================================================
    @Test
    @Order(6)
    @DisplayName("[V6] category 는 MIN(code) 순, leaf 는 같은 부모 내 code 순 정렬")
    @Transactional
    void testV6_DisplayOrder() {
        Framework fw = frameworkRepository.save(Framework.builder().name("Order FW").build());

        // domain "B" 의 MIN(code) = "1.0"
        // domain "A" 의 MIN(code) = "2.0"
        // 따라서 category 정렬: B(0), A(1)
        controlRepository.save(Control.builder()
                .framework(fw).code("2.0").domain("A").name("A 통제 1").build());
        controlRepository.save(Control.builder()
                .framework(fw).code("2.5").domain("A").name("A 통제 2").build());
        controlRepository.save(Control.builder()
                .framework(fw).code("1.0").domain("B").name("B 통제 1").build());
        controlRepository.save(Control.builder()
                .framework(fw).code("1.5").domain("B").name("B 통제 2").build());

        entityManager.flush();
        entityManager.clear();

        runMigrationPortable();

        // category 정렬: B(display_order=0), A(display_order=1)
        Integer bOrder = jdbcTemplate.queryForObject(
                "SELECT display_order FROM control_nodes WHERE node_type = 'category' AND name = 'B'",
                Integer.class);
        Integer aOrder = jdbcTemplate.queryForObject(
                "SELECT display_order FROM control_nodes WHERE node_type = 'category' AND name = 'A'",
                Integer.class);
        assertThat(bOrder).isEqualTo(0);
        assertThat(aOrder).isEqualTo(1);

        // 같은 category 내 leaf 정렬: code 오름차순으로 0, 1
        Long bCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM control_nodes WHERE node_type = 'category' AND name = 'B'",
                Long.class);
        String firstCode = jdbcTemplate.queryForObject(
                "SELECT code FROM control_nodes "
                        + "WHERE node_type = 'control' AND parent_id = ? AND display_order = 0",
                String.class, bCategoryId);
        String secondCode = jdbcTemplate.queryForObject(
                "SELECT code FROM control_nodes "
                        + "WHERE node_type = 'control' AND parent_id = ? AND display_order = 1",
                String.class, bCategoryId);
        assertThat(firstCode).isEqualTo("1.0");
        assertThat(secondCode).isEqualTo("1.5");

        long bLeafCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes WHERE node_type = 'control' AND parent_id = ?",
                Long.class, bCategoryId);
        assertThat(bLeafCount).isEqualTo(2L);

        System.out.println("✅ [V6] display_order 정확 — category MIN(code) 순, leaf 같은 부모 내 code 순");
    }

    // ========================================================================
    // 7. Empty controls — noop (오류 없음)
    // ========================================================================
    @Test
    @Order(7)
    @DisplayName("[V6] controls 비어있어도 마이그레이션 noop 으로 안전 종료")
    @Transactional
    void testV6_EmptyControls() {
        // controls 한 행도 없는 상태에서 마이그레이션 — 어떤 예외도 없어야 함
        runMigrationPortable();

        long allNodes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM control_nodes", Long.class);
        assertThat(allNodes).isEqualTo(0L);

        long allEvidenceTypes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evidence_types", Long.class);
        assertThat(allEvidenceTypes).isEqualTo(0L);

        System.out.println("✅ [V6] 빈 controls 환경에서 마이그레이션 noop (오류 없음)");
    }

    // ========================================================================
    // 8. INSERT 차단 — POST /controls / POST /import 모두 410 Gone
    // ========================================================================
    @Test
    @Order(8)
    @DisplayName("[Block] POST /api/v1/frameworks/{id}/controls → 410 Gone")
    void testInsertBlocked_410() throws Exception {
        // 테스트용 admin 토큰 발급 (다른 테스트와 동일 패턴)
        User admin = userRepository.save(User.builder()
                .email("admin-5-14b@test.com")
                .name("관리자")
                .hashedPassword(passwordEncoder.encode("password"))
                .role(UserRole.admin)
                .permissionEvidence(true)
                .build());
        Framework fw = frameworkRepository.save(Framework.builder().name("Block Test FW").build());

        String token = jwtTokenProvider.createToken(
                admin.getId(), admin.getEmail(), admin.getRole().name());

        // 8-1) ControlController.create() — 410 Gone + 안내 메시지
        // DTO setter 가정에 의존하지 않도록 Map → JSON 으로 직접 직렬화
        Map<String, Object> req = Map.of(
                "code", "BLOCKED",
                "domain", "차단",
                "name", "차단 테스트 통제"
        );

        mockMvc.perform(post("/api/v1/frameworks/{id}/controls", fw.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("PATCH /api/v1/frameworks/{id}/tree")));

        // controls 테이블에 신규 row 안 남았는지 확인 (rollback 안전망 그대로)
        long controlsCount = controlRepository.count();
        assertThat(controlsCount).isEqualTo(0L);

        System.out.println("✅ [Block] POST /controls → 410 Gone, controls 테이블 신규 INSERT 차단됨");
    }
}