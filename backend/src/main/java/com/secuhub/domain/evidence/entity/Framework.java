package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;


@Entity
@Table(name = "frameworks", indexes = {
        @Index(name = "idx_frameworks_parent", columnList = "parent_framework_id"),
        @Index(name = "idx_frameworks_status", columnList = "status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Framework extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * v11: 상속 원본 Framework. NULL이면 신규 생성.
     * 예) ISMS-P 2026 의 parent = ISMS-P 2025
     * ON DELETE SET NULL — 원본 삭제 시 자식은 고아가 되지만 독립 운영 가능.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_framework_id")
    private Framework parentFramework;

    /**
     * v11: 아카이브 처리용.
     * active   — 현재 감사 주기에서 사용 중
     * archived — 종료된 감사 주기
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private FrameworkStatus status = FrameworkStatus.active;

    @OneToMany(mappedBy = "framework", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Control> controls = new ArrayList<>();

    /**
     * v14 Phase 5-14a — Optimistic lock.
     *
     * <p>통제 관리 통합 다이얼로그 ({@code UnifiedControlsDialog})의 동시 편집 충돌 감지에 사용.
     * JPA {@code @Version} 으로 자동 관리:</p>
     * <ul>
     *   <li>INSERT 시 0 으로 초기화 ({@code @Builder.Default} 로도 안전망)</li>
     *   <li>UPDATE 시 +1 자동 증가</li>
     *   <li>UPDATE 의 WHERE 절에 {@code version = ?} 추가되어 충돌 시 {@code OptimisticLockException}</li>
     * </ul>
     *
     * <p>Phase 5-14d 의 {@code PATCH /tree} 에서 클라이언트가 보낸 {@code expectedVersion}
     * 과 비교하여 409 응답 처리에 사용 예정.</p>
     */
    @Version
    @Builder.Default
    @Column(nullable = false)
    private Long version = 0L;

    public void update(String name, String description) {
        if (name != null) this.name = name;
        if (description != null) this.description = description;
    }

    public void addControl(Control control) {
        this.controls.add(control);
        control.setFramework(this);
    }

    // v11: 상태 전이
    public void archive() {
        this.status = FrameworkStatus.archived;
    }

    public void activate() {
        this.status = FrameworkStatus.active;
    }

    // v11: 상속 관계 설정 (Framework.inherit() 서비스 로직에서 사용 예정)
    public void setParentFramework(Framework parent) {
        this.parentFramework = parent;
    }
}