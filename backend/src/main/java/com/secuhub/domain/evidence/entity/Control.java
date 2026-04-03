package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

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

    @OneToMany(mappedBy = "control", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<EvidenceType> evidenceTypes = new ArrayList<>();

    void setFramework(Framework framework) {
        this.framework = framework;
    }

    public void update(String code, String domain, String name, String description) {
        if (code != null) this.code = code;
        if (domain != null) this.domain = domain;
        if (name != null) this.name = name;
        if (description != null) this.description = description;
    }

    public void addEvidenceType(EvidenceType evidenceType) {
        this.evidenceTypes.add(evidenceType);
        evidenceType.setControl(this);
    }
}
