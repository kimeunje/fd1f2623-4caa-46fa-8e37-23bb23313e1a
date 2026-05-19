package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "job_executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class JobExecution extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private CollectionJob job;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.running;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * v18.7 — selenium wrapper 산출 _diagnosis.json 의 전체 내용.
     *
     * <p>schema_version 1.0 — execution / scenario / steps[] / diagnosis 의 4 영역.
     * status=failed 시점에 채워짐 (성공 실행도 단계별 시간 추적용으로 채워질 수 있음).</p>
     *
     * <p>스크린샷 / page_source 는 본 필드에 포함되지 않음. output 디렉토리
     * ({storagePath}/output/{jobId}/{executionId}/) 안의 표준 파일명
     * (_diag_screenshot.png / _diag_page_source.html) 으로 별도 보관.</p>
     *
     * <p>L_RESPONSIBILITY_SEPARATION — EvidenceAsset / EvidenceFile 시스템 미관여.
     * 진단 정보는 JobExecution 의 책임 (실행 수명과 동일).</p>
     */
    @Column(name = "error_diagnosis", columnDefinition = "JSON")
    private String errorDiagnosis;

    public void markSuccess() {
        this.status = ExecutionStatus.success;
        this.finishedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = ExecutionStatus.failed;
        this.errorMessage = errorMessage;
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * v18.7 — 진단 JSON 보관용 setter.
     *
     * <p>Lombok @Setter 가 클래스 레벨에 없으므로 명시 setter 추가. ScriptExecutionService
     * 의 collectDiagnosis 메서드가 호출.</p>
     */
    public void setErrorDiagnosis(String errorDiagnosis) {
        this.errorDiagnosis = errorDiagnosis;
    }
}