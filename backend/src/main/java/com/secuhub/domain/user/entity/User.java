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

    // Phase 3 cleanup (2026-05-04): permissionVuln 필드 + permission_vuln 컬럼 제거.
    // Phase 3 (취약점 관리) 프로젝트 외 결정 → 권한 plane 단순화.
    // Flyway forward migration `V_p3_cleanup__drop_phase3_artifacts.sql` 에서 컬럼
    // DROP. dev/test 환경 (H2 ddl-auto:create) 은 entity 수정만으로 자동 정합.

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

    /**
     * 권한 갱신 — null 이면 미변경.
     *
     * <p>Phase 3 cleanup (2026-05-04): {@code permissionVuln} 파라미터 제거.
     * 단일 권한 plane 만 유지.</p>
     */
    public void updatePermissions(Boolean permissionEvidence) {
        if (permissionEvidence != null) this.permissionEvidence = permissionEvidence;
    }

    public void updateStatus(UserStatus status) {
        this.status = status;
    }

    public void changePassword(String newHashedPassword) {
        this.hashedPassword = newHashedPassword;
    }
}