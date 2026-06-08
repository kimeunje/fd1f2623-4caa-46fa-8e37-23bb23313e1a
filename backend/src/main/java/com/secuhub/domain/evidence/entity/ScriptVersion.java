package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * v19.4 — 스크립트 버전 이력 (carry-over ⑤).
 *
 * <p>스크립트를 저장할 때마다 그 시점의 내용을 한 장씩 스냅샷으로 보관한다.
 * 현재 실행본은 기존대로 {@code {base-dir}/{uuid}.py} 파일에 그대로 두고(실행 경로 무변경),
 * 모든 버전 내용은 본 테이블(DB)에 적재한다. 롤백은 옛 버전 내용을 복사한 "새 버전"을
 * 만들어 전진하므로(이력 불변) "실행 파일 = 항상 최신 versionNo" 불변식이 유지된다.</p>
 *
 * <p>{@code script_id} 는 plain FK (DB FK + ON DELETE CASCADE 는 Flyway). 스크립트가
 * 삭제되면 버전 이력도 함께 정리된다.</p>
 */
@Entity
@Table(
        name = "script_versions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_script_versions_script_no",
                columnNames = {"script_id", "version_no"}),
        indexes = @Index(name = "idx_script_versions_script", columnList = "script_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ScriptVersion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 대상 스크립트. plain FK. */
    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    /** 스크립트별 1부터 증가하는 버전 번호. (script_id, version_no) 유니크. */
    @Column(name = "version_no", nullable = false)
    private int versionNo;

    /** 해당 버전의 스크립트 본문 스냅샷. */
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    /** 본문 byte 크기 (UTF-8). */
    @Column(name = "content_size", nullable = false)
    @Builder.Default
    private Long contentSize = 0L;

    /** 선택적 변경 메모 (예: "selector 수정", "v2 내용으로 복귀"). */
    @Column(length = 200)
    private String note;

    /** 이 버전을 만든 사용자 id (SecurityContext 기반, 없으면 null). */
    @Column(name = "created_by")
    private Long createdBy;
}