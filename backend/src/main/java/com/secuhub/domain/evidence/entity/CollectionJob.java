package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.ArrayList;
import java.util.List;

/**
 * 수집 작업 (Collection Job) 엔티티.
 *
 * <h3>v18.3 — {@code @OnDelete(CASCADE)} 추가 (L_SPEC_SCHEMA_MISMATCH 종결)</h3>
 * <p>v18.2 fix 부산 발견 — DB FK ({@code FK30ebo0xryo4qm4o059ea7cjls}) 가 default
 * RESTRICT 였음. EvidenceType 삭제 시 매달린 CollectionJob cascade 삭제 필요.
 * {@code evidence_type_id} 컬럼 자체는 nullable (전역 작업 허용) — CASCADE 는 NULL
 * 행에 영향 없음 (FK 검사 자체가 skip).</p>
 */
@Entity
@Table(name = "collection_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CollectionJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 30)
    private JobType jobType;

    @Column(name = "script_path", length = 1000)
    private String scriptPath;

    /**
     * v18.3 — {@code @OnDelete(CASCADE)} 추가.
     * EvidenceType 삭제 시 매달린 CollectionJob 도 자동 삭제.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_type_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private EvidenceType evidenceType;

    @Column(name = "schedule_cron", length = 100)
    private String scheduleCron;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<JobExecution> executions = new ArrayList<>();

    public void update(String name, String description, String scriptPath, String scheduleCron) {
        if (name != null) this.name = name;
        if (description != null) this.description = description;
        if (scriptPath != null) this.scriptPath = scriptPath;
        if (scheduleCron != null) this.scheduleCron = scheduleCron;
    }

    public void activate() {
        this.isActive = true;
    }

    public void deactivate() {
        this.isActive = false;
    }
}