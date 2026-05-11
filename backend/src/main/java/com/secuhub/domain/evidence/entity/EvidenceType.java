package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import com.secuhub.domain.user.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 증빙 유형 (Evidence Type) 엔티티.
 *
 * <h3>v14 Phase 5-14f — control FK 의 타입 변경 (역사 보존)</h3>
 * <p>{@code @JoinColumn(name="control_id")} 의 타입이 v14 5-14f 에서 {@code Control}
 * → {@link ControlNode} 로 변경됨. DB 레벨 컬럼은 그대로 ({@code control_id}). prod
 * 환경에서는 5-14b 의 V6 마이그레이션이 이미 {@code evidence_types.control_id += 1,000,000}
 * 적용 + FK 를 {@code control_nodes(id)} 로 이전 완료.</p>
 *
 * <h3>v15 Phase 5-15b R3 (v15.6) — Control 엔티티 + ControlController 일괄 제거</h3>
 *
 * <h3>v15 Phase 5-15c (v15.7) — 자바 필드명 정합 (DB 컬럼 보존)</h3>
 * <p>자바 필드명 {@code control} → {@link #controlNode} 로 rename. <b>DB 컬럼명은
 * {@code control_id} 그대로 유지</b> (v15.7 Q1=B 결정 — 마이그레이션 0). 의미 정합:
 * 매핑 대상이 {@link ControlNode} 이므로 자바 명명도 그에 맞춤. Repository derived
 * query 도 {@code findByControlId} → {@code findByControlNodeId} 자동 정합 (Spring
 * Data 가 {@link #controlNode} 필드 path 정합 메서드명 요구).</p>
 *
 * <p>cascade 영향:</p>
 * <ul>
 *   <li>Lombok {@code @Getter} → {@code getControlNode()} 자동 생성 (옛 {@code getControl()}
 *       호출처는 모두 일괄 변경됨)</li>
 *   <li>{@link ControlNode#evidenceTypes} 의 {@code @OneToMany(mappedBy="controlNode")}
 *       정합 갱신 (v15.7)</li>
 *   <li>JPA Repository 의 모든 JPQL {@code et.control} 참조 → {@code et.controlNode}</li>
 *   <li>Lombok {@code @Builder} 의 {@code .control(...)} 호출 → {@code .controlNode(...)}
 *       (FrameworkService.inherit 등)</li>
 * </ul>
 *
 * <h3>제약</h3>
 * <ul>
 *   <li>v15 5-15a (hybrid) 채택 후로는 leaf 만이 아니라 모든 노드가 evidence_types
 *       보유 가능 ({@link ControlNode#addEvidenceType} 의 leaf-only 가드 제거됨).
 *       v15 에서 DB CHECK constraint 추가 검토는 후순위.</li>
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
     * v14 Phase 5-14f — 타입 {@code Control} → {@link ControlNode} 변경.
     * v15 Phase 5-15c (v15.7) — 자바 필드명 {@code control} → {@code controlNode}.
     * 컬럼명은 {@code control_id} 그대로 유지 (DB 호환). 호출 측은 모두
     * {@code et.getControlNode()} / {@code .controlNode(...)} 사용.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "control_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ControlNode controlNode;

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
     * v14 Phase 5-14f — 시그니처 {@code Control} → {@link ControlNode}.
     * v15 Phase 5-15c (v15.7) — 메서드명 {@code setControl} → {@code setControlNode}
     * (필드명 정합).
     *
     * <p>호출 측은 {@link ControlNode#addEvidenceType(EvidenceType)} 안의 양방향
     * 동기화 1 곳 — 외부 직접 호출 0.</p>
     */
    void setControlNode(ControlNode controlNode) {
        this.controlNode = controlNode;
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