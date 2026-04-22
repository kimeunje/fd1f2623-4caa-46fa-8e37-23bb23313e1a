package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import com.secuhub.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "control_id", nullable = false)
    private Control control;

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

    void setControl(Control control) {
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