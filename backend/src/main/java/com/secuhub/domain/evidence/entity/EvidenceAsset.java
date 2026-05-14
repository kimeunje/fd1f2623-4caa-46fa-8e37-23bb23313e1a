package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import com.secuhub.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

/**
 * 증빙 자산 (Evidence Asset) entity. v18.6a 신규.
 *
 * <h3>v18.6a — §2.4 "Evidence Library N:N" 진입</h3>
 *
 * <p>운영 검증 중 사용자 발견 silent risk 2건 대응:</p>
 * <ul>
 *   <li>① 같은 파일을 여러 EvidenceType 에 올리면 N copy 누적 (저장 공간 + 일관성)</li>
 *   <li>② Framework 복사 시 {@code EvidenceFile.filePath} 가 원본 경로 → dangling 위험</li>
 * </ul>
 *
 * <p>새 구조: Asset (물리 파일 1:1) + Link (관계 N:M)</p>
 * <ul>
 *   <li>{@code EvidenceAsset} — 물리 파일 (sha256 + filePath + size)</li>
 *   <li>{@code EvidenceFile} — link entity (asset_id FK, v18.6a transitional NULLABLE)</li>
 * </ul>
 *
 * <h3>디렉토리 정책 (Q2=c)</h3>
 * <p>{@code {storage}/assets/{id % 1000}/{id}}</p>
 * <ul>
 *   <li>ID 기반 분산 (1000 sub-dir, ext4 listing 성능 보장)</li>
 *   <li>파일명은 ID 만 (originalFileName 은 DB)</li>
 *   <li>v18.5-platform-c 의 V1 baseline 위 첫 V2 마이그레이션 (L_FLYWAY_BASE_SCHEMA_GAP)</li>
 * </ul>
 *
 * <h3>중복 정책 (Q9 — forceUpload=true 허용)</h3>
 * <p>{@code sha256} 은 <b>UNIQUE 가 아닌 일반 인덱스</b>. 같은 해시의 asset 이 여러 개 가능:</p>
 * <ul>
 *   <li>FE 에서 [기존 사용] 선택: 같은 sha256 의 기존 asset reuse (link 만 생성)</li>
 *   <li>FE 에서 [새로 등록] 선택 (forceUpload=true): 같은 sha256 라도 별도 asset 생성</li>
 *   <li>자동 수집 (ScriptExecutionService): confirm 없이 자동 reuse (Q7)</li>
 * </ul>
 *
 * <h3>FULLTEXT INDEX (Q3)</h3>
 * <p>{@code original_file_name} 에 FULLTEXT INDEX 적용 (Flyway V2). MariaDB native,
 * JPA {@code @Index} 미지원이므로 본 entity 에는 일반 인덱스만 명시.</p>
 * <p><b>dev/test 환경 주의</b>: ddl-auto 가 FULLTEXT 자동 생성 안 함 → 검색은
 * LIKE prefix fallback (Repository / Service 분기). L_TEST_BYPASS_FALSE_POSITIVE
 * 사례 ⑦. testcontainers 도입은 carry-over.</p>
 *
 * <h3>FK 정책</h3>
 * <ul>
 *   <li>{@code uploaded_by} → users.id : ON DELETE SET NULL (Flyway V2). user 삭제 시
 *       asset 자체 보존</li>
 *   <li>본 entity 자체는 어디서도 cascade delete 되지 않음 — GC 는 service 레벨
 *       ({@code EvidenceFileService.delete} 의 reference_count = 0 검사, Q10)</li>
 * </ul>
 *
 * <h3>BaseEntity 상속</h3>
 * <p>{@code createdAt} / {@code updatedAt} 은 {@link BaseEntity} 에서 상속. 검색의
 * 다중 필터 (기간) + 정렬 (최신순) 기준으로 사용.</p>
 */
