package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import com.secuhub.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

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
 * {@code control_id} 그대로 유지</b> (v15.7 Q1=B 결정 — 마이그레이션 0).</p>
 *
 * <h3>v18.3 — {@code @OnDelete(CASCADE)} 추가 (L_SPEC_SCHEMA_MISMATCH 종결)</h3>
 * <p>v18.2 fix 부산 발견 — DB FK ({@code FKn504iexlvxm3rnqu2j0fbkpej}) 가 default
 * RESTRICT 였음. spec §3.3.1.3 의 "ON DELETE CASCADE 가 evidence_types 까지 자동
 * 삭제" 표기와 mismatch. v18.3 정공 fix 로 ddl-auto (dev/test) + Flyway
 * {@code V_v18_3} (staging/prod) 양쪽에서 ON DELETE CASCADE 동등 동작.
 * ControlNode 삭제 → 매달린 EvidenceType cascade 삭제 → (cascade 연쇄)
 * CollectionJob / EvidenceFile 까지 자동 정리.</p>
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
     * v18.3 — {@code @OnDelete(CASCADE)} 추가 (L_SPEC_SCHEMA_MISMATCH 종결).
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
     * v15 Phase 5-15c (v15.7) — 메서드명 {@code setControl} → {@code setControlNode}.
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