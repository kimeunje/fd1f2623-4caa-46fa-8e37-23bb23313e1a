package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.ScriptVersion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * v19.4 — 스크립트 버전 이력 응답 DTO (carry-over ⑤).
 */
public class ScriptVersionDto {

    /** 버전 목록용 — 본문 제외 메타데이터. */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class VersionResponse {
        private int versionNo;
        private Long contentSize;
        private String note;
        private Long createdBy;
        private LocalDateTime createdAt;

        public static VersionResponse from(ScriptVersion v) {
            return VersionResponse.builder()
                    .versionNo(v.getVersionNo())
                    .contentSize(v.getContentSize())
                    .note(v.getNote())
                    .createdBy(v.getCreatedBy())
                    .createdAt(v.getCreatedAt())
                    .build();
        }
    }

    /** 단일 버전 내용 (미리보기/diff용). */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class VersionContentResponse {
        private int versionNo;
        private String content;
        private Long contentSize;
        private String note;
        private LocalDateTime createdAt;

        public static VersionContentResponse from(ScriptVersion v) {
            return VersionContentResponse.builder()
                    .versionNo(v.getVersionNo())
                    .content(v.getContent())
                    .contentSize(v.getContentSize())
                    .note(v.getNote())
                    .createdAt(v.getCreatedAt())
                    .build();
        }
    }
}