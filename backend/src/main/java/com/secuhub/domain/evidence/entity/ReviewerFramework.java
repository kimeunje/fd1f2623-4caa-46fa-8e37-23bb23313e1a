package com.secuhub.domain.evidence.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * v19.25 — 심사원(reviewer) ↔ 프레임워크 열람 배정.
 *
 * <p>계정 설정에서 심사원에게 열어줄 프레임워크를 지정하면 (user_id, framework_id) 행이 쌓인다.
 * 심사원 뷰(ReviewService)는 이 배정에 있는 프레임워크만 목록/트리/다운로드로 노출한다.
 * 배정이 없는 심사원은 아무것도 못 본다(fail-closed).</p>
 *
 * <p>user / framework 를 {@code @ManyToOne} 로 매핑하지 않고 <b>plain Long 컬럼</b>으로 둔다 —
 * user 도메인·evidence 도메인 엔티티 간 결합을 피하고, lazy 로딩 없이 id 만 다루기 위함.
 * 참조 무결성은 DB FK(Flyway {@code V_v19_28}, ON DELETE CASCADE)로 보장한다.</p>
 */
@Entity
@Table(
        name = "reviewer_frameworks",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_reviewer_framework",
                columnNames = {"user_id", "framework_id"}),
        indexes = @Index(name = "idx_rf_user", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReviewerFramework {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "framework_id", nullable = false)
    private Long frameworkId;
}