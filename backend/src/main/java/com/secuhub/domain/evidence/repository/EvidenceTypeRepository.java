package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.EvidenceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * EvidenceType 영속화.
 *
 * <h3>v14 Phase 5-14f — 의미 변경</h3>
 * <p>{@link #findByControlId(Long)} 의 시그니처는 그대로지만, {@link EvidenceType#getControl()}
 * 의 타입이 {@link com.secuhub.domain.evidence.entity.Control} → {@link
 * com.secuhub.domain.evidence.entity.ControlNode} 로 변경되어 의미가 자연 변경됨.
 * 호출 측은 ControlNode.id 를 넘기면 자연 매칭. 5-14e 의 impact-summary 가 자연 정상화.</p>
 */
public interface EvidenceTypeRepository extends JpaRepository<EvidenceType, Long> {

    /**
     * 5-14f 후: controlId 는 leaf {@link com.secuhub.domain.evidence.entity.ControlNode} 의 id.
     * 시그니처 (메서드명 / 파라미터 / 반환 타입) 보존.
     */
    List<EvidenceType> findByControlId(Long controlId);

    // v11: 담당자 본인의 증빙 유형 조회 — "내 할 일" 페이지 기반 (Phase 5-5)
    List<EvidenceType> findByOwnerUserId(Long ownerUserId);

    // ====================================================================
    // v14 Phase 5-14f — leaf 카운트 본격 집계 (TreeService.getTree 의 NodeSummary
    //                   빌드 시 leaf 의 evidenceTypeCount 채움용)
    // ====================================================================

    /**
     * 한 Framework 안의 leaf 별 evidence_types 카운트를 한 번에 집계.
     *
     * <p>응답: {@code Object[]} 의 배열. 각 항목 {@code [Long controlNodeId, Long count]}.
     * leaf 가 0 개의 evidence_types 를 가진 경우는 결과에 포함 안 됨 (외부 코드에서
     * defaultIfMissing 처리).</p>
     *
     * <p>v14 Phase 5-14f: implicit 다단 path ({@code et.control.framework.id}) 대신
     * explicit {@code JOIN} 사용 — Hibernate 6 의 nested association path resolution
     * 안정성 확보. 같은 SQL 결과 (INNER JOIN evidence_types → control_nodes → frameworks).</p>
     *
     * <p>JPQL 의 {@code COUNT(et)} 는 {@code Long} 반환. {@code cn.id} 는 ControlNode.id.</p>
     *
     * <p>TreeService 가 leaf 마다 호출하지 말고 (N+1 회피) Framework 단위로 1회 호출
     * 후 Map<controlNodeId, count> 빌드.</p>
     *
     * @param frameworkId Framework id
     * @return [controlNodeId, count] 배열의 리스트
     */
    @Query("""
            SELECT cn.id, COUNT(et)
              FROM EvidenceType et
              JOIN et.control cn
             WHERE cn.framework.id = :frameworkId
             GROUP BY cn.id
            """)
    List<Object[]> countGroupByControlIdInFramework(@Param("frameworkId") Long frameworkId);
}