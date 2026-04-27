package com.secuhub;

import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.NodeType;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5-14a — control_nodes 스키마 검증 테스트 (10 케이스).
 *
 * <p>실행: {@code ./gradlew test --tests ControlNodeSchemaTest}</p>
 *
 * <p><b>v14 fix-1</b>: testCascadeOnParentDelete / testCascadeOnFrameworkDelete 에
 * {@code @Transactional} 추가 (entityManager.flush 가 트랜잭션 필요).
 * native DELETE 와 native COUNT 가 같은 트랜잭션 안에서 ON DELETE CASCADE 동작 검증.</p>
 *
 * <p>검증 항목:</p>
 * <ol>
 *   <li>2단 트리 저장/조회 (depth=1 category + depth=2 control)</li>
 *   <li>5단 mixed-depth 트리 (1 → 1.1 → 1.1.1 → 1.1.1.1 → 1.1.1.1.1)</li>
 *   <li>같은 부모 안 leaf+category sibling 공존 (mixed-depth)</li>
 *   <li>ON DELETE CASCADE — 부모 카테고리 삭제 시 자손 일괄 삭제</li>
 *   <li>ON DELETE CASCADE — Framework 삭제 시 모든 control_nodes 삭제</li>
 *   <li>CHECK 제약 — node_type 비정상 값 차단</li>
 *   <li>CHECK 제약 — depth 범위 초과 차단</li>
 *   <li>{@code @Version} — Framework 수정 시 version 자동 증가</li>
 *   <li>Repository — leaf 한정 조회 (외부 API 호환용)</li>
 *   <li>Repository — 재귀 CTE 자손 일괄 조회 (cascading delete 사전 카운트용)</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ControlNodeSchemaTest {

    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        controlNodeRepository.deleteAll();
        frameworkRepository.deleteAll();
    }

    // ====================================================================
    // 1. 기본 트리 저장
    // ====================================================================

    @Test
    @Order(1)
    @DisplayName("[Insert] depth=1 카테고리 + depth=2 자식 통제 → 트리 정상 저장")
    @Transactional
    void testBasicTree() {
        Framework fw = frameworkRepository.save(Framework.builder().name("ISMS-P").build());

        ControlNode cat = saveCategory(fw, null, "1", "관리체계 수립 및 운영", 1);
        ControlNode leaf = saveControl(fw, cat, "1.1", "정책 수립", 2);

        entityManager.flush();
        entityManager.clear();

        List<ControlNode> all = controlNodeRepository
                .findByFrameworkIdOrderByDepthAscDisplayOrderAsc(fw.getId());

        assertThat(all).hasSize(2);
        assertThat(all.get(0).getDepth()).isEqualTo(1);
        assertThat(all.get(0).isCategory()).isTrue();
        assertThat(all.get(1).getDepth()).isEqualTo(2);
        assertThat(all.get(1).isLeaf()).isTrue();
        assertThat(all.get(1).getParent().getId()).isEqualTo(cat.getId());

        System.out.println("✅ [Insert] depth=1 + depth=2 트리 정상");
    }

    // ====================================================================
    // 2. 5단 mixed-depth (실무 ISMS-P + 사내 자체 표준 호환)
    // ====================================================================

    @Test
    @Order(2)
    @DisplayName("[Tree] 5단 mixed-depth (1 → 1.1 → 1.1.1 → 1.1.1.1 → 1.1.1.1.1) 저장/조회")
    @Transactional
    void test5DepthTree() {
        Framework fw = frameworkRepository.save(Framework.builder().name("Deep FW").build());

        ControlNode d1 = saveCategory(fw, null, "2",         "보호대책 요구사항",  1);
        ControlNode d2 = saveCategory(fw, d1,   "2.1",       "정책, 조직, 자산",   2);
        ControlNode d3 = saveCategory(fw, d2,   "2.1.1",     "정책의 유지관리",    3);
        ControlNode d4 = saveCategory(fw, d3,   "2.1.1.1",   "정책 검토 주기",     4);
        ControlNode d5 = saveControl (fw, d4,   "2.1.1.1.1", "분기별 검토",        5);

        entityManager.flush();
        entityManager.clear();

        List<ControlNode> all = controlNodeRepository
                .findByFrameworkIdOrderByDepthAscDisplayOrderAsc(fw.getId());

        assertThat(all).hasSize(5);
        assertThat(all).extracting(ControlNode::getDepth)
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(all.get(4).isLeaf()).isTrue();

        System.out.println("✅ [Tree] 5단 mixed-depth 정상 (depth 1→5, leaf 는 depth=5)");
    }

    // ====================================================================
    // 3. Mixed-depth sibling (같은 부모 leaf + category 공존)
    // ====================================================================

    @Test
    @Order(3)
    @DisplayName("[Mixed-depth] 같은 부모 아래 leaf + category sibling 공존")
    @Transactional
    void testMixedDepthSibling() {
        Framework fw = frameworkRepository.save(Framework.builder().name("Mixed FW").build());

        ControlNode parent = saveCategory(fw, null, "2.1.1", "정책 유지", 3);

        // sibling 1: leaf (depth=4 직접 통제)
        ControlNode leaf = saveControl(fw, parent, "2.1.1.2", "변경 관리", 4);
        // sibling 2: category (depth=4 분류, 자식을 더 가짐)
        ControlNode cat = saveCategory(fw, parent, "2.1.1.1", "검토 주기", 4);
        // category 의 자식 leaf
        saveControl(fw, cat, "2.1.1.1.1", "분기 검토", 5);

        entityManager.flush();
        entityManager.clear();

        List<ControlNode> children = controlNodeRepository
                .findByParentIdOrderByDisplayOrderAsc(parent.getId());

        assertThat(children).hasSize(2);
        assertThat(children).extracting(ControlNode::getNodeType)
                .containsExactlyInAnyOrder(NodeType.control, NodeType.category);
        assertThat(children).extracting(ControlNode::getDepth)
                .containsOnly(4);   // 둘 다 depth=4 sibling

        System.out.println("✅ [Mixed-depth] 같은 부모 leaf+category sibling 공존");
    }

    // ====================================================================
    // 4. ON DELETE CASCADE — 부모 카테고리 삭제
    // ====================================================================

    /**
     * v14 fix-1: {@code @Transactional} 추가.
     *
     * <p>이전 버전에는 트랜잭션 어노테이션 없이 native DELETE/COUNT 를 호출했는데,
     * Spring 의 SharedEntityManager 가 활성 트랜잭션 없이 {@code flush()} 호출 시
     * {@code TransactionRequiredException} 던짐. {@code @Transactional} 안에서:</p>
     * <ul>
     *   <li>save() / native DELETE / native COUNT 모두 같은 트랜잭션</li>
     *   <li>DB 의 ON DELETE CASCADE 는 native DELETE 즉시 발화 (커밋 무관)</li>
     *   <li>같은 트랜잭션 안 read-your-own-writes 로 자손 사라짐 검증 가능</li>
     * </ul>
     */
    @Test
    @Order(4)
    @DisplayName("[Cascade] 부모 카테고리 삭제 → 자손 노드 일괄 삭제 (ON DELETE CASCADE)")
    @Transactional
    void testCascadeOnParentDelete() {
        Framework fw = frameworkRepository.save(Framework.builder().name("Cascade FW").build());
        ControlNode root = saveCategory(fw, null, "1",     "대분류", 1);
        ControlNode mid  = saveCategory(fw, root, "1.1",   "중분류", 2);
        ControlNode leaf = saveControl (fw, mid,  "1.1.1", "통제",   3);

        Long rootId = root.getId();
        Long midId  = mid.getId();
        Long leafId = leaf.getId();

        // 메모리 상태 DB 확정 + 1차 캐시 비우기
        entityManager.flush();
        entityManager.clear();

        // Native DELETE 로 root 삭제 (Hibernate cascade 가 아닌 DB FK 의 ON DELETE CASCADE 검증)
        int deleted = entityManager.createNativeQuery(
                "DELETE FROM control_nodes WHERE id = :id"
        ).setParameter("id", rootId).executeUpdate();
        assertThat(deleted).isEqualTo(1);

        // 자손이 DB 에서 사라졌는지 직접 질의 (같은 트랜잭션 안에서도 ON DELETE CASCADE 결과 보임)
        Number midRemain = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM control_nodes WHERE id = :id"
        ).setParameter("id", midId).getSingleResult();

        Number leafRemain = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM control_nodes WHERE id = :id"
        ).setParameter("id", leafId).getSingleResult();

        assertThat(midRemain.longValue()).isZero();
        assertThat(leafRemain.longValue()).isZero();

        System.out.println("✅ [Cascade] root 삭제 → mid + leaf 자동 삭제 (ON DELETE CASCADE 정상)");
    }

    // ====================================================================
    // 5. ON DELETE CASCADE — Framework 삭제
    // ====================================================================

    /**
     * v14 fix-1: {@code @Transactional} 추가 (testCascadeOnParentDelete 와 동일 사유).
     */
    @Test
    @Order(5)
    @DisplayName("[Cascade] Framework 삭제 → 모든 control_nodes 일괄 삭제")
    @Transactional
    void testCascadeOnFrameworkDelete() {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW Del").build());
        Long fwId = fw.getId();

        saveCategory(fw, null, "1", "분류 A", 1);
        ControlNode mid = saveCategory(fw, null, "2", "분류 B", 1);
        saveControl(fw, mid, "2.1", "통제", 2);

        entityManager.flush();
        entityManager.clear();

        int deleted = entityManager.createNativeQuery(
                "DELETE FROM frameworks WHERE id = :id"
        ).setParameter("id", fwId).executeUpdate();
        assertThat(deleted).isEqualTo(1);

        Number remain = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM control_nodes WHERE framework_id = :id"
        ).setParameter("id", fwId).getSingleResult();
        assertThat(remain.longValue()).isZero();

        System.out.println("✅ [Cascade] Framework 삭제 → control_nodes 전체 삭제");
    }

    // ====================================================================
    // 6. CHECK 제약 — node_type
    // ====================================================================

    @Test
    @Order(6)
    @DisplayName("[Constraint] node_type 비정상 값 → CHECK 위반")
    @Transactional
    void testNodeTypeCheck() {
        Framework fw = frameworkRepository.save(Framework.builder().name("CHK FW").build());

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                INSERT INTO control_nodes
                  (framework_id, parent_id, node_type, code, name,
                   display_order, depth, created_at, updated_at)
                VALUES
                  (:fwId, NULL, 'invalid_type', 'X', '잘못된 노드',
                   0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)
                .setParameter("fwId", fw.getId())
                .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class);

        System.out.println("✅ [Constraint] node_type CHECK 위반 차단");
    }

    // ====================================================================
    // 7. CHECK 제약 — depth 범위
    // ====================================================================

    @Test
    @Order(7)
    @DisplayName("[Constraint] depth 11 → CHECK 위반 (1~10 범위)")
    @Transactional
    void testDepthCheck() {
        Framework fw = frameworkRepository.save(Framework.builder().name("DEP FW").build());

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                INSERT INTO control_nodes
                  (framework_id, parent_id, node_type, code, name,
                   display_order, depth, created_at, updated_at)
                VALUES
                  (:fwId, NULL, 'category', 'X', '깊이 11',
                   0, 11, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)
                .setParameter("fwId", fw.getId())
                .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class);

        System.out.println("✅ [Constraint] depth>10 CHECK 위반 차단");
    }

    // ====================================================================
    // 8. @Version 자동 증가
    // ====================================================================

    @Test
    @Order(8)
    @DisplayName("[@Version] Framework 수정 시 version 자동 증가 (0 → 1)")
    @Transactional
    void testVersionAutoIncrement() {
        Framework fw = frameworkRepository.save(
                Framework.builder().name("V FW").description("초기").build());
        assertThat(fw.getVersion()).isEqualTo(0L);

        entityManager.flush();
        entityManager.clear();

        // 같은 트랜잭션 안에서 reload + update + flush
        Framework reloaded = frameworkRepository.findById(fw.getId()).orElseThrow();
        reloaded.update("V FW v2", "변경됨");
        frameworkRepository.save(reloaded);

        entityManager.flush();
        entityManager.clear();

        Framework after = frameworkRepository.findById(fw.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(1L);
        assertThat(after.getName()).isEqualTo("V FW v2");

        System.out.println("✅ [@Version] 수정 시 version 0 → 1 자동 증가");
    }

    // ====================================================================
    // 9. Repository — leaf 한정 조회
    // ====================================================================

    @Test
    @Order(9)
    @DisplayName("[Repository] leaf 한정 / category 한정 분리 조회")
    @Transactional
    void testLeafOnlyQuery() {
        Framework fw = frameworkRepository.save(Framework.builder().name("Q FW").build());
        ControlNode cat = saveCategory(fw, null, "1", "분류", 1);
        saveControl(fw, cat, "1.1", "통제 A", 2);
        saveControl(fw, cat, "1.2", "통제 B", 2);

        List<ControlNode> leaves = controlNodeRepository
                .findByFrameworkIdAndNodeTypeOrderByDisplayOrderAsc(fw.getId(), NodeType.control);
        List<ControlNode> categories = controlNodeRepository
                .findByFrameworkIdAndNodeTypeOrderByDisplayOrderAsc(fw.getId(), NodeType.category);

        assertThat(leaves).hasSize(2);
        assertThat(leaves).allMatch(ControlNode::isLeaf);
        assertThat(categories).hasSize(1);
        assertThat(categories.get(0).isCategory()).isTrue();

        System.out.println("✅ [Repository] leaf/category 분리 조회 정상");
    }

    // ====================================================================
    // 10. Repository — 재귀 CTE
    // ====================================================================

    @Test
    @Order(10)
    @DisplayName("[Repository] findAllDescendants 재귀 CTE — 자손 N단 일괄 반환")
    @Transactional
    void testRecursiveDescendants() {
        Framework fw = frameworkRepository.save(Framework.builder().name("R FW").build());

        // 트리:
        //  d1 (depth=1)
        //   ├─ d2 (depth=2)
        //   │   └─ d3 (depth=3)
        //   │       ├─ leaf 1.1.1.1 (depth=4)
        //   │       └─ leaf 1.1.1.2 (depth=4)
        //   └─ leaf 1.2 sibling (depth=2)
        ControlNode d1 = saveCategory(fw, null, "1",       "L1", 1);
        ControlNode d2 = saveCategory(fw, d1,   "1.1",     "L2", 2);
        ControlNode d3 = saveCategory(fw, d2,   "1.1.1",   "L3", 3);
        saveControl (fw, d3,   "1.1.1.1", "L4 leaf",          4);
        saveControl (fw, d3,   "1.1.1.2", "L4 leaf 2",        4);
        saveControl (fw, d1,   "1.2",     "L2 sibling leaf",  2);

        entityManager.flush();
        entityManager.clear();

        List<ControlNode> descendants = controlNodeRepository.findAllDescendants(d1.getId());

        // d1 자체는 제외, 자손 5개 (d2, d3, 1.1.1.1, 1.1.1.2, 1.2 sibling)
        assertThat(descendants).hasSize(5);
        // 정렬 (depth ASC) — 첫 행은 depth=2
        assertThat(descendants.get(0).getDepth()).isEqualTo(2);
        // 마지막 행은 depth=4 leaf 중 하나
        assertThat(descendants.get(descendants.size() - 1).getDepth()).isEqualTo(4);

        System.out.println("✅ [Repository] 재귀 CTE 자손 일괄 조회 (5건, 4단 깊이)");
    }

    // ====================================================================
    // 헬퍼
    // ====================================================================

    private ControlNode saveCategory(Framework fw, ControlNode parent,
                                      String code, String name, int depth) {
        return controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(parent).nodeType(NodeType.category)
                .code(code).name(name)
                .displayOrder(0).depth(depth).build());
    }

    private ControlNode saveControl(Framework fw, ControlNode parent,
                                     String code, String name, int depth) {
        return controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(parent).nodeType(NodeType.control)
                .code(code).name(name)
                .displayOrder(0).depth(depth).build());
    }
}