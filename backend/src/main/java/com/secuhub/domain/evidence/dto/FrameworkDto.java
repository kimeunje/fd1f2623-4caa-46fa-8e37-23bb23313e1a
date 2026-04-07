package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.Framework;
import lombok.*;

public class FrameworkDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @jakarta.validation.constraints.NotBlank(message = "프레임워크 이름은 필수입니다.")
        private String name;
        private String description;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String name;
        private String description;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private String name;
        private String description;
        private int controlCount;
        private String createdAt;

        public static Response from(Framework entity) {
            return Response.builder()
                    .id(entity.getId())
                    .name(entity.getName())
                    .description(entity.getDescription())
                    .controlCount(entity.getControls() != null ? entity.getControls().size() : 0)
                    .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null)
                    .build();
        }
    }
}
