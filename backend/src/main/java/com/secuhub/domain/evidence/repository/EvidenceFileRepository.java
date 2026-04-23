package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.EvidenceFile;
import com.secuhub.domain.evidence.entity.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EvidenceFileRepository extends JpaRepository<EvidenceFile, Long> {

    List<EvidenceFile> findByEvidenceTypeIdOrderByVersionDesc(Long evidenceTypeId);

    @Query("SELECT MAX(ef.version) FROM EvidenceFile ef WHERE ef.evidenceType.id = :evidenceTypeId")
    Optional<Integer> findMaxVersionByEvidenceTypeId(@Param("evidenceTypeId") Long evidenceTypeId);

    // ========================================================================
    // v11: 승인 플로우 (Phase 5-1 / Phase 5-4 활용)
    // ========================================================================

    /**
     * 특정 상태의 파일 목록 (관리자 승인 대기 조회용).
     * Framework 상세 페이지에서 증빙 유형 카드 배지 집계에도 사용.
     */
    List<EvidenceFile> findByReviewStatus(ReviewStatus reviewStatus);

    /**
     * 페이징 지원 — 대시보드 "내 승인 대기" 목록 (Phase 5-8).
     */
    Page<EvidenceFile> findByReviewStatus(ReviewStatus reviewStatus, Pageable pageable);

    /**
     * 상태별 카운트 — KPI 카드용.
     */
    long countByReviewStatus(ReviewStatus reviewStatus);

    /**
     * 특정 담당자가 업로드한 파일 — "내 할 일" 내 검토중/완료 섹션 기반 (Phase 5-5).
     */
    List<EvidenceFile> findByUploadedByIdOrderByCreatedAtDesc(Long uploadedById);

    /**
     * 특정 Framework 내 승인 대기 건수 — Framework 목록 페이지 배지 (Phase 5-3).
     * evidence_types → controls → frameworks 조인으로 집계.
     */
    @Query("""
        SELECT COUNT(ef) FROM EvidenceFile ef
        WHERE ef.reviewStatus = :status
          AND ef.evidenceType.control.framework.id = :frameworkId
        """)
    long countByFrameworkIdAndReviewStatus(@Param("frameworkId") Long frameworkId,
                                           @Param("status") ReviewStatus status);

    /**
     * 특정 Control 내 승인 대기 건수 — Framework 상세 페이지의 통제 항목 행 배지 (Phase 5-9).
     * evidence_types → controls 조인으로 집계.
     *
     * <p>패턴은 {@link #countByFrameworkIdAndReviewStatus(Long, ReviewStatus)} 와 동일.</p>
     */
    @Query("""
        SELECT COUNT(ef) FROM EvidenceFile ef
        WHERE ef.reviewStatus = :status
          AND ef.evidenceType.control.id = :controlId
        """)
    long countByControlIdAndReviewStatus(@Param("controlId") Long controlId,
                                         @Param("status") ReviewStatus status);
}