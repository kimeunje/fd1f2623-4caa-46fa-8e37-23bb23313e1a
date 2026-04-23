package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.Control;
import com.secuhub.domain.evidence.entity.EvidenceType;
import lombok.*;

import java.util.List;

public class ControlDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @jakarta.validation.constraints.NotBlank(message = "통제항목 코드는 필수입니다.")
        private String code;
        private String domain;
        @jakarta.validation.constraints.NotBlank(message = "통제항목 이름은 필수입니다.")
        private String name;
        private String description;
        private List<EvidenceTypeRequest> evidenceTypes;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String code;
        private String domain;
        private String name;
        private String description;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceTypeRequest {
        @jakarta.validation.constraints.NotBlank(message = "증빙 유형 이름은 필수입니다.")
        private String name;
        private String description;
    }

    /**
     * 통제항목 목록/생성/수정 응답 DTO.
     *
     * <p>v11 Phase 5-9 에서 Framework 상세 페이지의 행 단위 "검토 대기 N건" 배지를 위해
     * {@code pendingReviewCount} 필드를 추가했다. FrameworkDto.Response 와 동일한 규약:
     * 집계가 필요 없는 경로(생성/수정 직후)에서는 0 으로 채워진다.</p>
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private Long frameworkId;
        private String code;
        private String domain;
        private String name;
        private String description;
        private int evidenceTotal;
        private int evidenceCollected;
        private String status;
        private String createdAt;

        // v11 Phase 5-9 — 통제항목 행 단위 검토 대기 배지
        private long pendingReviewCount;

        /**
         * 기존 2-인자 팩토리 유지 (생성/수정 경로 호환). pendingReviewCount=0 으로 채움.
         */
        public static Response from(Control entity, int collected) {
            return from(entity, collected, 0L);
        }

        /**
         * v11 Phase 5-9 — 집계 포함 팩토리. 목록 조회에서 사용.
         */
        public static Response from(Control entity, int collected, long pendingReviewCount) {
            int total = entity.getEvidenceTypes() != null ? entity.getEvidenceTypes().size() : 0;
            String status;
            if (total == 0) status = "미수집";
            else if (collected >= total) status = "완료";
            else if (collected > 0) status = "진행중";
            else status = "미수집";

            return Response.builder()
                    .id(entity.getId())
                    .frameworkId(entity.getFramework().getId())
                    .code(entity.getCode())
                    .domain(entity.getDomain())
                    .name(entity.getName())
                    .description(entity.getDescription())
                    .evidenceTotal(total)
                    .evidenceCollected(collected)
                    .status(status)
                    .createdAt(entity.getCreatedAt() != null ?
                            entity.getCreatedAt().toString() : null)
                    .pendingReviewCount(pendingReviewCount)
                    .build();
        }
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class DetailResponse {
        private Long id;
        private Long frameworkId;
        private String code;
        private String domain;
        private String name;
        private String description;
        private int evidenceTotal;
        private int evidenceCollected;
        private String status;
        private List<EvidenceTypeResponse> evidenceTypes;
        private String createdAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class EvidenceTypeResponse {
        private Long id;
        private String name;
        private String description;
        private boolean collected;
        private List<EvidenceFileDto.Response> files;

        public static EvidenceTypeResponse from(EvidenceType et, List<EvidenceFileDto.Response> files) {
            return EvidenceTypeResponse.builder()
                    .id(et.getId())
                    .name(et.getName())
                    .description(et.getDescription())
                    .collected(files != null && !files.isEmpty())
                    .files(files)
                    .build();
        }
    }
}