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

/**
 * UserRepository — Spring Data JPA.
 *
 * <h3>v15.9 (Phase 5-15e) 변경</h3>
 * <ul>
 *   <li>신규 derived 메서드: {@link #existsByEmail(String)} — 사용자 생성 사전 중복 검증</li>
 *   <li>신규 JPQL 메서드: {@link #searchUsers(UserRole, UserStatus, String, Pageable)} —
 *       admin 사용자 list 화면용 검색 + 필터 + 페이징 (Q3=A name + email LIKE)</li>
 * </ul>
 *
 * <h3>기존 메서드 (v15.9 이전 보존)</h3>
 * <ul>
 *   <li>{@link #findByEmail(String)} — AuthService 가 사용 (login + JWT subject 매칭)</li>
 *   <li>{@link #findByRole(UserRole)} — SchemaValidationTest + 기존 유틸</li>
 *   <li>{@link #findByRoleAndStatus(UserRole, UserStatus)} — 본 phase UserService.getApprovers
 *       / getDevelopers + 기존 SchemaValidationTest 가 사용</li>
 * </ul>
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    List<User> findByRole(UserRole role);

    List<User> findByRoleAndStatus(UserRole role, UserStatus status);

    /**
     * v15.9 신규 — 사용자 생성 사전 중복 검증.
     */
    boolean existsByEmail(String email);

    /**
     * v15.9 신규 — admin 사용자 list 화면용 검색 + 필터 + 페이징.
     *
     * <p>{@code role} / {@code status} 가 null 이면 해당 필터 미적용. {@code search}
     * 가 null 이면 미적용. 호출 측 ({@link com.secuhub.domain.user.service.UserService#list})
     * 에서 빈 문자열 / 공백을 null 로 normalize.</p>
     *
     * <p><b>Q3=A 정합</b>: name + email 의 LIKE %X% (case-insensitive). JPQL 안 LOWER
     * + LIKE — H2 / PostgreSQL 양쪽 호환. PostgreSQL ILIKE 는 H2 미지원이라 회피.</p>
     */
    @Query("""
        SELECT u FROM User u
        WHERE (:role IS NULL OR u.role = :role)
          AND (:status IS NULL OR u.status = :status)
          AND (:search IS NULL
               OR LOWER(u.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<User> searchUsers(
            @Param("role") UserRole role,
            @Param("status") UserStatus status,
            @Param("search") String search,
            Pageable pageable);
}