package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.CollectionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CollectionJobRepository extends JpaRepository<CollectionJob, Long> {

    List<CollectionJob> findByIsActiveTrueAndScheduleCronIsNotNull();

    /**
     * Framework 단위 수집 작업 수 — Phase 5-3 FrameworkListView 집계용.
     * collection_jobs.evidence_type_id → evidence_types.control_id → controls.framework_id 조인.
     */
    @Query("""
        SELECT COUNT(j) FROM CollectionJob j
        WHERE j.evidenceType.control.framework.id = :frameworkId
        """)
    long countByFrameworkId(@Param("frameworkId") Long frameworkId);

    /**
     * 특정 통제 (leaf) 산하 EvidenceType 에 바인딩된 CollectionJob 수.
     *
     * <p>Phase 5-14e impact-summary 의 {@code jobCount} 필드용. spec §3.3.1.5 의
     * "자동 수집 작업 N개" 의미. {@code is_active} 무관 (활성 / 비활성 모두 포함),
     * 실행 이력 무관.</p>
     *
     * <p>{@code controlId} 의미는 spec §3.3.1.5 (5-14e Q1=A): leaf control_node.id.
     * 패턴은 {@link #countByFrameworkId(Long)} 와 동일 — framework 대신 control 단위.</p>
     *
     * <p>{@code j.evidenceType} 가 NULL 인 전역 작업은 자연스럽게 제외 (LEFT JOIN 아님).</p>
     */
    @Query("""
        SELECT COUNT(j) FROM CollectionJob j
        WHERE j.evidenceType.control.id = :controlId
        """)
    long countByControlId(@Param("controlId") Long controlId);
}