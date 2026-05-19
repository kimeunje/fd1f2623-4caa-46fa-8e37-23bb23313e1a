package com.secuhub.domain.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * v18.8 — 스크립트 관리 (어드민 UI 만으로 Python 스크립트 등록/수정).
 *
 * <p>v18.7 의 진단 패널 "수정 스크립트 업로드" 버튼 + 작업 등록 다이얼로그의
 * "스크립트 작성" 버튼이 본 DTO 활용.</p>
 *
 * <p>책임 분리 (L_RESPONSIBILITY_SEPARATION) — ScriptManagementService 가 파일 I/O
 * 책임만. CollectionJob.scriptPath 와 매핑은 그대로.</p>
 */
public class ScriptManagementDto {

    /**
     * 신규 업로드 요청.
     *
     * <p>filename = 확장자 .py 필수, path traversal 방지 (영문/숫자/언더스코어/하이픈/점만).
     * content = UTF-8 Python 소스 코드 (최대 1MB).</p>
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadRequest {
        @NotBlank(message = "파일명은 필수입니다.")
        @Pattern(
                regexp = "^[a-zA-Z0-9_.-]+\\.py$",
                message = "파일명은 영문/숫자/언더스코어/하이픈/점만 허용되며 .py 확장자여야 합니다."
        )
        private String filename;

        @NotBlank(message = "스크립트 내용은 필수입니다.")
        private String content;
    }

    /**
     * 기존 스크립트 수정 요청.
     *
     * <p>filename = URL path 에서 받으므로 본 DTO 에는 content 만.</p>
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        @NotBlank(message = "스크립트 내용은 필수입니다.")
        private String content;
    }

    /**
     * 스크립트 목록 응답 1 row.
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class ScriptInfo {
        private String filename;
        private long size;                  // bytes
        private LocalDateTime lastModified; // 파일 시스템 mtime
        private String scriptPath;          // CollectionJob.scriptPath 와 매핑 가능한 절대/상대 경로
    }

    /**
     * 스크립트 내용 조회 응답.
     *
     * <p>인라인 편집기 (textarea) 가 본 응답의 content 를 그대로 표시.</p>
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class ScriptContent {
        private String filename;
        private String content;
        private long size;
        private LocalDateTime lastModified;
    }

    /**
     * 스크립트 목록 응답 wrapper.
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class ListResponse {
        private List<ScriptInfo> scripts;
    }
}