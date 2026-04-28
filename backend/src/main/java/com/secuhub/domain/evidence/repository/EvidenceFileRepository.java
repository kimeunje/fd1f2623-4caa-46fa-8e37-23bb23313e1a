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

    // ========================================================================
    // v14 Phase 5-14e — leaf 통제 코드 변경 사전 경고 (impact-summary)
    // ========================================================================

    /**
     * 특정 통제 (leaf) 에 매달린 모든 EvidenceFile 수.
     *
     * <p>Phase 5-14e impact-summary 의 {@code evidenceFileCount} 필드용.
     * version / review_status 무관, 모든 행 카운트.</p>
     *
     * <p>{@code controlId} 의미는 spec §3.3.1.5 (5-14e Q1=A): leaf control_node.id.
     * V6 prod 후 {@code evidence_types.control_id} 가 leaf control_node.id 로 이주됨
     * (+1,000,000 offset). dev/test 에서는 매핑 이주 (5-14f) 전까지 매칭 0 자연.</p>
     *
     * <p>패턴은 기존 {@link #countByControlIdAndReviewStatus(Long, ReviewStatus)} 와 동일
     * — review_status 필터만 제거.</p>
     */
    @Query("""
        SELECT COUNT(ef) FROM EvidenceFile ef
        WHERE ef.evidenceType.control.id = :controlId
        """)
    long countByControlId(@Param("controlId") Long controlId);

    /**
     * 특정 통제 (leaf) 에 매달린 EvidenceFile 중 관리자가 명시 검토한 (reviewed_at IS NOT NULL) 수.
     *
     * <p>Phase 5-14e impact-summary 의 {@code reviewCount} 필드용. spec §3.3.1.5 의
     * "검토 이력 N건" 의미. {@code review_status IN ('approved', 'rejected')} 인 파일이
     * 모두 reviewed_at 가짐 (Phase 5-4 EvidenceApprovalService 가 set). pending /
     * auto_approved 는 reviewed_at 가 NULL 이라 제외.</p>
     *
     * <p>{@code controlId} 의미는 {@link #countByControlId(Long)} 와 동일.</p>
     */
    @Query("""
        SELECT COUNT(ef) FROM EvidenceFile ef
        WHERE ef.evidenceType.control.id = :controlId
          AND ef.reviewedAt IS NOT NULL
        """)
    long countReviewedByControlId(@Param("controlId") Long controlId);
}