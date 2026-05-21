package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.CollectionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * CollectionJob 영속화.
 *
 * <h3>v15 Phase 5-15c (v15.7) — Q1=B + Q5=A 정합</h3>
 * <p>EvidenceType 의 자바 필드명 변경 정합:</p>
 * <ul>
 *   <li>JPQL navigation {@code j.evidenceType.control.framework.id} → {@code j.evidenceType.controlNode.framework.id}
 *       (Q1=B FORCED)</li>
 *   <li>JPQL navigation {@code j.evidenceType.control.id} → {@code j.evidenceType.controlNode.id}</li>
 *   <li>@Query 메서드명 일관 rename (Q5=A): {@code countByControlId} →
 *       {@link #countByControlNodeId(Long)}, {@code countByControlIds} →
 *       {@link #countByControlNodeIds(List)}</li>
 *   <li>@Param 명: {@code controlId} → {@code controlNodeId} / {@code controlIds}
 *       → {@code controlNodeIds}</li>
 * </ul>
 *
 * <h3>v18.8.7 — 스크립트 삭제 시 사용 중 검사용</h3>
 * <p>{@link #existsByScriptId(Long)} 추가. {@link ScriptManagementService#delete} 가
 * 본 메서드로 "사용 중인 작업이 있으면 거부" 정책 구현 (Q2=안전).</p>
 */
public interface CollectionJobRepository extends JpaRepository<CollectionJob, Long> {

    List<CollectionJob> findByIsActiveTrueAndScheduleCronIsNotNull();

    /**
     * v18.8.7 — 특정 Script 를 참조하는 CollectionJob 이 있는지 검사.
     *
     * <p>스크립트 삭제 시 "사용 중이면 거부" 정책 (Q2=안전) 구현용. legacy scriptPath 만
     * 쓰는 작업은 무관, script_id FK 가 NOT NULL 인 row 만 체크.</p>
     *
     * <p>Spring Data JPA derived query — `Script script` 필드 (`@ManyToOne`) 의 id 자동 매칭.
     * EXISTS 1 LIMIT 1 식으로 최적화됨.</p>
     */
    boolean existsByScriptId(Long scriptId);

    /**
     * Framework 단위 수집 작업 수 — Phase 5-3 FrameworkListView 집계용.
     * collection_jobs.evidence_type_id → evidence_types.control_id → control_nodes.framework_id 조인.
     *
     * <p>v15.7: JPQL 의 {@code j.evidenceType.control.framework.id} →
     * {@code j.evidenceType.controlNode.framework.id} (Q1=B). 메서드명은 "Framework"
     * 기준이라 보존.</p>
     */
    @Query("""
        SELECT COUNT(j) FROM CollectionJob j
        WHERE j.evidenceType.controlNode.framework.id = :frameworkId
        """)
    long countByFrameworkId(@Param("frameworkId") Long frameworkId);

    /**
     * 특정 통제 (또는 hybrid 노드) 산하 EvidenceType 에 바인딩된 CollectionJob 수.
     *
     * <p>Phase 5-14e impact-summary 의 own job 카운트용. spec §3.3.1.5 의 "자동 수집
     * 작업 N개" 의미. {@code is_active} 무관 (활성 / 비활성 모두 포함), 실행 이력 무관.</p>
     *
     * <p>{@code controlNodeId} 의미는 spec §3.3.1.5 (5-14e Q1=A): leaf control_node.id.
     * 패턴은 {@link #countByFrameworkId(Long)} 와 동일 — framework 대신 control_node 단위.</p>
     *
     * <p>{@code j.evidenceType} 가 NULL 인 전역 작업은 자연스럽게 제외 (LEFT JOIN 아님).</p>
     *
     * <p>v15.7: 메서드명 + JPQL + @Param 모두 controlNode 정합.</p>
     */
    @Query("""
        SELECT COUNT(j) FROM CollectionJob j
        WHERE j.evidenceType.controlNode.id = :controlNodeId
        """)
    long countByControlNodeId(@Param("controlNodeId") Long controlNodeId);

    // ====================================================================
    // v15 Phase 5-15a — Hybrid impact-summary 의 자손 카운트 집계
    // ====================================================================

    /**
     * 자손 노드들 산하 EvidenceType 에 바인딩된 CollectionJob 수.
     *
     * <p>Phase 5-15a hybrid impact-summary 의 {@code descendantJobCount} 필드용.
     * {@link #countByControlNodeId(Long)} 의 IN 절 확장 버전.</p>
     *
     * <p>호출 규약: {@code controlNodeIds} 가 비어있을 경우 본 메서드 호출 금지
     * (JPQL {@code IN ()} 의 환경 의존 동작 회피). 호출 측에서 빈 list 검사 후 0 리턴.</p>
     *
     * @param controlNodeIds 자손 ControlNode.id 리스트 (본인 제외)
     */
    @Query("""
        SELECT COUNT(j) FROM CollectionJob j
        WHERE j.evidenceType.controlNode.id IN :controlNodeIds
        """)
    long countByControlNodeIds(@Param("controlNodeIds") List<Long> controlNodeIds);
}