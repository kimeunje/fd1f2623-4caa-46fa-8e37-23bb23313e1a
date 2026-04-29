package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import com.secuhub.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 증빙 유형 (Evidence Type) 엔티티.
 *
 * <h3>v14 Phase 5-14f — control FK 의 타입 변경 (핵심)</h3>
 * <p>{@code @JoinColumn(name="control_id")} 의 타입이 {@link Control} → {@link ControlNode}
 * 로 변경됨. DB 레벨 컬럼은 그대로 ({@code control_id}). prod 환경에서는 5-14b 의 V6
 * 마이그레이션이 이미 {@code evidence_types.control_id += 1,000,000} 적용 + FK 를
 * {@code control_nodes(id)} 로 이전 완료. 본 phase 는 JPA 매핑을 그 결과에 정합화.</p>
 *
 * <p>dev/test 환경 (ddl-auto: create) 에서는 V6 가 안 돌지만, 본 매핑이 control_nodes
 * 를 직접 가리키므로 자연 작동. 5-14e 의 impact-summary 와 5-14c 의 GET /tree 의
 * leaf 두 카운트 (evidenceTypeCount / pendingReviewCount) 가 자연 정상화.</p>
 *
 * <h3>제약</h3>
 * <ul>
 *   <li>{@code control_id} 는 leaf {@code ControlNode} ({@code node_type='control'}) 만
 *       가리켜야 함. 5-14f 시점 application 레이어에서 검증 (PATCH /tree 의 12 검증 +
 *       {@link ControlNode#addEvidenceType(EvidenceType)} 의 leaf 검증). v15 에서 DB
 *       CHECK constraint 추가 검토.</li>
 * </ul>
 */
@Entity
@Table(name = "evidence_types", indexes = {
        @Index(name = "idx_evidence_types_owner", columnList = "owner_user_id"),
        @Index(name = "idx_evidence_types_due", columnList = "due_date")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class EvidenceType extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * v14 Phase 5-14f — 타입 {@link Control} → {@link ControlNode} 변경.
     * leaf ControlNode 만 매달 수 있다 ({@code node_type='control'}).
     * 컬럼명은 {@code control_id} 그대로 유지 (DB 호환).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "control_id", nullable = false)
    private ControlNode control;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * v10 도입 / v11 활용: 증빙 담당자.
     * permission_evidence=true 인 사용자가 배정됨. 본인이 업로드·재제출·이력 조회 가능.
     * NULL 이면 "미배정" — 관리자만 업로드 가능.
     * ON DELETE SET NULL — 사용자 삭제 시 미배정 상태로 복구.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private User ownerUser;

    /**
     * v11: 이 Framework 내 이 증빙의 제출 마감일.
     * 감사 주기별로 다를 수 있어 EvidenceType 레벨에 위치 (Framework 상속 시 복제 대상).
     */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @OneToMany(mappedBy = "evidenceType", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<EvidenceFile> evidenceFiles = new ArrayList<>();

    /**
     * v14 Phase 5-14f — 시그니처 변경: {@link Control} → {@link ControlNode}.
     * 기존 호출 측 (5-14a 의 {@link Control#addEvidenceType} 등) 은 5-14f 에서 모두
     * leaf ControlNode 직접 사용으로 전환됨.
     */
    void setControl(ControlNode control) {
        this.control = control;
    }

    public void update(String name, String description) {
        if (name != null) this.name = name;
        if (description != null) this.description = description;
    }

    // v11: 담당자·마감일 지정
    public void assignOwner(User owner) {
        this.ownerUser = owner;
    }

    public void updateDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }
}