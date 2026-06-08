package com.secuhub.domain.access.dto;

import com.secuhub.domain.access.entity.IpAccessRule;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v19.1 — 계정별 IP 접근 규칙 관리 DTO (BE-2).
 *
 * <p>UserDto 패턴 정합 — 중첩 정적 클래스. 요청은 {@code @NoArgsConstructor +
 * @AllArgsConstructor}, 응답은 {@code @Builder + from(...)}.</p>
 */
public class IpAccessRuleDto {

    /**
     * 규칙 생성. cidr 필수. enabled 미지정 시 true.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotBlank(message = "IP 또는 CIDR 은 필수입니다.")
        private String cidr;

        /** 운영 메모 (선택). */
        private String description;

        /** null 이면 true(활성)로 간주. */
        private Boolean enabled;
    }

    /**
     * 규칙 부분 수정. 모든 필드 null = 변경 안 함.
     *
     * <ul>
     *   <li>{@code cidr = null} → 변경 안 함</li>
     *   <li>{@code description = null} → 변경 안 함, {@code ""} → 해제(null)</li>
     *   <li>{@code enabled = null} → 변경 안 함</li>
     * </ul>
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String cidr;
        private String description;
        private Boolean enabled;
    }

    /**
     * 규칙 응답.
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private Long userId;
        private String cidr;
        private String description;
        private boolean enabled;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static Response from(IpAccessRule rule) {
            return Response.builder()
                    .id(rule.getId())
                    .userId(rule.getUserId())
                    .cidr(rule.getCidr())
                    .description(rule.getDescription())
                    .enabled(rule.isEnabled())
                    .createdAt(rule.getCreatedAt())
                    .updatedAt(rule.getUpdatedAt())
                    .build();
        }
    }
}