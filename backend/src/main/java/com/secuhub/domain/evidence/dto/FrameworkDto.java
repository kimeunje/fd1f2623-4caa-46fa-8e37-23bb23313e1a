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

    /**
     * v11 Phase 5-6 — Framework 상속 요청 DTO.
     *
     * <p>기존 Framework 의 구조(통제/증빙유형/수집작업)를 스냅샷 복제하여
     * 새 Framework 를 생성한다. 파일·실행이력은 복제하지 않는다.</p>
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InheritRequest {
        @jakarta.validation.constraints.NotNull(message = "상속받을 원본 Framework ID는 필수입니다.")
        private Long sourceFrameworkId;

        @jakarta.validation.constraints.NotBlank(message = "새 프레임워크 이름은 필수입니다.")
        private String name;

        private String description;
    }

    /**
     * Framework 목록/상세 응답 DTO.
     *
     * <p>v11 Phase 5-3 에서 목록 페이지(FrameworkListView)에 필요한 집계 필드를 추가했다:</p>
     * <ul>
     *   <li>{@code status} — active / archived</li>
     *   <li>{@code parentFrameworkId / Name} — 상속 관계 표시</li>
     *   <li>{@code controlCount / evidenceTypeCount / jobCount} — 행별 카운트 3종</li>
     *   <li>{@code pendingReviewCount} — 검토 대기 배지</li>
     * </ul>
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private String name;
        private String description;
        private String createdAt;

        // v11 Phase 5-3
        private String status;                  // "active" | "archived"
        private Long parentFrameworkId;
        private String parentFrameworkName;
        private int controlCount;
        private int evidenceTypeCount;
        private int jobCount;
        private long pendingReviewCount;

        /** 단순 응답 (생성/수정 직후 등). 집계 필드는 0 으로 채워진다. */
        public static Response from(Framework entity) {
            return builderBase(entity).build();
        }

        /** Phase 5-3 집계 포함 응답 (목록/상세 API 용). */
        public static Response from(Framework entity,
                                    int controlCount,
                                    int evidenceTypeCount,
                                    int jobCount,
                                    long pendingReviewCount) {
            return builderBase(entity)
                    .controlCount(controlCount)
                    .evidenceTypeCount(evidenceTypeCount)
                    .jobCount(jobCount)
                    .pendingReviewCount(pendingReviewCount)
                    .build();
        }

        private static ResponseBuilder builderBase(Framework entity) {
            Framework parent = entity.getParentFramework();
            return Response.builder()
                    .id(entity.getId())
                    .name(entity.getName())
                    .description(entity.getDescription())
                    .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null)
                    .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                    .parentFrameworkId(parent != null ? parent.getId() : null)
                    .parentFrameworkName(parent != null ? parent.getName() : null);
        }
    }
}