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

        public static Response from(Control entity, int collected) {
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
                    .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null)
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
