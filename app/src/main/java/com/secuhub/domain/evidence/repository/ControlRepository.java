package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.Control;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ControlRepository extends JpaRepository<Control, Long> {

    List<Control> findByFrameworkIdOrderByCodeAsc(Long frameworkId);

    @Query("""
        SELECT c FROM Control c
        LEFT JOIN FETCH c.evidenceTypes
        WHERE c.framework.id = :frameworkId
        ORDER BY c.code ASC
        """)
    List<Control> findByFrameworkIdWithEvidenceTypes(@Param("frameworkId") Long frameworkId);
}
