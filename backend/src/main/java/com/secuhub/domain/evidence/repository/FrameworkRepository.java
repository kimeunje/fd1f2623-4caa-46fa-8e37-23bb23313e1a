package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.FrameworkStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FrameworkRepository extends JpaRepository<Framework, Long> {

    // v11: 상속 관계 조회 — 특정 Framework를 부모로 삼는 자식 Framework 목록
    List<Framework> findByParentFrameworkId(Long parentFrameworkId);

    // v11: 상태별 조회 — Framework 목록 페이지에서 active 만 표시
    List<Framework> findByStatus(FrameworkStatus status);

    List<Framework> findByStatusOrderByCreatedAtDesc(FrameworkStatus status);

    @Query("""
        SELECT f.id, f.name FROM Framework f
        WHERE f.status = com.secuhub.domain.evidence.entity.FrameworkStatus.active
        ORDER BY f.name ASC
        """)
    List<Object[]> findActiveIdNameForReview();

    @Query("SELECT f.id, f.name FROM Framework f WHERE f.id = :id")
    List<Object[]> findIdNameByIdForReview(@Param("id") Long id);
}