package com.secuhub.domain.user.dto;

import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.entity.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 5-15e (v15.9) — admin 사용자 관리 + 사용자 본인 비밀번호 변경 wire shape.
 *
 * <p>FE {@code frontend/src/types/index.ts} 의 {@code User} / {@code UserBrief} /
 * {@code UserListResponse} / {@code UserCreatePayload} / {@code UserUpdatePayload}
 * shape 정합. Q5=A 결정 (BE 자체 정합 + FE shape 은 wire 검증으로 일치 강제).</p>
 *
 * <h3>비밀번호 비노출</h3>
 * <p>{@link DetailResponse} / {@link BriefResponse} 모두 {@code hashedPassword} 미포함.
 * {@link User} entity 의 {@code @Getter} 가 자동 생성한 {@code getHashedPassword()} 가
 * 있어도, {@code of()} 팩토리에서 explicit 미매핑이라 wire 에서 자연 차단.</p>
 */
public final class UserDto {

    private UserDto() {}

    // ====================================================================
    // Response DTOs
    // ====================================================================

    /**
     * 단건 상세 응답. FE {@code User} interface 정합 (id / email / name / team /
     * role / permissionEvidence / permissionVuln / status / lastLoginAt /
     * createdAt / updatedAt).
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class DetailResponse {
        private final Long id;
        private final String email;
        private final String name;
        private final String team;
        private final UserRole role;
        private final Boolean permissionEvidence;
        private final Boolean permissionVuln;
        private final UserStatus status;
        private final LocalDateTime lastLoginAt;
        private final LocalDateTime createdAt;
        private final LocalDateTime updatedAt;

        public static DetailResponse of(User u) {
            return DetailResponse.builder()
                    .id(u.getId())
                    .email(u.getEmail())
                    .name(u.getName())
                    .team(u.getTeam())
                    .role(u.getRole())
                    .permissionEvidence(u.getPermissionEvidence())
                    .permissionVuln(u.getPermissionVuln())
                    .status(u.getStatus())
                    .lastLoginAt(u.getLastLoginAt())
                    .createdAt(u.getCreatedAt())
                    .updatedAt(u.getUpdatedAt())
                    .build();
        }
    }

    /**
     * 간략 응답 (선택 박스 / 드롭다운용). FE {@code UserBrief} interface 정합
     * (id / name / email / team / role).
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class BriefResponse {
        private final Long id;
        private final String name;
        private final String email;
        private final String team;
        private final UserRole role;

        public static BriefResponse of(User u) {
            return BriefResponse.builder()
                    .id(u.getId())
                    .name(u.getName())
                    .email(u.getEmail())
                    .team(u.getTeam())
                    .role(u.getRole())
                    .build();
        }
    }

    /**
     * list 응답. FE {@code UserListResponse} interface 정합 ({@code items: User[]} +
     * {@code total: number}). Spring {@code Page} 의 일부 필드만 노출.
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class ListResponse {
        private final List<DetailResponse> items;
        private final long total;
    }

    // ====================================================================
    // Request DTOs
    // ====================================================================

    /**
     * 사용자 생성 요청. FE {@code UserCreatePayload} interface 정합.
     *
     * <p>password 는 hash 전 평문 — service 측에서 {@code passwordEncoder.encode}
     * 후 저장. 8 자 이상 100 자 이하 검증 (보안 정책 기본).</p>
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotBlank
        @Email
        @Size(max = 255)
        private String email;

        @NotBlank
        @Size(max = 100)
        private String name;

        @NotBlank
        @Size(min = 8, max = 100)
        private String password;

        @Size(max = 100)
        private String team;

        @NotNull
        private UserRole role;

        @NotNull
        private Boolean permissionEvidence;

        @NotNull
        private Boolean permissionVuln;
    }

    /**
     * 사용자 부분 수정 요청. FE {@code UserUpdatePayload} interface 정합.
     *
     * <p>모든 필드 nullable — null 이면 해당 필드 미변경 (service 측 entity
     * mutator 의 null 가드 활용). email / hashedPassword 는 본 endpoint 에서 변경
     * 불가 (별도 endpoint 로 분리 필요 시 후속 phase).</p>
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        @Size(max = 100)
        private String name;

        @Size(max = 100)
        private String team;

        private UserRole role;
        private Boolean permissionEvidence;
        private Boolean permissionVuln;
        private UserStatus status;
    }

    /**
     * 본인 비밀번호 변경 요청. FE {@code usersApi.changePassword(currentPassword,
     * newPassword)} 정합.
     *
     * <p>Q2=A 결정: 현재 비번 검증 → 새 비번 hash. service 측에서
     * {@code passwordEncoder.matches(...)} 검증 후 변경.</p>
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChangePasswordRequest {
        @NotBlank
        private String currentPassword;

        @NotBlank
        @Size(min = 8, max = 100)
        private String newPassword;
    }
}