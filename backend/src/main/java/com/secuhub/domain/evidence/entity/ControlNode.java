package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.ArrayList;
import java.util.List;

/**
 * v14 Phase 5-14a — 통제 트리 노드 (자기참조).
 *
 * <p>spec v14 §3.3.1.3 데이터 모델 (옵션 D):</p>
 * <ul>
 *   <li>임의 depth (1~10) 자기참조 트리</li>
 *   <li>{@link NodeType#category} = branch (자식 노드 가짐, evidence_types 직접 매달 수 없음)</li>
 *   <li>{@link NodeType#control} = leaf (자식 노드 못 가짐, evidence_types 의 부모)</li>
 *   <li>같은 부모 안에 leaf 와 category 가 sibling 으로 공존 가능 (mixed-depth)</li>
 *   <li>{@code ON DELETE CASCADE} — 부모 삭제 시 자손 노드 + 매달린 evidence_types 까지 자동 삭제</li>
 * </ul>
 *
 * <p><b>5-14a 범위 주의</b>: 본 phase 에서는 트리 구조만 도입한다.
 * leaf 노드의 {@code evidenceTypes OneToMany} 매핑은 5-14f 에서
 * {@code EvidenceType.control_id} 가 leaf control_node id 를 가리키도록 이주할 때 함께 추가된다.</p>
 *
 * <p><b>제약 표현 (dev/test ddl-auto vs prod Flyway 의미 일치):</b></p>
 * <ul>
 *   <li>{@code @OnDelete(CASCADE)} — Hibernate ddl-auto 에서도 ON DELETE CASCADE FK 생성 보장</li>
 *   <li>{@code @Check(constraints = "...")} — Hibernate ddl-auto 에서도 CHECK 제약 생성 보장</li>
 *   <li>두 환경 모두 동일 동작 (NotificationPreference 패턴 정합)</li>
 * </ul>
 */
@Entity
@Table(
    name = "control_nodes",
    indexes = {
        @Index(name = "idx_cn_framework", columnList = "framework_id"),
        @Index(name = "idx_cn_parent", columnList = "parent_id"),
        @Index(name = "idx_cn_framework_depth", columnList = "framework_id, depth"),
        @Index(name = "idx_cn_code", columnList = "framework_id, code")
    }
)
@Check(name = "chk_cn_node_type", constraints = "node_type IN ('category', 'control')")
@Check(name = "chk_cn_depth",     constraints = "depth BETWEEN 1 AND 10")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ControlNode extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 소속 Framework. Framework 삭제 시 ON DELETE CASCADE 로 자동 삭제.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "framework_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Framework framework;

    /**
     * 부모 노드. NULL 이면 framework 직속 (depth=1).
     * 부모 삭제 시 ON DELETE CASCADE 로 자손 일괄 삭제.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ControlNode parent;

    /**
     * 직속 자식들. JPA 메모리 레벨 cascade 도 함께 적용 (영속성 컨텍스트 일관성).
     * 실제 cascading delete 의 권위는 DB FK 의 ON DELETE CASCADE.
     */
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ControlNode> children = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 20)
    private NodeType nodeType;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /**
     * 캐싱 컬럼: parent.depth + 1 (parent IS NULL 이면 1).
     * 서비스 레이어가 INSERT 시점에 일관성 보장 (5-14d).
     * 1~10 범위로 CHECK 제약.
     */
    @Column(nullable = false)
    private int depth;

    // ============================================================
    // 메서드
    // ============================================================

    /**
     * 부모-자식 양방향 연결. 5-14d 의 트리 PATCH 에서 사용 예정.
     */
    public void addChild(ControlNode child) {
        this.children.add(child);
        child.setParent(this);
    }

    /**
     * package-private — 양방향 동기화용. 외부 코드는 {@link #addChild} 사용 권장.
     */
    void setParent(ControlNode parent) {
        this.parent = parent;
    }

    /**
     * package-private — Framework 상속 복제 (5-14f) 등에서 framework 재지정용.
     */
    void setFramework(Framework framework) {
        this.framework = framework;
    }

    /**
     * 노드 메타 수정. null 인 필드는 변경하지 않음 (PATCH 의미).
     * 5-14d 트리 PATCH 의 {@code updated} 액션에서 사용 예정.
     */
    public void update(String code, String name, String description, Integer displayOrder, Integer depth) {
        if (code != null) this.code = code;
        if (name != null) this.name = name;
        if (description != null) this.description = description;
        if (displayOrder != null) this.displayOrder = displayOrder;
        if (depth != null) this.depth = depth;
    }

    /**
     * leaf↔category 변환. 호출 측에서 변환 가능 여부를 검증해야 함 (5-14d):
     * <ul>
     *   <li>leaf → category: evidence_types 가 0 건일 때만</li>
     *   <li>category → leaf: 자식 노드가 0 건일 때만</li>
     * </ul>
     */
    public void convertNodeType(NodeType newType) {
        this.nodeType = newType;
    }

    /**
     * 분류(branch) 노드인지.
     */
    public boolean isCategory() {
        return this.nodeType == NodeType.category;
    }

    /**
     * 통제(leaf) 노드인지.
     */
    public boolean isLeaf() {
        return this.nodeType == NodeType.control;
    }
}