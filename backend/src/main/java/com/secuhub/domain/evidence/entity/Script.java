package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * v18.8.2 — Python 스크립트 entity (UID 기반).
 *
 * <p>스크립트 이름은 의미 없음 — 사용자는 작성한 내용만 신경 씀. 시스템이 자동으로
 * id 부여 + {@code {app.scripts.base-dir}/{id}.py} 파일 시스템 매핑.</p>
 *
 * <p>EvidenceAsset 패턴 정합 — 표면 (사용자 보는 이름) 과 내부 저장 (id + file_path)
 * 분리.</p>
 *
 * <h3>relationship</h3>
 * <p>CollectionJob → Script = N:1 (1:N — 다른 작업이 같은 스크립트 공유 가능, Q1=A).
 * FK ON DELETE SET NULL — 스크립트 삭제 시 의존 작업의 script_id 만 null 로.</p>
 *
 * <h3>legacy fallback</h3>
 * <p>CollectionJob.scriptPath (옛 컬럼) 도 유지. ScriptExecutionService 의
 * resolveScriptPath = script_id 우선, 없으면 script_path fallback (Q2=A).</p>
 */
@Entity
@Table(name = "scripts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Script extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 상대 경로 (filename only) — 예: "12.py".
     *
     * <p>app.scripts.base-dir 와 결합하여 절대 경로 도출. 운영 환경 이전 시
     * base-dir 만 갱신하면 됨 (절대 경로 hardcode 회피).</p>
     */
    @Column(name = "file_path", nullable = false, length = 255)
    private String filePath;

    /** 파일 크기 (bytes) — UTF-8 byte 기준. */
    @Column(name = "content_size", nullable = false)
    @Builder.Default
    private Long contentSize = 0L;

    /**
     * 파일 크기 갱신 — update 시점에 호출 (Lombok @Setter 미적용).
     */
    public void setContentSize(Long contentSize) {
        this.contentSize = contentSize;
    }
}