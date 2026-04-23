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
}