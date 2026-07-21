package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.EvidenceFile;
import com.secuhub.domain.evidence.entity.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * EvidenceFile 영속화.
 *
 * <h3>v15 Phase 5-15c (v15.7) — Q1=B + Q5=A 정합</h3>
 * <p>EvidenceType 의 자바 필드명 {@code control} → {@code controlNode} 변경 정합:</p>
 * <ul>
 *   <li>JPQL 안 모든 {@code ef.evidenceType.control.*} 참조 → {@code ef.evidenceType.controlNode.*}
 *       (FORCED — Hibernate path resolution)</li>
 *   <li>JPQL 안 {@code JOIN et.control cn} → {@code JOIN et.controlNode cn} (FORCED)</li>
 *   <li>@Query 메서드명 일관 rename (Q5=A): {@code countByControlId} →
 *       {@link #countByControlNodeId(Long)}, 동일 패턴 (총 6 메서드)</li>
 *   <li>@Param 명: {@code controlId} → {@code controlNodeId} / {@code controlIds}
 *       → {@code controlNodeIds} (Repository layer 명명 정합)</li>
 * </ul>
 *
 * <h3>v16.4a (Dashboard) — 추가 메서드</h3>
 * <p>DashboardService 가 사용하는 N+1 차단 JOIN FETCH 버전 + 진척률 집계 2 카운트.</p>
 */
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
     * evidence_types → control_nodes → frameworks 조인으로 집계.
     *
     * <p>v15.7: JPQL navigation {@code ef.evidenceType.control.framework.id} →
     * {@code ef.evidenceType.controlNode.framework.id} (Q1=B). 메서드명은 "Framework"
     * 기준이라 보존.</p>
     */
    @Query("""
        SELECT COUNT(ef) FROM EvidenceFile ef
        WHERE ef.reviewStatus = :status
          AND ef.evidenceType.controlNode.framework.id = :frameworkId
        """)
    long countByFrameworkIdAndReviewStatus(@Param("frameworkId") Long frameworkId,
                                           @Param("status") ReviewStatus status);

    /**
     * 특정 ControlNode 내 승인 대기 건수 — Framework 상세 페이지의 통제 항목 행 배지 (Phase 5-9).
     * evidence_types → control_nodes 조인으로 집계.
     *
     * <p>패턴은 {@link #countByFrameworkIdAndReviewStatus(Long, ReviewStatus)} 와 동일.</p>
     *
     * <p>v15.7: 메서드명 {@code countByControlIdAndReviewStatus} →
     * {@code countByControlNodeIdAndReviewStatus} (Q5=A) + JPQL {@code controlNode}
     * (Q1=B).</p>
     */
    @Query("""
        SELECT COUNT(ef) FROM EvidenceFile ef
        WHERE ef.reviewStatus = :status
          AND ef.evidenceType.controlNode.id = :controlNodeId
        """)
    long countByControlNodeIdAndReviewStatus(@Param("controlNodeId") Long controlNodeId,
                                             @Param("status") ReviewStatus status);

    // ========================================================================
    // v14 Phase 5-14e — leaf 통제 코드 변경 사전 경고 (impact-summary)
    // ========================================================================

    /**
     * 특정 통제 (또는 hybrid 노드) 에 매달린 모든 EvidenceFile 수.
     *
     * <p>Phase 5-14e impact-summary 의 own evidence file 카운트용.
     * version / review_status 무관, 모든 행 카운트.</p>
     *
     * <p>{@code controlNodeId} 의미는 spec §3.3.1.5 (5-14e Q1=A): leaf control_node.id
     * (또는 hybrid 노드 id). V6 prod 후 {@code evidence_types.control_id} 가 leaf
     * control_node.id 로 이주됨 (+1,000,000 offset).</p>
     *
     * <p>패턴은 기존 {@link #countByControlNodeIdAndReviewStatus(Long, ReviewStatus)} 와 동일
     * — review_status 필터만 제거.</p>
     *
     * <p>v15.7: 메서드명 + JPQL + @Param 모두 controlNode 정합.</p>
     */
    @Query("""
        SELECT COUNT(ef) FROM EvidenceFile ef
        WHERE ef.evidenceType.controlNode.id = :controlNodeId
        """)
    long countByControlNodeId(@Param("controlNodeId") Long controlNodeId);

    /**
     * 특정 통제 (또는 hybrid 노드) 에 매달린 EvidenceFile 중 관리자가 명시 검토한
     * (reviewed_at IS NOT NULL) 수.
     *
     * <p>Phase 5-14e impact-summary 의 own review 카운트용. spec §3.3.1.5 의
     * "검토 이력 N건" 의미. {@code review_status IN ('approved', 'rejected')} 인 파일이
     * 모두 reviewed_at 가짐 (Phase 5-4 EvidenceApprovalService 가 set). pending /
     * auto_approved 는 reviewed_at 가 NULL 이라 제외.</p>
     *
     * <p>v15.7: 메서드명 + JPQL + @Param 모두 controlNode 정합.</p>
     */
    @Query("""
        SELECT COUNT(ef) FROM EvidenceFile ef
        WHERE ef.evidenceType.controlNode.id = :controlNodeId
          AND ef.reviewedAt IS NOT NULL
        """)
    long countReviewedByControlNodeId(@Param("controlNodeId") Long controlNodeId);

    // ====================================================================
    // v14 Phase 5-14f — TreeService.getTree 의 leaf 별 pendingReviewCount 본격 집계용
    // ====================================================================

    /**
     * 한 Framework 안의 노드 별 pending evidence_files 카운트를 한 번에 집계.
     *
     * <p>응답: {@code Object[]} 의 배열. 각 항목 {@code [Long controlNodeId, Long count]}.
     * pending 파일 0 개인 노드는 결과에 포함 안 됨.</p>
     *
     * <p>v14 Phase 5-14f 안전 버전 (Hibernate 6 호환):</p>
     * <ol>
     *   <li><b>explicit JOIN</b> — implicit 다단 path 대신 명시 JOIN 으로 nested
     *       association path resolution 안정성 확보.</li>
     *   <li><b>{@link ReviewStatus} parameter</b> — fully-qualified enum literal 의
     *       Hibernate 6 SQM path 오인 회피.</li>
     * </ol>
     *
     * <p>TreeService 가 노드마다 호출하지 말고 Framework 단위로 1회 호출 후
     * Map<controlNodeId, count> 빌드 (N+1 회피).</p>
     *
     * <p>v15.7: 메서드명 + JPQL 정합 갱신.</p>
     *
     * @param frameworkId Framework id
     * @param status 일반적으로 {@link ReviewStatus#pending} 전달 (호출 측 명시)
     * @return [controlNodeId, count] 배열의 리스트
     */
    @Query("""
            SELECT cn.id, COUNT(ef)
              FROM EvidenceFile ef
              JOIN ef.evidenceType et
              JOIN et.controlNode cn
             WHERE cn.framework.id = :frameworkId
               AND ef.reviewStatus = :status
             GROUP BY cn.id
            """)
    List<Object[]> countPendingGroupByControlNodeIdInFramework(@Param("frameworkId") Long frameworkId,
                                                                @Param("status") ReviewStatus status);

    // ====================================================================
    // v15 Phase 5-15a — Hybrid impact-summary 의 자손 카운트 집계
    //                   ControlNodeRepository.findAllDescendants 의 id list 를
    //                   IN 절로 받아 카운트. 빈 list 면 호출 측이 본 메서드 호출 회피.
    // ====================================================================

    /**
     * 자손 노드들에 매달린 모든 EvidenceFile 수.
     *
     * <p>Phase 5-15a hybrid impact-summary 의 {@code descendantEvidenceFileCount}
     * 필드용. {@link #countByControlNodeId(Long)} 의 IN 절 확장 버전 — 같은 SQL
     * 패턴 ({@code ef.evidenceType.controlNode.id} 매칭) 에 단일 id 대신 id 리스트.</p>
     *
     * <p>호출 규약: {@code controlNodeIds} 가 비어있을 경우 본 메서드 호출 금지
     * (JPQL {@code IN ()} 의 환경 의존 동작 회피). 호출 측에서 빈 list 검사 후 0 리턴.</p>
     *
     * @param controlNodeIds 자손 ControlNode.id 리스트 (본인 제외)
     */
    @Query("""
        SELECT COUNT(ef) FROM EvidenceFile ef
        WHERE ef.evidenceType.controlNode.id IN :controlNodeIds
        """)
    long countByControlNodeIds(@Param("controlNodeIds") List<Long> controlNodeIds);

    /**
     * 자손 노드들에 매달린 EvidenceFile 중 명시 검토된 (reviewed_at IS NOT NULL) 수.
     *
     * <p>Phase 5-15a hybrid impact-summary 의 {@code descendantReviewCount} 필드용.
     * {@link #countReviewedByControlNodeId(Long)} 의 IN 절 확장 버전.</p>
     *
     * @param controlNodeIds 자손 ControlNode.id 리스트 (본인 제외)
     */
    @Query("""
        SELECT COUNT(ef) FROM EvidenceFile ef
        WHERE ef.evidenceType.controlNode.id IN :controlNodeIds
          AND ef.reviewedAt IS NOT NULL
        """)
    long countReviewedByControlNodeIds(@Param("controlNodeIds") List<Long> controlNodeIds);

    // ====================================================================
    // v16.4a (Dashboard 위젯) — JOIN FETCH N+1 차단 + 진척률 집계
    // ====================================================================

    /**
     * v16.4a (Dashboard) — 승인 대기 top 10 (raw, Pageable 사용).
     *
     * <p>기존 {@link #findByReviewStatus(ReviewStatus, Pageable)} 와 의도 다름:
     * 본 메서드는 {@code JOIN FETCH evidenceType + controlNode + framework + uploadedBy}
     * 로 lazy load 일괄 hydrate (N+1 차단). DashboardService 가 ancestors path 빌드 +
     * deepLinkUrl 조립 시 lazy 폭발 회피.</p>
     *
     * <p>spec §3.8.1 정합. submittedAt = createdAt DESC.</p>
     */
    @Query("""
        SELECT ef FROM EvidenceFile ef
        JOIN FETCH ef.evidenceType et
        JOIN FETCH et.controlNode cn
        JOIN FETCH cn.framework fw
        LEFT JOIN FETCH ef.uploadedBy u
        WHERE ef.reviewStatus = com.secuhub.domain.evidence.entity.ReviewStatus.pending
        ORDER BY ef.createdAt DESC
        """)
    List<EvidenceFile> findTop10PendingForDashboardRaw(Pageable pageable);

    /**
     * v16.4a (Dashboard) — 승인 대기 top 10 default (limit=10).
     */
    default List<EvidenceFile> findTop10PendingForDashboard() {
        return findTop10PendingForDashboardRaw(PageRequest.of(0, 10));
    }

    /**
     * v16.4a (Dashboard) — Framework 진척 계산용.
     * 주어진 evidence_type id 들 중 1+ approved/auto_approved 가진 것의 distinct 수.
     *
     * <p>spec §3.8.1 의 "수집 완료 = 1+ approved/auto_approved" 정의 정합
     * (FrameworkProgress.collectedCount).</p>
     *
     * <p>호출 규약: {@code evidenceTypeIds} 가 빈 list 인 경우 호출 측에서 호출 회피
     * (JPQL {@code IN ()} 환경 의존 회피, 본 패키지의 {@link #countByControlNodeIds(List)}
     * 와 같은 패턴).</p>
     */
    @Query("""
        SELECT COUNT(DISTINCT ef.evidenceType.id) FROM EvidenceFile ef
        WHERE ef.evidenceType.id IN :evidenceTypeIds
          AND ef.reviewStatus IN (
              com.secuhub.domain.evidence.entity.ReviewStatus.approved,
              com.secuhub.domain.evidence.entity.ReviewStatus.auto_approved
          )
        """)
    long countDistinctEvidenceTypesByCollectedStatus(
            @Param("evidenceTypeIds") List<Long> evidenceTypeIds);

    /**
     * v16.4a (Dashboard) — Framework 진척 계산용.
     * 주어진 evidence_type id 들 중 1+ pending 가진 것의 distinct 수.
     *
     * <p>spec §3.8.1 의 "검토 대기 = 1+ pending" 정의 정합
     * (FrameworkProgress.pendingReviewCount).</p>
     */
    @Query("""
        SELECT COUNT(DISTINCT ef.evidenceType.id) FROM EvidenceFile ef
        WHERE ef.evidenceType.id IN :evidenceTypeIds
          AND ef.reviewStatus = com.secuhub.domain.evidence.entity.ReviewStatus.pending
        """)
    long countDistinctEvidenceTypesByPendingStatus(
            @Param("evidenceTypeIds") List<Long> evidenceTypeIds);

    // ====================================================================
    // v19.25 (심사원 뷰) — 항목별 "최신 승인 파일" 1건 벌크 조회
    // ====================================================================

    /**
     * 한 Framework 안의 증빙 유형별 "최신 승인 파일" 1건을 Framework 단위 1회로 벌크 조회 (N+1 회피).
     *
     * <p>승인 절차 off 환경({@code app.approval.enabled=false}) 정합 — 최신 버전 = 최신 파일이지만,
     * 레거시 pending/rejected 행 오염을 막기 위해 {@code review_status IN (approved, auto_approved)}
     * 중 evidence_type 별 {@code MAX(version)} 만 선별. 상관 서브쿼리는 같은 승인 필터를 다시 적용.</p>
     *
     * <p>DTO 단계 제외 원칙 — 파일 이력/버전 목록·스크립트·작업·노트는 조회하지 않음. 심사원이 보는 것은
     * evidence_type 별 최신 승인 파일 1건뿐. fully-qualified enum 리터럴은 dashboard 쿼리와 동일 패턴
     * (Hibernate 6 SQM path 오인 회피).</p>
     *
     * <p>응답: {@code Object[]} 배열. 각 항목
     * {@code [Long fileId, Long evidenceTypeId, String fileName, Long fileSize, Integer version, LocalDateTime collectedAt]}.
     * 승인 파일이 하나도 없는 evidence_type 은 결과에 미포함(호출 측에서 latestFile=null 처리).</p>
     */
    @Query("""
        SELECT ef.id, ef.evidenceType.id, ef.fileName
          FROM EvidenceFile ef
         WHERE ef.evidenceType.controlNode.framework.id = :frameworkId
           AND ef.reviewStatus IN (
                 com.secuhub.domain.evidence.entity.ReviewStatus.approved,
                 com.secuhub.domain.evidence.entity.ReviewStatus.auto_approved
               )
           AND ef.version = (
                 SELECT MAX(ef2.version) FROM EvidenceFile ef2
                  WHERE ef2.evidenceType = ef.evidenceType
                    AND ef2.reviewStatus IN (
                          com.secuhub.domain.evidence.entity.ReviewStatus.approved,
                          com.secuhub.domain.evidence.entity.ReviewStatus.auto_approved
                        )
               )
        """)
    List<Object[]> findLatestApprovedFileRowsByFramework(@Param("frameworkId") Long frameworkId);

    /**
     * v19.25 — 파일이 속한 Framework id (심사원 다운로드 스코프 검증용).
     * evidence_files → evidence_types → control_nodes → frameworks 경로.
     */
    @Query("""
        SELECT ef.evidenceType.controlNode.framework.id
          FROM EvidenceFile ef
         WHERE ef.id = :fileId
        """)
    java.util.Optional<Long> findFrameworkIdByFileId(@Param("fileId") Long fileId);
}