@Entity
@Table(name = "evidence_assets", indexes = {
        @Index(name = "idx_assets_sha256", columnList = "sha256"),
        @Index(name = "idx_assets_uploaded_by", columnList = "uploaded_by")
        // FULLTEXT KEY ft_assets_filename (original_file_name) — Flyway V2 에서만 추가.
        // JPA @Index 는 FULLTEXT 미지원 (Hibernate 6 시점 한계).
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class EvidenceAsset extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * SHA-256 해시 (소문자 hex 64자). 같은 sha256 = 같은 내용.
     * UNIQUE 가 아닌 일반 인덱스 — Q9 정합 (forceUpload=true 시 별도 asset 허용).
     * 검색 / 매칭 단서로만 사용 (중복 감지 dialog 의 후보 조회).
     */
    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    /**
     * 물리 파일 절대 경로. v18.6a 정책: {@code {storage}/assets/{id % 1000}/{id}}.
     * 파일명은 ID 만, 확장자/원본명은 별도 컬럼 ({@link #originalFileName}).
     *
     * <p>{@link #buildRelativePath(Long)} 으로 상대 경로 조립 가능 — service 에서
     * {@code storage.path} prop 와 결합하여 절대 경로 생성.</p>
     */
    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(name = "file_size", nullable = false)
    @Builder.Default
    private Long fileSize = 0L;

    /**
     * 첫 업로드 시점의 파일명. 검색 단서 (FULLTEXT MATCH AGAINST 대상).
     * v18.6a Q6 정책: link 단위 fileName 보존 ({@code EvidenceFile.fileName}) 이 우선,
     * 본 컬럼은 검색용 + 미설정 시 fallback.
     */
    @Column(name = "original_file_name", length = 500)
    private String originalFileName;

    /**
     * 첫 업로드 사용자. User 삭제 시 ON DELETE SET NULL (Flyway V2). asset 자체 보존.
     *
     * <p>entity 에서 {@code @OnDelete} 명시 안 함 — Hibernate 의 default (NO ACTION) 와
     * DB 의 SET NULL 이 영속성 컨텍스트 시점에는 동작 차이 없음. prod DB 정책은
     * Flyway V2 가 source-of-truth.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    // createdAt / updatedAt 은 BaseEntity 에서 상속

    // ========================================================================
    // 정책 helper
    // ========================================================================

    /**
     * Asset 의 storage 상대 경로 조립.
     *
     * <p>{@code assets/{id % 1000}/{id}} 패턴 (Q2=c). Service 에서 절대 경로
     * 생성 시 {@code Paths.get(storagePath, buildRelativePath(id))} 형식 사용.</p>
     *
     * @param assetId asset.id (NULL 불가)
     * @return 상대 경로 (예: "assets/137/137")
     * @throws IllegalArgumentException assetId 가 null 인 경우
     */
    public static String buildRelativePath(Long assetId) {
        if (assetId == null) {
            throw new IllegalArgumentException("assetId is null — 본 helper 는 영속화된 asset 에만 호출 가능");
        }
        return String.format("assets/%d/%d", assetId % 1000, assetId);
    }

    /**
     * 본 asset 의 storage 상대 경로 ({@link #buildRelativePath(Long)} 의 인스턴스 변형).
     * 영속화된 asset (id != null) 에서만 호출 가능.
     */
    public String relativePath() {
        return buildRelativePath(this.id);
    }

    // ========================================================================
    // Update method — service 의 2-단계 INSERT 패턴 정합
    // ========================================================================

    /**
     * filePath 갱신 — IDENTITY id 받은 후 path 빌드 시 사용.
     *
     * <p>EvidenceAssetService 의 2-단계 INSERT 패턴:</p>
     * <ol>
     *   <li>{@code builder().filePath("PENDING").build()} 로 entity save → id 받음</li>
     *   <li>{@code Paths.get(storagePath, buildRelativePath(id))} 로 물리 저장</li>
     *   <li>{@code asset.updateFilePath(absolutePath.toString())} — dirty checking 으로
     *       flush 시 UPDATE SQL 발행</li>
     * </ol>
     *
     * <p>v14.4 의 {@code Framework.touchVersion()} / v14.1 의 {@code ControlNode.update()}
     * 패턴 정합 — Lombok {@code @Setter} 대신 명시적 update method 본 프로젝트 컨벤션.</p>
     */
    public void updateFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath is null or blank");
        }
        this.filePath = filePath;
    }
}