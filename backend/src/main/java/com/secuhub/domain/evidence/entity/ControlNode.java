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
 *   <li>{@link NodeType#category} = branch 의 의미 (전통적 "분류")</li>
 *   <li>{@link NodeType#control} = leaf 의 의미 (전통적 "통제")</li>
 *   <li>같은 부모 안에 leaf 와 category 가 sibling 으로 공존 가능 (mixed-depth)</li>
 *   <li>{@code ON DELETE CASCADE} — 부모 삭제 시 자손 노드 + 매달린 evidence_types 까지 자동 삭제</li>
 * </ul>
 *
 * <h3>v15 Phase 5-15a — Hybrid 모델 채택 (mutex 폐기)</h3>
 * <p>spec §3.3.1.9: leaf↔category mutex 폐기. 모든 노드가 자식 + 증빙 동시 보유 가능
 * (hybrid). {@code node_type} enum 은 v15 5-15a 시점에 보존 (의미 약화) — v15 5-15b
 * 또는 그 이후에 일괄 제거 검토.</p>
 *
 * <h3>v15 Phase 5-15c (v15.7) — EvidenceType.controlNode 정합 갱신</h3>
 * <p>{@link #evidenceTypes} 의 {@code @OneToMany(mappedBy="control")} → {@code mappedBy="controlNode"}
 * (EvidenceType 의 자바 필드명 변경 정합 — Q1=B). 누락 시 startup 시
 * {@code MappingException}. {@link #addEvidenceType} 안의 양방향 동기화도
 * {@code setControlNode} 로 정합.</p>
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

    /**
     * v19.26 — 인수인계 노트(description)를 마지막으로 수정한 사람의 이름(박제).
     * FK 아님 — 작성 시점 이름을 그대로 저장해 퇴사/개명과 무관하게 "당시 누가" 를 보존.
     * description 텍스트가 실제로 바뀔 때만 갱신 ({@link #update} 참조). NULL 허용.
     */
    @Column(name = "description_updated_by_name", length = 100)
    private String descriptionUpdatedByName;

    /**
     * v19.26 — 인수인계 노트 최종 수정 시각. description 실제 변경 시에만 갱신. NULL 허용.
     */
    @Column(name = "description_updated_at")
    private java.time.LocalDateTime descriptionUpdatedAt;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /**
     * 캐싱 컬럼: parent.depth + 1 (parent IS NULL 이면 1).
     * 서비스 레이어가 INSERT 시점에 일관성 보장 (5-14d).
     * 1~10 범위로 CHECK 제약.
     */
    @Column(nullable = false)
    private int depth;

    /**
     * v14 Phase 5-14a 에서 의도적으로 미포함 → Phase 5-14f 에서 추가.
     *
     * <p>v14 5-14f 시점: leaf ({@code node_type='control'}) 일 때만 의미 있음.
     * v15 5-15a 시점 (hybrid 모델 채택 후): 모든 노드가 evidence_types 보유 가능
     * (category 노드도 자체 증빙 + 자식 동시 보유). {@link EvidenceType#getControlNode()}
     * 의 mappedBy 대상.</p>
     *
     * <p>v15 Phase 5-15c (v15.7): {@code mappedBy = "control"} → {@code mappedBy = "controlNode"}
     * (EvidenceType 의 자바 필드명 정합).</p>
     *
     * <p>cascade ALL + orphanRemoval — 노드 삭제 시 매달린 evidence_types 도 함께
     * 삭제. DB 레벨 ON DELETE CASCADE 와 정합 (V6 Step 3c).</p>
     */
    @jakarta.persistence.OneToMany(mappedBy = "controlNode",
            cascade = jakarta.persistence.CascadeType.ALL,
            orphanRemoval = true)
    @lombok.Builder.Default
    private java.util.List<EvidenceType> evidenceTypes = new java.util.ArrayList<>();

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
     * 5-14d 트리 PATCH 의 {@code updated} 액션에서 사용.
     *
     * <p>{@code TreeUpdateService} 의 updated 적용은 code/name/description 만
     * 받으므로 displayOrder/depth 에 null 을 전달한다 — 위치 변경은 별도
     * {@link #move(ControlNode, int, int)} 책임.</p>
     *
     * <p>v19.26 — {@code editorName}: 인수인계 노트 작성자(박제). description 이
     * <b>실제로 바뀔 때만</b> descriptionUpdatedByName/At 을 갱신한다. 코드/이름만
     * 수정하거나(description=null) 값이 이전과 동일하면 작성자/수정일은 불변 —
     * "누가 언제 노트를 고쳤는지"의 의미를 정확히 유지한다. BE 가 실제 변경 여부의
     * 최종 판정자이므로 FE dirty 상태와 무관하게 안전하다.</p>
     */
    public void update(String code, String name, String description,
                       Integer displayOrder, Integer depth, String editorName) {
        if (code != null) this.code = code;
        if (name != null) this.name = name;
        if (description != null && !description.equals(this.description)) {
            this.description = description;
            this.descriptionUpdatedByName = editorName;
            this.descriptionUpdatedAt = java.time.LocalDateTime.now();
        }
        if (displayOrder != null) this.displayOrder = displayOrder;
        if (depth != null) this.depth = depth;
    }

    /**
     * 5-14d — moved 액션의 위치 갱신.
     *
     * <p>{@code framework} 필드는 package-private setter 만 노출되므로 본 메서드는
     * 같은 framework 안에서의 이동만 처리한다 (트리 PATCH 의 의미와 정합).</p>
     *
     * <p>호출 측 ({@code TreeUpdateService}) 책임:</p>
     * <ul>
     *   <li>v14 5-14d: newParent 가 leaf (control) 가 아님을 사전 검증.
     *       <b>v15 5-15a hybrid 모델 채택 후 본 검증 제거</b> (모든 노드가 자식 보유 가능)</li>
     *   <li>newParent 가 자기 자신의 자손이 아님을 사전 검증 (사이클 방지)</li>
     *   <li>newDepth 가 newParent.depth + 1 과 일치 (또는 newParent=null 이면 1)</li>
     *   <li>newDepth 가 1~10 범위</li>
     * </ul>
     *
     * @param newParent 새 부모 (null = framework 직속, depth=1)
     * @param newDisplayOrder 같은 부모 안 새 정렬 인덱스
     * @param newDepth 새 depth (parent.depth + 1, 또는 parent=null 이면 1)
     */
    public void move(ControlNode newParent, int newDisplayOrder, int newDepth) {
        this.parent = newParent;
        this.displayOrder = newDisplayOrder;
        this.depth = newDepth;
    }

    /**
     * leaf↔category 변환.
     *
     * <p>v14 5-14d 시점: 호출 측에서 변환 가능 여부를 검증해야 함:</p>
     * <ul>
     *   <li>leaf → category: evidence_types 가 0 건일 때만</li>
     *   <li>category → leaf: 자식 노드가 0 건일 때만</li>
     * </ul>
     *
     * <p>v15 5-15a (hybrid) 시점: 의미 약화. mutex 가 아니므로 변환 자체가 자연.
     * v15 5-15b 또는 그 이후 enum 일괄 제거 시 본 메서드도 함께 정리 검토.</p>
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

    /**
     * v14 Phase 5-14f → v15 Phase 5-15a — 모든 노드에 증빙 유형을 매단다.
     *
     * <p><b>v15 5-15a (hybrid 모델 채택)</b>: 5-14f 의 leaf-only 가드
     * (category 호출 시 {@code IllegalStateException}) 제거. 모든 노드가
     * evidence_types 직접 매달림 가능 (spec §3.3.1.9 hybrid). 데이터 모델상
     * 기존 mutex 데이터 (leaf 만 보유) 는 hybrid 의 부분집합이므로 BC 영향 0.
     * {@code node_type} 컬럼 자체는 v15 5-15b 까지 보존 (의미만 약화).</p>
     *
     * <p>5-14d 의 PATCH /tree 검증 규칙 #9 (leaf-with-evidence 422) 도
     * 본 가드 제거와 동시에 자연 무력화 — 진입점이 본 메서드뿐이므로
     * TreeUpdateService 측의 별도 변경 (parent_must_be_category 제거) 과 함께
     * 5-15a B 단계에서 처리.</p>
     *
     * <p>v15 Phase 5-15c (v15.7): {@code evidenceType.setControl(this)} →
     * {@code evidenceType.setControlNode(this)} (EvidenceType 의 setter 명 변경 정합).</p>
     */
    public void addEvidenceType(EvidenceType evidenceType) {
        // v15 Phase 5-15a — hybrid 모델: leaf-only 가드 제거.
        // spec §3.3.1.9: 모든 노드가 evidence + 자식 동시 보유 가능.
        this.evidenceTypes.add(evidenceType);
        evidenceType.setControlNode(this);   // v15.7: setControl → setControlNode
    }
}