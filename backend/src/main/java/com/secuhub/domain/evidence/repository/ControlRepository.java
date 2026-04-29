package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.Control;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 통제항목 영속화 (구 모델).
 *
 * <h3>⚠ v14 Phase 5-14f — Deprecated</h3>
 * <p>{@link Control} 엔티티가 v14 Phase 5-14f 에서 deprecated 되어 본 Repository 도
 * 함께 deprecated. v15 에서 controls 테이블 + Control 엔티티 + 본 Repository
 * 모두 제거.</p>
 *
 * <h3>v14 동안 처리 정책</h3>
 * <ul>
 *   <li>신규 코드는 {@link ControlNodeRepository} 사용 ({@code node_type='control'} leaf 조회)</li>
 *   <li>본 Repository 의 메서드들은 외부 API 호환을 위해 유지 (v15 제거 예정)</li>
 *   <li>{@link #findByFrameworkIdWithEvidenceTypes} 의 {@code LEFT JOIN FETCH c.evidenceTypes}
 *       는 5-14f 에서 제거 — {@link Control#getEvidenceTypes} 매핑 자체가 사라졌으므로
 *       fetch join 대상이 없음. 의미는 "Framework 별 Control 목록 조회" 로 단순화 (legacy
 *       호환만 유지). 신규 호출 금지</li>
 * </ul>
 *
 * @deprecated v14 Phase 5-14f. v15 에서 controls 테이블 + Control 엔티티 + 본 Repository
 *     완전 제거. 신규 코드는 {@link ControlNodeRepository} 사용.
 */
@Deprecated(since = "v14 Phase 5-14f", forRemoval = true)
public interface ControlRepository extends JpaRepository<Control, Long> {

    /**
     * @deprecated v14 Phase 5-14f. v15 에서 메서드 자체 제거.
     */
    @Deprecated(since = "v14 Phase 5-14f", forRemoval = true)
    List<Control> findByFrameworkIdOrderByCodeAsc(Long frameworkId);

    /**
     * v14 Phase 5-14f 변경: {@code LEFT JOIN FETCH c.evidenceTypes} 제거.
     *
     * <p>{@link Control#getEvidenceTypes()} 매핑이 5-14f 에서 제거됨 ({@link
     * com.secuhub.domain.evidence.entity.EvidenceType#control} 의 mappedBy 가 더 이상
     * Control 이 아니라 ControlNode). fetch join 대상이 없어 JPQL parsing 실패 →
     * 본 메서드의 @Query 본문에서 fetch join 제거. 시맨틱은 "Framework 별 Control 목록 조회"
     * 로 단순화 (legacy controls 테이블 직접 조회만).</p>
     *
     * <p>호출 측이 {@code control.getEvidenceTypes()} 사용 시도하면 컴파일 에러 (Control 의
     * 매핑이 제거됨) — 5-14f 회귀 픽스 시점에 모든 호출 측이 ControlNode 위임으로 전환됨이
     * 보장됨.</p>
     *
     * @deprecated v14 Phase 5-14f. v15 에서 메서드 자체 제거. 신규 코드는
     *     {@link ControlNodeRepository#findByFrameworkIdAndNodeTypeOrderByDisplayOrderAsc} 사용.
     */
    @Deprecated(since = "v14 Phase 5-14f", forRemoval = true)
    @Query("""
        SELECT c FROM Control c
        WHERE c.framework.id = :frameworkId
        ORDER BY c.code ASC
        """)
    List<Control> findByFrameworkIdWithEvidenceTypes(@Param("frameworkId") Long frameworkId);
}