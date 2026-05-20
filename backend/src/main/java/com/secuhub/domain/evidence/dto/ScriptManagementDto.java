package com.secuhub.domain.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v18.8.2 — 스크립트 관리 DTO (UID 기반).
 *
 * <p>사용자 의도: "스크립트 이름은 의미 없다. 내용만." → filename 입력 제거, BE 자동 id 부여.</p>
 *
 * <p>EvidenceAsset 패턴 정합 — 사용자가 보는 표면 (작업 name) 과 내부 저장 (script id) 분리.</p>
 */
public class ScriptManagementDto {

    /**
     * 신규 작성 요청 — content 만 필수. id 와 file_path 는 BE 자동 부여.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotBlank(message = "스크립트 내용은 필수입니다.")
        private String content;
    }

    /**
     * 기존 스크립트 수정 요청 — content 만. id 는 URL path 에서 받음.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        @NotBlank(message = "스크립트 내용은 필수입니다.")
        private String content;
    }

    /**
     * 스크립트 응답 — id + 내용 + 메타.
     *
     * <p>file_path 는 BE 내부용이라 응답에 노출 안 함 (사용자 입장에서 id 만 식별).</p>
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class ScriptResponse {
        private Long id;
        private String content;
        private Long contentSize;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}