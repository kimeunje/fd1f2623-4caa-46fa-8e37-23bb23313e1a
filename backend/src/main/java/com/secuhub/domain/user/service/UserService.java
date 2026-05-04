package com.secuhub.domain.user.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
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
 * <p>도입 동기는 {@link com.secuhub.domain.user.controller.UserController} javadoc 참조.</p>
 *
 * <h3>주요 동작 요약</h3>
 * <ul>
 *   <li><b>create</b> — email 중복 체크 + 비번 hash + status=active 기본</li>
 *   <li><b>update</b> — 부분 수정. {@link User} entity mutator 활용 — null 필드 자연 보존</li>
 *   <li><b>delete</b> — soft delete (status=inactive). hard delete 미지원</li>
 *   <li><b>list</b> — JPA Specification 없이 단순 조건 분기 + name/email LIKE search (Q3=A)</li>
 *   <li><b>changePassword</b> — 현재 비번 검증 후 새 비번 hash (Q2=A)</li>
 * </ul>
 *
 * <h3>get / list 의 비밀번호 비노출</h3>
 * <p>{@link UserDto.DetailResponse} 가 hashedPassword 를 포함하지 않음 ({@code of()}
 * 팩토리에서 explicit 미매핑). entity 에 비번이 잔존해도 wire 에서 자연 차단.</p>
 *
 * <h3>delete 의 soft 선택</h3>
 * <p>hard delete 시 FK cascade 영향 (evidence_files.uploaded_by / reviewed_by /
 * approval_requests.requester / vulnerabilities.assignee 등 참조 지점 다수). soft
 * delete 로 ID 보존 + status=inactive 표시 → 운영 안전. 본 프로젝트 기존 패턴 정합.</p>
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
                .permissionVuln(Boolean.TRUE.equals(request.getPermissionVuln()))
                .status(UserStatus.active)
                .build();
        User saved = userRepository.save(user);
        log.info("User created: id={} email={} role={}", saved.getId(), saved.getEmail(), saved.getRole());
        return UserDto.DetailResponse.of(saved);
    }

    @Transactional
    public UserDto.DetailResponse update(Long id, UserDto.UpdateRequest request) {
        User user = findOrThrow(id);
        // entity mutator 활용 — null 필드는 mutator 안 가드로 자연 보존
        if (request.getName() != null || request.getTeam() != null) {
            user.updateProfile(request.getName(), request.getTeam());
        }
        if (request.getRole() != null) {
            user.updateRole(request.getRole());
        }
        if (request.getPermissionEvidence() != null || request.getPermissionVuln() != null) {
            user.updatePermissions(request.getPermissionEvidence(), request.getPermissionVuln());
        }
        if (request.getStatus() != null) {
            user.updateStatus(request.getStatus());
        }
        return UserDto.DetailResponse.of(user);
    }

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
     * 본인 비밀번호 변경. {@link UserController#changePassword} 가 호출.
     *
     * <p>Q2=A: 현재 비번 검증 (보안 표준) → 새 비번 hash → 저장.</p>
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

    /** 빈 문자열 / 공백 → null. JPQL 의 IS NULL 분기 정합. */
    private String normalize(String search) {
        return (search == null || search.isBlank()) ? null : search.trim();
    }
}