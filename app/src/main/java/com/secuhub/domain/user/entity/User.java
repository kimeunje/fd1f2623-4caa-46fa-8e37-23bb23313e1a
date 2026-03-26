package com.secuhub.domain.user.entity;

import com.secuhub.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_email", columnList = "email"),
        @Index(name = "idx_users_role", columnList = "role"),
        @Index(name = "idx_users_status", columnList = "status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "hashed_password", nullable = false, length = 255)
    private String hashedPassword;

    @Column(length = 100)
    private String team;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UserRole role = UserRole.developer;

    @Column(name = "permission_evidence", nullable = false)
    @Builder.Default
    private Boolean permissionEvidence = false;

    @Column(name = "permission_vuln", nullable = false)
    @Builder.Default
    private Boolean permissionVuln = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UserStatus status = UserStatus.active;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    public void updateLastLogin() {
        this.lastLoginAt = LocalDateTime.now();
    }

    public void updateProfile(String name, String team) {
        if (name != null) this.name = name;
        if (team != null) this.team = team;
    }

    public void updateRole(UserRole role) {
        this.role = role;
    }

    public void updatePermissions(Boolean permissionEvidence, Boolean permissionVuln) {
        if (permissionEvidence != null) this.permissionEvidence = permissionEvidence;
        if (permissionVuln != null) this.permissionVuln = permissionVuln;
    }

    public void updateStatus(UserStatus status) {
        this.status = status;
    }

    public void changePassword(String newHashedPassword) {
        this.hashedPassword = newHashedPassword;
    }
}
