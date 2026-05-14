package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.EvidenceAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link EvidenceAsset} (물리 파일) repository. v18.6a 신규.
 *
 * <h3>주요 메서드</h3>
 * <ul>
 *   <li>{@link #findFirstBySha256OrderByCreatedAtDesc} — 중복 감지 dialog 의
 *       "기존 사용" 후보 조회 (Q1=b 정합)</li>
 *   <li>{@link #searchAssetsFulltext} — prod (MariaDB FULLTEXT) 검색</li>
 *   <li>{@link #searchAssetsLike} — dev/test fallback (LIKE prefix)</li>
 *   <li>{@link #countLinkedFiles} — 단건 link 카운트 (GC 판단, Q10)</li>
 *   <li>{@link #countLinkedFilesByAssetIds} — batch 카운트 (검색 응답 usedInCount, Q11)</li>
 * </ul>
 *
 * <h3>환경 분기 (L_TEST_BYPASS_FALSE_POSITIVE 사례 ⑦)</h3>
 * <p>MariaDB FULLTEXT INDEX 는 Flyway V2 에서만 추가됨. dev/test (ddl-auto) 에서는
 * FULLTEXT 자동 생성 안 됨 → {@link #searchAssetsFulltext} 가 native query 실패
 * 가능. Service 레벨에서 환경 분기 (Spring profile 또는 try-catch fallback) 권장.</p>
 *
 * <p>본 프로젝트 컨벤션 정합:</p>
 * <ul>
 *   <li>v14.3 의 {@code TreeReadTest} 패턴 — native query 의 JPQL 정합</li>
 *   <li>v16.4a 의 {@code DashboardSummary} 패턴 — N+1 회피 batch query</li>
 *   <li>v18.5-platform-c 의 V1 baseline 정합 — 본 repository 의 native query 는 V2 (FULLTEXT INDEX) 의존</li>
 * </ul>
 */
public interface EvidenceAssetRepository extends JpaRepository<EvidenceAsset, Long> {

    // ========================================================================
    // SHA-256 조회 — 중복 감지 dialog
    // ========================================================================

    /**
     * SHA-256 해시 기준 가장 최근 asset 조회.
     * 중복 감지 dialog 의 "기존 사용" 후보 (Q1=b 정합).
     *
     * <p>같은 해시의 asset 이 여러 개 있을 수 있음 (Q9 — {@code forceUpload=true}
     * 시 별도 생성). 가장 최근 ({@code created_at DESC}) 1개 반환.</p>
     *
     * <p>Service 호출 동선:</p>
     * <ol>
     *   <li>FE upload 요청 → BE 가 sha256 계산</li>
     *   <li>본 메서드로 기존 asset 조회</li>
     *   <li>Optional 비어 있으면 신규 asset 생성</li>
     *   <li>존재하면 응답 shape {@code status: "duplicate_detected"} + existingAsset 정보 반환</li>
     * </ol>
     */
    Optional<EvidenceAsset> findFirstBySha256OrderByCreatedAtDesc(String sha256);

    /**
     * SHA-256 기준 같은 해시의 모든 asset 조회.
     * Admin 용 디버깅 / 정리 (예: forceUpload=true 누적된 같은 내용 asset 들 검토).
     * v18.6c 또는 후순위 admin UI 에서 활용 예정.
     */
    List<EvidenceAsset> findBySha256OrderByCreatedAtDesc(String sha256);

    // ========================================================================
    // 검색 — prod (FULLTEXT) / dev (LIKE fallback) 분기
    // ========================================================================

    /**
     * 검색 — prod (MariaDB FULLTEXT INDEX) 한정.
     *
     * <p>{@code MATCH(original_file_name) AGAINST (... IN NATURAL LANGUAGE MODE)}.
     * 공백 단위 token, 한국어 형태소 분석 없음 (FULLTEXT collation =
     * utf8mb4_unicode_ci). 검색 단서 보강 = 다중 필터 (업로더 / 기간).</p>
     *
     * <p><b>dev/test 환경에서는 작동 안 함</b> — FULLTEXT INDEX 가 Flyway V2 에서만
     * 추가되고, dev/test 는 ddl-auto 자동 정합이라 FULLTEXT 자체 없음. Service 분기로
     * {@link #searchAssetsLike} fallback 권장. L_TEST_BYPASS_FALSE_POSITIVE 사례 ⑦ /
     * L_FLYWAY_BASE_SCHEMA_GAP 응용.</p>
     *
     * <p>Service 측 정규화 권장: {@code query == null ? "" : query.trim()}.
     * {@code :query = ''} 분기로 빈 검색어 시 전체 (필터만 적용) 반환.</p>
     *
     * @param query 검색어 (빈 문자열 = 전체)
     * @param uploaderId 업로더 user.id (NULL = 전체)
     * @param fromDate 등록일 시작 (NULL = 제한 없음)
     * @param toDate 등록일 끝 (NULL = 제한 없음)
     * @param pageable 페이지네이션 (50건 권장)
     * @return Page&lt;EvidenceAsset&gt; — 최신순 정렬
     */
    @Query(value =
        "SELECT a.* FROM evidence_assets a " +
        "WHERE (:query = '' OR " +
        "       MATCH(a.original_file_name) AGAINST (:query IN NATURAL LANGUAGE MODE)) " +
        "  AND (:uploaderId IS NULL OR a.uploaded_by = :uploaderId) " +
        "  AND (:fromDate IS NULL OR a.created_at >= :fromDate) " +
        "  AND (:toDate IS NULL OR a.created_at <= :toDate) " +
        "ORDER BY a.created_at DESC",
        countQuery =
        "SELECT COUNT(*) FROM evidence_assets a " +
        "WHERE (:query = '' OR " +
        "       MATCH(a.original_file_name) AGAINST (:query IN NATURAL LANGUAGE MODE)) " +
        "  AND (:uploaderId IS NULL OR a.uploaded_by = :uploaderId) " +
        "  AND (:fromDate IS NULL OR a.created_at >= :fromDate) " +
        "  AND (:toDate IS NULL OR a.created_at <= :toDate)",
        nativeQuery = true)
    Page<EvidenceAsset> searchAssetsFulltext(@Param("query") String query,
                                             @Param("uploaderId") Long uploaderId,
                                             @Param("fromDate") LocalDateTime fromDate,
                                             @Param("toDate") LocalDateTime toDate,
                                             Pageable pageable);

    /**
     * 검색 — dev/test fallback (JPQL + LIKE prefix).
     *
     * <p>FULLTEXT INDEX 없는 환경에서 사용. 검색 동작 차이:</p>
     * <ul>
     *   <li>FULLTEXT: 부분 일치 (token 단위 분리) — "정책" 검색 시 "정보보호 정책서" 매칭</li>
     *   <li>LIKE prefix: 시작 일치 — "정책" 으로 시작하는 파일명만 매칭</li>
     * </ul>
     *
     * <p>Service 에서 환경 분기 권장. 패턴 옵션:</p>
     * <ol>
     *   <li>Spring profile 기반 — {@code @Profile("prod")} = Fulltext, 그 외 = Like</li>
     *   <li>try-catch — Fulltext 우선 시도, MariaDB FULLTEXT 에러 시 Like fallback</li>
     *   <li>{@code @Value} prop — {@code app.search.use-fulltext} 토글</li>
     * </ol>
     *
     * <p>본 프로젝트 컨벤션: option 1 (Spring profile) 권장. dev/test 자동 LIKE, prod 자동 FULLTEXT.</p>
     */
    @Query("SELECT a FROM EvidenceAsset a " +
           "WHERE (:query = '' OR LOWER(a.originalFileName) LIKE LOWER(CONCAT(:query, '%'))) " +
           "  AND (:uploaderId IS NULL OR a.uploadedBy.id = :uploaderId) " +
           "  AND (:fromDate IS NULL OR a.createdAt >= :fromDate) " +
           "  AND (:toDate IS NULL OR a.createdAt <= :toDate) " +
           "ORDER BY a.createdAt DESC")
    Page<EvidenceAsset> searchAssetsLike(@Param("query") String query,
                                          @Param("uploaderId") Long uploaderId,
                                          @Param("fromDate") LocalDateTime fromDate,
                                          @Param("toDate") LocalDateTime toDate,
                                          Pageable pageable);

    // ========================================================================
    // Link 카운트 — usedInCount + GC 판단
    // ========================================================================

    /**
     * 단건 asset 의 매달린 EvidenceFile (link) 카운트.
     *
     * <p>용도:</p>
     * <ul>
     *   <li>삭제 시 GC 판단 (Q10) — count == 0 시 EvidenceFileService 가 물리 + entity
     *       자동 삭제</li>
     *   <li>단건 asset 상세 조회 ({@code GET /evidence-assets/{id}}) 응답의
     *       usedInCount (Q11)</li>
     * </ul>
     *
     * <p>검색 결과 list 의 usedInCount 정합용은 N+1 회피로
     * {@link #countLinkedFilesByAssetIds} 사용.</p>
     */
    @Query("SELECT COUNT(ef) FROM EvidenceFile ef WHERE ef.asset.id = :assetId")
    long countLinkedFiles(@Param("assetId") Long assetId);

    /**
     * 여러 asset 의 link 카운트 batch 조회 — 검색 결과 list 의 usedInCount 정합용.
     *
     * <p>N+1 회피 (1 query 로 list 의 모든 카운트 매핑). v16.4a 의 {@code Dashboard
     * Summary} batch 패턴 정합.</p>
     *
     * <p>응답은 {@code (assetId, count)} 튜플 List. Service 에서 Map 변환:</p>
     * <pre>
     * Map&lt;Long, Long&gt; countMap = repo.countLinkedFilesByAssetIds(ids).stream()
     *     .collect(Collectors.toMap(
     *         row -&gt; (Long) row[0],
     *         row -&gt; (Long) row[1]));
     * </pre>
     *
     * <p>주의: 결과에 포함되지 않은 asset (link 0개) 은 map 에서 빠짐 → service 에서
     * default 0 처리 ({@code countMap.getOrDefault(assetId, 0L)}).</p>
     */
    @Query("SELECT ef.asset.id, COUNT(ef) FROM EvidenceFile ef " +
           "WHERE ef.asset.id IN :assetIds " +
           "GROUP BY ef.asset.id")
    List<Object[]> countLinkedFilesByAssetIds(@Param("assetIds") List<Long> assetIds);
}