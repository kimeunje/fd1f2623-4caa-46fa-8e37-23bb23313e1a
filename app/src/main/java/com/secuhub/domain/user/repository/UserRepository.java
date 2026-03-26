package com.secuhub.domain.user.repository;

import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByRole(UserRole role);

    List<User> findByRoleAndStatus(UserRole role, UserStatus status);

    List<User> findByTeamAndStatus(String team, UserStatus status);

    @Query("SELECT u FROM User u WHERE u.role IN :roles AND u.status = :status")
    List<User> findByRolesAndStatus(@Param("roles") List<UserRole> roles, @Param("status") UserStatus status);

    @Query("""
        SELECT u FROM User u
        WHERE (:role IS NULL OR u.role = :role)
          AND (:status IS NULL OR u.status = :status)
          AND (:search IS NULL OR u.name LIKE %:search% OR u.email LIKE %:search%)
        """)
    Page<User> findByFilters(
            @Param("role") UserRole role,
            @Param("status") UserStatus status,
            @Param("search") String search,
            Pageable pageable
    );
}
