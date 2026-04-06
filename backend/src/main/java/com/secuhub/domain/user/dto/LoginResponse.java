package com.secuhub.domain.user.dto;

import com.secuhub.domain.user.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private UserInfo user;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class UserInfo {
        private Long id;
        private String email;
        private String name;
        private String team;
        private String role;
        private Boolean permissionEvidence;
        private Boolean permissionVuln;
    }

    public static LoginResponse of(String token, User user) {
        return LoginResponse.builder()
                .token(token)
                .user(UserInfo.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .name(user.getName())
                        .team(user.getTeam())
                        .role(user.getRole().name())
                        .permissionEvidence(user.getPermissionEvidence())
                        .permissionVuln(user.getPermissionVuln())
                        .build())
                .build();
    }
}
