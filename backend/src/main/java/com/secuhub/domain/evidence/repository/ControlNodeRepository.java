package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.NodeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * v14 Phase 5-14a — control_nodes 트리 조회 Repository.
 *
 * <p>주요 사용처 (5-14b 이후 phase 에서 활용):</p>
 * <ul>
 *   <li>{@link #findByFrameworkIdOrderByDepthAscDisplayOrderAsc} —
 *       5-14c {@code GET /tree} 의 평탄화 응답 빌드용</li>
 *   <li>{@link #findByFrameworkIdAndNodeTypeOrderByDisplayOrderAsc} —
 *       5-14f Control.java 위임 구현 (leaf 한정 조회) 용</li>
 *   <li>{@link #findByParentIdOrderByDisplayOrderAsc} —
 *       5-14d 코드 자동 추론 (sibling max+1) / 직속 자식 조회용</li>
 *   <li>{@link #findByFrameworkIdAndParentIsNullOrderByDisplayOrderAsc} —
 *       빈 Framework 검사 / 새 대분류 sibling 추론용</li>
 *   <li>{@link #findAllDescendants} —
 *       5-14d cascading delete 사전 카운트 / 자손 일괄 처리용 (재귀 CTE)</li>
 *   <li>{@link #countByFrameworkIdAndNodeType} —
 *       Framework 헤더 카운트 표시 (5-14g) 용</li>
 *   <li>{@link #findByFrameworkIdInOrderByDepthAscDisplayOrderAsc} —
 *       v16.4a Dashboard 의 ancestors path 빌드용 (다중 framework)</li>
 * </ul>
 *
 * <p>재귀 CTE 호환성: H2 2.2+ / MariaDB 10.2.2+ 모두 표준 {@code WITH RECURSIVE} 지원.
 * <b>단, H2 는 CTE 정의에 컬럼 리스트 명시를 요구</b> (MariaDB 는 선택, 둘 다 호환되도록 컬럼 리스트 명시).</p>
 */
public interface ControlNodeRepository extends JpaRepository<ControlNode, Long> {

    /**
     * Framework 의 모든 노드를 평탄화하여 정렬 반환.
     *
     * <p>정렬: {@code depth ASC, displayOrder ASC} — 부모가 자식보다 먼저 등장 보장.
     * {@code GET /tree} 응답에서 클라이언트가 한 번의 순회로 트리를 reconstruct 할 수 있도록.</p>
     *
     * <p><b>v14.3 (Phase 5-14c) 변경</b>: 본문을 derived query 에서 JPQL 로 전환 +
     * {@code LEFT JOIN FETCH cn.parent} 추가. NodeSummary 매핑 시
     * {@code cn.getParent().getId()} 호출의 lazy load N+1 차단을 위해.
     * 메서드 이름 / 시그니처 / 정렬은 동일 — 호출 측 호환 그대로.</p>
     *
     * <p>spec §3.3.1.4 의 (depth, parent.id NULL FIRST, displayOrder) 정렬 중
     * "parent.id" 단계 정렬은 {@link com.secuhub.domain.evidence.service.TreeService}
     * 가 JVM-side Comparator 로 추가 적용 — JPQL ORDER BY 의 NULL 처리 환경
     * 의존성을 회피하기 위함.</p>
     */
    @Query("""
        SELECT cn FROM ControlNode cn
        LEFT JOIN FETCH cn.parent
        WHERE cn.framework.id = :frameworkId
        ORDER BY cn.depth ASC, cn.displayOrder ASC
    """)
    List<ControlNode> findByFrameworkIdOrderByDepthAscDisplayOrderAsc(@Param("frameworkId") Long frameworkId);

    /**
     * Framework 안에서 특정 nodeType 만 반환.
     * 외부 API 호환을 위한 leaf 한정 조회 등에 사용.
     */
    List<ControlNode> findByFrameworkIdAndNodeTypeOrderByDisplayOrderAsc(Long frameworkId, NodeType nodeType);

    /**
     * 직속 자식만 반환 (parent_id 일치).
     * 코드 자동 추론, 자식 노드 카운트 등에 사용.
     */
    List<ControlNode> findByParentIdOrderByDisplayOrderAsc(Long parentId);

    /**
     * Framework 직속 자식 (parent_id IS NULL, depth=1) 만 반환.
     * 빈 Framework 검사 / 새 대분류 sibling max+1 추론용.
     */
    List<ControlNode> findByFrameworkIdAndParentIsNullOrderByDisplayOrderAsc(Long frameworkId);

    /**
     * 자손 노드 일괄 조회 (재귀 CTE).
     *
     * <p>cascading delete 사전 카운트, 분류 삭제 시 자손 영향 분석 등에 사용.
     * 결과 정렬: {@code depth ASC, display_order ASC} (부모 먼저).</p>
     *
     * <p>루트 자체는 결과에서 제외.</p>
     *
     * <p><b>H2 호환을 위해 CTE 정의에 컬럼 리스트 명시</b>.
     * H2 2.2.x 는 {@code WITH RECURSIVE name(col1, col2, ...) AS (...)} 형식 강제.
     * MariaDB 도 이 형식 동등 호환.</p>
     */
    @Query(value = """
        WITH RECURSIVE descendants
            (id, framework_id, parent_id, node_type, code, name,
             description, display_order, depth, created_at, updated_at) AS (
          SELECT id, framework_id, parent_id, node_type, code, name,
                 description, display_order, depth, created_at, updated_at
            FROM control_nodes
           WHERE id = :rootId
          UNION ALL
          SELECT cn.id, cn.framework_id, cn.parent_id, cn.node_type, cn.code, cn.name,
                 cn.description, cn.display_order, cn.depth, cn.created_at, cn.updated_at
            FROM control_nodes cn
            JOIN descendants d ON cn.parent_id = d.id
        )
        SELECT id, framework_id, parent_id, node_type, code, name,
               description, display_order, depth, created_at, updated_at
          FROM descendants
         WHERE id <> :rootId
         ORDER BY depth ASC, display_order ASC
        """, nativeQuery = true)
    List<ControlNode> findAllDescendants(@Param("rootId") Long rootId);

    /**
     * Framework 안의 nodeType 별 카운트.
     * Framework 헤더의 "통제 N" 표시 (5-14g) 등에 사용.
     */
    long countByFrameworkIdAndNodeType(Long frameworkId, NodeType nodeType);

    /**
     * Framework 와 무관하게 nodeType 별 전역 카운트.
     * v15.3 5-15b 부터 EvidenceFileService.getStats() 의 controlCoverage
     * 계산용 (이전 controlRepository.count() 대체).
     */
    long countByNodeType(NodeType nodeType);

    // ====================================================================
    // v16.4a (Dashboard 위젯) — 다중 framework ancestors path 빌드용
    // ====================================================================

    /**
     * v16.4a (Dashboard) — 여러 framework 의 모든 노드 일괄 조회.
     *
     * <p>DashboardService 의 pending top 10 처리 시 ancestors path 빌드 재료. 각
     * pending 파일이 속한 framework 들의 모든 노드를 한 번에 조회 →
     * {@code byId 맵} 으로 in-memory traversal (FrameworkExportService 의
     * {@link #findByFrameworkIdOrderByDepthAscDisplayOrderAsc} + buildAncestors 패턴
     * 의 다중 framework 버전).</p>
     *
     * <p>{@code LEFT JOIN FETCH cn.parent} 로 첫 단계 parent hydrate. 추가 단계는
     * service 측 byId 맵에서 in-memory 조회.</p>
     *
     * <p>정렬: 단일 framework 버전과 동일 ({@code depth ASC, displayOrder ASC}).
     * framework 별 그룹핑은 service 측 byId 맵으로 자연 처리 — JPQL ORDER BY 에
     * framework.id 추가 시 LEFT JOIN FETCH 와 충돌 가능성 회피.</p>
     */
    @Query("""
        SELECT cn FROM ControlNode cn
        LEFT JOIN FETCH cn.parent
        WHERE cn.framework.id IN :frameworkIds
        ORDER BY cn.depth ASC, cn.displayOrder ASC
        """)
    List<ControlNode> findByFrameworkIdInOrderByDepthAscDisplayOrderAsc(
            @Param("frameworkIds") List<Long> frameworkIds);


    @Query("""
    SELECT cn.id, p.id, cn.code, cn.name, cn.depth, cn.displayOrder
      FROM ControlNode cn
      LEFT JOIN cn.parent p
     WHERE cn.framework.id = :frameworkId
     ORDER BY cn.depth ASC, cn.displayOrder ASC
    """)
    List<Object[]> findReviewTreeRows(@Param("frameworkId") Long frameworkId);
}