package com.secuhub.domain.user.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.config.audit.Auditable;
import com.secuhub.domain.audit.AuditAction;
import com.secuhub.domain.user.dto.UserDto;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.entity.UserStatus;
import com.secuhub.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 5-15e (v15.9) — admin 사용자 관리 + 사용자 본인 비밀번호 변경 service.
 *
 * <p>AUDIT-1: create/update/delete 에 {@code @Auditable} (targetId 포함).
 * (changePassword 감사는 선택 — 가이드 참조.)</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public UserDto.ListResponse list(UserRole role, UserStatus status, String search, Pageable pageable) {
        Page<User> page = userRepository.searchUsers(role, status, normalize(search), pageable);
        List<UserDto.DetailResponse> items = page.getContent().stream()
                .map(UserDto.DetailResponse::of)
                .toList();
        return UserDto.ListResponse.builder()
                .items(items)
                .total(page.getTotalElements())
                .build();
    }

    @Transactional(readOnly = true)
    public UserDto.DetailResponse get(Long id) {
        return UserDto.DetailResponse.of(findOrThrow(id));
    }

    @Auditable(action = AuditAction.USER_CREATE, targetType = "User",
            targetId = "#result.id", detail = "#a0.email")
    @Transactional
    public UserDto.DetailResponse create(UserDto.CreateRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(
                    "이미 사용 중인 이메일입니다: " + request.getEmail(), HttpStatus.CONFLICT);
        }
        User user = User.builder()
                .email(request.getEmail())
                .name(request.getName())
                .hashedPassword(passwordEncoder.encode(request.getPassword()))
                .team(request.getTeam())
                .role(request.getRole())
                .permissionEvidence(Boolean.TRUE.equals(request.getPermissionEvidence()))
                .status(UserStatus.active)
                .build();
        User saved = userRepository.save(user);
        log.info("User created: id={} email={} role={}", saved.getId(), saved.getEmail(), saved.getRole());
        return UserDto.DetailResponse.of(saved);
    }

    @Auditable(action = AuditAction.USER_UPDATE, targetType = "User", targetId = "#a0")
    @Transactional
    public UserDto.DetailResponse update(Long id, UserDto.UpdateRequest request) {
        User user = findOrThrow(id);
        if (request.getName() != null || request.getTeam() != null) {
            user.updateProfile(request.getName(), request.getTeam());
        }
        if (request.getRole() != null) {
            user.updateRole(request.getRole());
        }
        if (request.getPermissionEvidence() != null) {
            user.updatePermissions(request.getPermissionEvidence());
        }
        if (request.getStatus() != null) {
            user.updateStatus(request.getStatus());
        }
        return UserDto.DetailResponse.of(user);
    }

    @Auditable(action = AuditAction.USER_DELETE, targetType = "User", targetId = "#a0")
    @Transactional
    public void delete(Long id) {
        User user = findOrThrow(id);
        // soft delete — status=inactive (FK 보존, 감사 추적 가능)
        user.updateStatus(UserStatus.inactive);
        log.info("User soft-deleted: id={} email={}", user.getId(), user.getEmail());
    }

    @Transactional(readOnly = true)
    public List<UserDto.BriefResponse> getApprovers() {
        return userRepository.findByRoleAndStatus(UserRole.approver, UserStatus.active).stream()
                .map(UserDto.BriefResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserDto.BriefResponse> getDevelopers(String team) {
        List<User> developers = userRepository.findByRoleAndStatus(UserRole.developer, UserStatus.active);
        if (team == null || team.isBlank()) {
            return developers.stream().map(UserDto.BriefResponse::of).toList();
        }
        return developers.stream()
                .filter(u -> team.equals(u.getTeam()))
                .map(UserDto.BriefResponse::of)
                .toList();
    }

    /**
     * 본인 비밀번호 변경. Q2=A: 현재 비번 검증 → 새 비번 hash → 저장.
     */
    @Transactional
    public void changePassword(String email, UserDto.ChangePasswordRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다. email=" + email));
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getHashedPassword())) {
            throw new BusinessException("현재 비밀번호가 일치하지 않습니다.", HttpStatus.BAD_REQUEST);
        }
        user.changePassword(passwordEncoder.encode(request.getNewPassword()));
        log.info("Password changed: id={} email={}", user.getId(), user.getEmail());
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private User findOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다. id=" + id));
    }

    private String normalize(String search) {
        return (search == null || search.isBlank()) ? null : search.trim();
    }
}