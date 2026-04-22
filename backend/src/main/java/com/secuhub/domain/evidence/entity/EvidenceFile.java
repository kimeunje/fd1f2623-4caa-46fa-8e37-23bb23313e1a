package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import com.secuhub.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "evidence_files", indexes = {
        @Index(name = "idx_evidence_files_review_status", columnList = "review_status"),
        @Index(name = "idx_evidence_files_reviewed_by", columnList = "reviewed_by"),
        @Index(name = "idx_evidence_files_uploaded_by", columnList = "uploaded_by")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class EvidenceFile extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_type_id", nullable = false)
    private EvidenceType evidenceType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id")
    private JobExecution execution;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(name = "file_size", nullable = false)
    @Builder.Default
    private Long fileSize = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "collection_method", nullable = false, length = 20)
    @Builder.Default
    private CollectionMethod collectionMethod = CollectionMethod.manual;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    // ========================================================================
    // v11: 승인 플로우 (Phase 5-1 신규)
    // ========================================================================

    /**
     * 업로드한 사용자 (관리자 또는 담당자).
     * Phase 2 까지의 기존 행은 NULL (uploader 정보 없음 — 관리자로 가정).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    /**
     * 담당자가 제출 시 남기는 메모. 관리자 검토 시 참고용.
     */
    @Column(name = "submit_note", columnDefinition = "TEXT")
    private String submitNote;

    /**
     * 검토 상태.
     * - 관리자 직접 업로드 / 자동수집 → auto_approved (이 엔티티의 builder 기본값)
     * - 담당자 업로드 → 서비스에서 .reviewStatus(ReviewStatus.pending) 명시 설정 (Phase 5-4)
     * - 기존 Phase 2 행 → DB DEFAULT 'auto_approved' 로 자동 반영
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    @Builder.Default
    private ReviewStatus reviewStatus = ReviewStatus.auto_approved;

    /**
     * 검토자 (관리자). pending 상태에서는 NULL.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    /**
     * 검토 코멘트. 반려(rejected) 시 필수 — 애플리케이션 레이어에서 검증 (Phase 5-4).
     */
    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    /**
     * 검토 일시. pending 상태에서는 NULL.
     */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    // ========================================================================
    // 도메인 메서드 (Phase 5-4 에서 활용)
    // ========================================================================

    /**
     * 관리자 승인 처리.
     */
    public void approve(User reviewer, String note) {
        this.reviewStatus = ReviewStatus.approved;
        this.reviewedBy = reviewer;
        this.reviewNote = note;          // 승인 시 코멘트는 선택
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * 관리자 반려 처리. note 는 호출자가 빈 값 검증 후 전달해야 함.
     */
    public void reject(User reviewer, String note) {
        this.reviewStatus = ReviewStatus.rejected;
        this.reviewedBy = reviewer;
        this.reviewNote = note;          // 반려 시 필수 (서비스에서 검증)
        this.reviewedAt = LocalDateTime.now();
    }
}