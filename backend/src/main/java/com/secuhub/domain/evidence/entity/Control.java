package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 통제항목 엔티티 (구 모델).
 *
 * <h3>⚠ v14 Phase 5-14f — Deprecated</h3>
 * <p>v14 의 {@code control_nodes} 트리 모델 도입으로 본 엔티티는 폐기 예정. v15 에서
 * 테이블 + 엔티티 모두 제거.</p>
 *
 * <h3>v14 동안 처리 정책</h3>
 * <ul>
 *   <li><b>신규 INSERT 차단</b> — 5-14b 부터 {@link com.secuhub.domain.evidence.service.ControlService#create}
 *       와 {@link com.secuhub.domain.evidence.service.ExcelImportService#importControls}
 *       에서 {@code BusinessException(HttpStatus.GONE)} 즉시 throw. 안내 메시지에
 *       {@code PATCH /api/v1/frameworks/{id}/tree} 동선 포함</li>
 *   <li><b>UPDATE/DELETE 차단</b> — 5-14f 부터 {@link com.secuhub.domain.evidence.service.ControlService#update}
 *       와 {@link com.secuhub.domain.evidence.service.ControlService#delete} 도 410 Gone</li>
 *   <li><b>GET 만 leaf-only 의미 재정의</b> — 5-14f 부터 {@code findByFramework},
 *       {@code findDetail} 은 ControlNode 의 leaf 만 반환하도록 위임</li>
 *   <li><b>{@code @OneToMany evidenceTypes} 매핑 제거</b> — 5-14f 부터 {@link EvidenceType#control}
 *       의 타입이 {@link ControlNode} 로 변경됨. JPA 의 {@code mappedBy="control"} 양방향
 *       관계가 ControlNode 측으로 이전됨 ({@link ControlNode#getEvidenceTypes()}).
 *       Control 측에서는 더 이상 표현하지 않음 (lazy load 해도 빈 리스트가 자연)</li>
 *   <li><b>{@code @OneToMany evidenceTypes 제거의 의미</b> — controls 테이블 자체는
 *       v14 동안 read-only 로 유지 (V6 이주 후 rollback 안전망). evidence_types 가
 *       이 테이블의 row 를 안 가리키므로 매핑 제거가 자연</li>
 *   <li><b>Builder 와 일부 메서드는 보존</b> — 기존 테스트 / FrameworkInheritanceTest 등이
 *       {@code Control.builder()} 를 사용 중. 5-14f 회귀 픽스에서 ControlNode 빌더로
 *       전환되지만 일부 호환을 위해 시그니처 보존</li>
 * </ul>
 *
 * @deprecated v14 Phase 5-14f. v15 에서 제거 예정. 신규 코드는 {@link ControlNode}
 *     ({@code node_type='control'} leaf) 직접 사용.
 */
@Deprecated(since = "v14 Phase 5-14f", forRemoval = true)
@Entity
@Table(name = "controls", indexes = {
        @Index(name = "idx_controls_code", columnList = "code")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Control extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "framework_id", nullable = false)
    private Framework framework;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(length = 200)
    private String domain;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    // ✂ v14 Phase 5-14f 제거 — EvidenceType.control 의 타입이 ControlNode 로 변경됨.
    //    mappedBy="control" 의 대상이 더 이상 본 엔티티가 아니라 ControlNode.evidenceTypes 임.
    //    @OneToMany(mappedBy = "control", cascade = CascadeType.ALL, orphanRemoval = true)
    //    @Builder.Default
    //    private List<EvidenceType> evidenceTypes = new ArrayList<>();

    /**
     * @deprecated v14 Phase 5-14f. 신규 호출 금지.
     */
    @Deprecated(since = "v14 Phase 5-14f", forRemoval = true)
    void setFramework(Framework framework) {
        this.framework = framework;
    }

    /**
     * @deprecated v14 Phase 5-14f. 통제 수정은 {@code PATCH /api/v1/frameworks/{id}/tree}
     *     (TreeUpdateService) 또는 {@link ControlNode#update} 사용.
     */
    @Deprecated(since = "v14 Phase 5-14f", forRemoval = true)
    public void update(String code, String domain, String name, String description) {
        if (code != null) this.code = code;
        if (domain != null) this.domain = domain;
        if (name != null) this.name = name;
        if (description != null) this.description = description;
    }

    // ✂ v14 Phase 5-14f 제거 — EvidenceType.control 이 ControlNode 를 가리키므로 신규 호출 불가.
    //    public void addEvidenceType(EvidenceType evidenceType) { ... }
    //    대체: ControlNode.addEvidenceType(EvidenceType) — leaf 노드에만 호출.
}