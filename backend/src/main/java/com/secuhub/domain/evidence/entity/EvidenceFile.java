package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import com.secuhub.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 증빙 파일 (Evidence File) 엔티티.
 *
 * <h3>v18.3 — {@code @OnDelete(CASCADE)} 추가 (L_SPEC_SCHEMA_MISMATCH 종결)</h3>
 * <p>EvidenceType 의 {@code @OneToMany(cascade=ALL, orphanRemoval=true)} 는 Hibernate
 * 레벨 매핑이고, DB FK 자체는 default RESTRICT 였음 (v18.2 부산 발견). Hibernate
 * cascade graph 가 silent skip 되는 사례 (L_HIBERNATE_CASCADE_SILENT) 에서는 DB FK
 * 가 정공 — native SQL DELETE 가 cascade chain 처리.</p>
 *
 * <h3>v18.6a — Evidence Asset 신규 채널 (§2.4 진입)</h3>
 * <p>새 {@code asset_id} FK 추가 (transitional NULLABLE). 운영 검증 중 발견된 silent
 * risk 2건 (중복 파일 / Framework 복사 dangling) 대응. {@code filePath} 컬럼은
 * transitional 보존 (v18.6b 마이그레이션 후 폐기). 자세한 정책은 {@link EvidenceAsset}
 * 참조.</p>
 */
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

    /**
     * v18.3 — {@code @OnDelete(CASCADE)} 추가.
     * EvidenceType 삭제 시 매달린 EvidenceFile 도 자동 삭제.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_type_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private EvidenceType evidenceType;

    /**
     * v18.6a — Evidence Asset 신규 채널 (§2.4 진입).
     *
     * <p>같은 asset 을 여러 EvidenceFile 이 참조 (N:M). transitional NULLABLE
     * (v18.6b 마이그레이션 후 NOT NULL ALTER 예정). asset_id NULL 시 옛
     * {@link #filePath} 컬럼 fallback ({@link #resolveFilePath()} 참조).</p>
     *
     * <p>DB FK ON DELETE RESTRICT (Flyway V2) — asset 사용 중 직접 삭제 차단.
     * GC 는 service 레벨 ({@code EvidenceFileService.delete} 의 reference_count
     * 검사, Q10). entity 에서 {@code @OnDelete} 명시 안 함 — Hibernate default
     * (NO ACTION) 와 DB RESTRICT 동작 같음.</p>
     *
     * <p>v18.3 의 EvidenceType cascade chain 영향 없음 — EvidenceType 삭제 →
     * EvidenceFile cascade 삭제는 본 link 만 정리, asset 자체는 별도 GC.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id")
    private EvidenceAsset asset;

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

    // ========================================================================
    // v18.6a — Asset 우선 / filePath fallback helper
    // ========================================================================

    /**
     * 물리 파일 경로 — asset_id 있으면 asset.filePath, 없으면 옛 filePath (transitional).
     *
     * <p>v18.6a 시점 양쪽 모두 운영:</p>
     * <ul>
     *   <li>신규 업로드 (FE upload + Script auto collect) = asset != null → asset.filePath</li>
     *   <li>옛 데이터 (v18.5 시점 등록) = asset == null + filePath 유지 → 옛 filePath</li>
     * </ul>
     *
     * <p>v18.6b 마이그레이션 후: 모든 EvidenceFile 이 asset != null, filePath 컬럼 폐기.
     * 본 helper 와 filePath 필드 모두 제거 예정.</p>
     *
     * @return 절대 경로 (asset 우선, 옛 filePath fallback)
     */
    public String resolveFilePath() {
        return (asset != null) ? asset.getFilePath() : filePath;
    }
}