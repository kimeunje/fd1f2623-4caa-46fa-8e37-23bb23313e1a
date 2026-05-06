package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.EvidenceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * EvidenceType 영속화.
 *
 * <h3>v14 Phase 5-14f — 의미 변경 (역사 보존)</h3>
 * <p>{@link EvidenceType#getControlNode()} 의 타입이 v14 5-14f 에서 {@code Control}
 * → {@link com.secuhub.domain.evidence.entity.ControlNode} 로 변경되어 Repository
 * 의 의미가 자연 변경됨. 호출 측은 ControlNode.id 를 넘기면 자연 매칭. 5-14e 의
 * impact-summary 가 자연 정상화.</p>
 *
 * <h3>v14 Phase 5-14g (β) — 카운트 메서드 추가</h3>
 * <p>{@link #countCollectedGroupByControlNodeIdInFramework(Long)} 추가. ControlsView
 * 트리 본문의 진행바 ({@code N/M}) 와 "완료/진행중/미수집" 상태 derive 용. 5-14f 의
 * evidence type 카운트 패턴과 동일 (explicit JOIN, N+1 회피).</p>
 *
 * <h3>v15 Phase 5-15c (v15.7) — Q1=B + Q5=A 정합</h3>
 * <p>EvidenceType 의 자바 필드명 {@code control} → {@code controlNode} 변경 (v15.7
 * Q1=B) 정합:</p>
 * <ul>
 *   <li>Spring Data derived query: {@code findByControlId} → {@link #findByControlNodeId(Long)}
 *       (FORCED — derived parser 가 필드 path 정합 메서드명 요구)</li>
 *   <li>@Query JPQL: {@code et.control cn} → {@code et.controlNode cn} (FORCED)</li>
 *   <li>@Query 메서드명 일관 rename (Q5=A): {@code countGroupByControlIdInFramework}
 *       → {@link #countGroupByControlNodeIdInFramework(Long)}, 동일 패턴</li>
 *   <li>@Param 명: {@code controlId} → {@code controlNodeId} (Repository layer 명명 정합)</li>
 * </ul>
 *
 * <h3>v16.4a (Dashboard) — Framework 진척 계산</h3>
 * <p>{@link #findIdsByFrameworkId(Long)} 신규 — DashboardService 의 진척 분모 계산용.</p>
 */
public interface EvidenceTypeRepository extends JpaRepository<EvidenceType, Long> {

    /**
     * 5-14f 후: controlNodeId 는 leaf {@link com.secuhub.domain.evidence.entity.ControlNode}
     * 의 id (또는 v15 hybrid 시점에는 hybrid 노드 포함).
     *
     * <p>v15.7: derived query 메서드명 {@code findByControlId} → {@code findByControlNodeId}
     * (EvidenceType.controlNode 필드 path 정합).</p>
     */
    List<EvidenceType> findByControlNodeId(Long controlNodeId);

    // v11: 담당자 본인의 증빙 유형 조회 — "내 할 일" 페이지 기반 (Phase 5-5)
    List<EvidenceType> findByOwnerUserId(Long ownerUserId);

    // ====================================================================
    // v14 Phase 5-14f — leaf 카운트 본격 집계 (TreeService.getTree 의 NodeSummary
    //                   빌드 시 leaf 의 evidenceTypeCount 채움용)
    // ====================================================================

    /**
     * 한 Framework 안의 노드 별 evidence_types 카운트를 한 번에 집계.
     *
     * <p>응답: {@code Object[]} 의 배열. 각 항목 {@code [Long controlNodeId, Long count]}.
     * 0 개의 evidence_types 를 가진 노드는 결과에 포함 안 됨 (외부 코드에서
     * defaultIfMissing 처리).</p>
     *
     * <p>v14 Phase 5-14f: implicit 다단 path ({@code et.control.framework.id}) 대신
     * explicit {@code JOIN} 사용 — Hibernate 6 의 nested association path resolution
     * 안정성 확보. 같은 SQL 결과 (INNER JOIN evidence_types → control_nodes → frameworks).</p>
     *
     * <p>JPQL 의 {@code COUNT(et)} 는 {@code Long} 반환. {@code cn.id} 는 ControlNode.id.</p>
     *
     * <p>TreeService 가 노드마다 호출하지 말고 (N+1 회피) Framework 단위로 1회 호출
     * 후 Map<controlNodeId, count> 빌드.</p>
     *
     * <p>v15.7: 메서드명 + JPQL field path + @Param 모두 controlNode 정합 (Q1=B + Q5=A).</p>
     *
     * @param frameworkId Framework id
     * @return [controlNodeId, count] 배열의 리스트
     */
    @Query("""
            SELECT cn.id, COUNT(et)
              FROM EvidenceType et
              JOIN et.controlNode cn
             WHERE cn.framework.id = :frameworkId
             GROUP BY cn.id
            """)
    List<Object[]> countGroupByControlNodeIdInFramework(@Param("frameworkId") Long frameworkId);

    // ====================================================================
    // v14 Phase 5-14g (β) — leaf 별 collected count (evidence_types 중 1개 이상의
    //                       evidence_files 를 가진 type 의 distinct 수)
    // ====================================================================

    /**
     * 한 Framework 안의 노드 별 "수집된 evidence_types" 카운트를 한 번에 집계.
     *
     * <p>정의: 노드에 매달린 evidence_types 중 evidence_files 가 1개 이상 있는 type 의
     * distinct 수. ControlsView §3.3 의 6컬럼 진행바 (`{collected}/{total}`) 와
     * 상태 배지 derive ("완료/진행중/미수집") 용.</p>
     *
     * <p>응답: {@code Object[]} 의 배열. 각 항목 {@code [Long controlNodeId, Long count]}.
     * collected 가 0 인 노드 (모든 evidence_types 미수집 또는 evidence_types 자체가 0개) 는
     * 결과에 포함 안 됨 (호출 측 defaultIfMissing 처리).</p>
     *
     * <p>구현: explicit JOIN 으로 Hibernate 6 의 nested association path resolution 안전.
     * {@code DISTINCT} 으로 같은 et 가 여러 ef 를 가질 때 중복 카운트 방지. 같은 SQL 결과:
     * {@code INNER JOIN evidence_files → evidence_types → control_nodes → frameworks}
     * + {@code GROUP BY cn.id} + {@code COUNT(DISTINCT et.id)}.</p>
     *
     * <p>TreeService 가 노드마다 호출하지 말고 (N+1 회피) Framework 단위로 1회 호출
     * 후 Map<controlNodeId, count> 빌드. 5-14f 의 두 카운트 메서드 옆에 자연 동거.</p>
     *
     * <p>v15.7: 메서드명 + JPQL 정합 갱신.</p>
     *
     * @param frameworkId Framework id
     * @return [controlNodeId, distinct evidence_type count] 배열의 리스트
     */
    @Query("""
            SELECT cn.id, COUNT(DISTINCT et.id)
              FROM EvidenceFile ef
              JOIN ef.evidenceType et
              JOIN et.controlNode cn
             WHERE cn.framework.id = :frameworkId
             GROUP BY cn.id
            """)
    List<Object[]> countCollectedGroupByControlNodeIdInFramework(@Param("frameworkId") Long frameworkId);

    // ====================================================================
    // v16.4a (Dashboard 위젯) — Framework 진척 분모 계산
    // ====================================================================

    /**
     * v16.4a (Dashboard) — 한 Framework 의 모든 evidence_types id 만 조회.
     *
     * <p>DashboardService.computeProgress 의 진척 분모 (totalEvidenceTypes) 계산용.
     * entity 부담 회피 — id 만 select.</p>
     *
     * <p>spec §3.8.1 의 FrameworkProgress.totalEvidenceTypes 정합.</p>
     *
     * <p>v15.7 Q1=B 정합: {@code et.controlNode.framework.id} navigation.</p>
     */
    @Query("""
        SELECT et.id FROM EvidenceType et
        WHERE et.controlNode.framework.id = :frameworkId
        """)
    List<Long> findIdsByFrameworkId(@Param("frameworkId") Long frameworkId);
}