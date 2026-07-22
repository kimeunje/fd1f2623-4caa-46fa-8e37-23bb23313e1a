package com.secuhub.domain.user.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.config.audit.Auditable;
import com.secuhub.domain.audit.AuditAction;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
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
 * <p>AUDIT-1/B: create/update/delete 에 {@code @Auditable}. create/update 는 반환 DTO 에서
 * targetName(사용자명)을 캡처. delete 는 void(반환 없음)라 표시명 생략 — soft-delete 라 users
 * 행이 보존되어 이름은 계정 화면에서 여전히 확인 가능. (changePassword 감사는 선택.)</p>
 *
 * <p><b>v19.30 — 계정 영구 삭제(hard delete) 추가.</b> 비활성화(soft delete)는 그대로 유지하고,
 * 그 위에 관리자가 명시적으로 요청할 때만 동작하는 {@link #hardDelete(Long, String)} 를 추가.
 * 프레임워크 2단계 삭제(v19.22)·스크립트 in-use 가드(v18.8.7)·IP ACL 자기잠금(v19.1) 관례 정합:
 * ① 본인 계정 불가, ② <b>이미 비활성</b>인 계정만 허용(2단계 제스처), ③ 증빙 유형 담당자면 거부.
 * hard delete 는 users 행이 사라지므로 감사 target_name 스냅샷이 필요 — 삭제 직전 이름을 반환해
 * {@code @Auditable}({@code #result})로 박제한다.</p>
 *
 * <p>FK 정리: {@code notification_preferences}({@code @OnDelete CASCADE}) /
 * {@code ip_access_rules} / {@code reviewer_frameworks} 는 FK ON DELETE CASCADE 로 자동 정리되므로
 * 서비스에서 손대지 않는다(마이그레이션 0). {@code evidence_types.owner_user_id} 만 위 ③ 가드로
 * 사전 차단. {@code evidence_files} 의 {@code uploaded_by}/{@code reviewed_by} 는 FK 아닌 이력
 * id 스냅샷이라 파일은 보존되고 작성자 id 만 남는다(감사 로그로 추적).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    // v19.30 hard delete 담당자 가드용.
    private final EvidenceTypeRepository evidenceTypeRepository;

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
            targetId = "#result.id", targetName = "#result.name", detail = "#a0.email")
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
                .permissionEvidence(effectivePermissionEvidence(request.getRole(), request.getPermissionEvidence()))
                .status(UserStatus.active)
                .build();
        User saved = userRepository.save(user);
        log.info("User created: id={} email={} role={}", saved.getId(), saved.getEmail(), saved.getRole());
        return UserDto.DetailResponse.of(saved);
    }

    @Auditable(action = AuditAction.USER_UPDATE, targetType = "User",
            targetId = "#a0", targetName = "#result.name")
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
            // 마지막 관리자 보호 — 관리자를 비활성화하려는데 다른 활성 관리자가 없으면 거부
            // (유일 관리자 비활성화로 인한 admin 접근 잠김 방지. hard delete 는 비활성 대상만
            //  허용하므로, 이 가드가 사실상 시드 관리자를 삭제 가능 상태로 만드는 것도 막는다.)
            if (request.getStatus() == UserStatus.inactive
                    && user.getRole() == UserRole.admin
                    && !hasOtherActiveAdmin(id)) {
                throw new BusinessException(
                        "마지막 활성 관리자는 비활성화할 수 없습니다. "
                                + "다른 관리자를 먼저 활성화하세요.", HttpStatus.CONFLICT);
            }
            user.updateStatus(request.getStatus());
        }
        // v19.25 방어선 — 역할이 심사원이면 증빙 권한을 강제로 끈다(FE 체크박스 제거만으론
        // 조작된 payload 를 막지 못하므로 BE 에서 최종 강제). 역할 변경/권한 변경 반영 뒤 적용.
        if (user.getRole() == UserRole.reviewer && Boolean.TRUE.equals(user.getPermissionEvidence())) {
            user.updatePermissions(false);
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

    /**
     * 계정 영구 삭제(hard delete) — v19.30. 비활성화(soft delete)와 별개의 명시적·비가역 액션.
     *
     * <p>가드(모두 409 CONFLICT):
     * ① 본인 계정 삭제 불가(자기잠금 방지, IP ACL 관례 정합),
     * ② 이미 비활성 상태여야 함(활성 계정은 먼저 비활성화 — 2단계 제스처),
     * ③ 증빙 유형 담당자(owner)면 거부(먼저 담당 재배정 — 소유권 무결성 보호, 스크립트 in-use 가드 정합).</p>
     *
     * <p>삭제 후 정리는 FK CASCADE 에 위임: {@code notification_preferences}(@OnDelete CASCADE) /
     * {@code ip_access_rules} / {@code reviewer_frameworks} 자동 삭제. {@code evidence_files} 의
     * {@code uploaded_by}/{@code reviewed_by} 는 FK 아닌 이력 id 라 파일 보존 + id 만 dangling.</p>
     *
     * @param id             삭제 대상 계정 id
     * @param requesterEmail 요청자(로그인 관리자) email — 본인 삭제 가드용
     * @return 삭제된 계정명(감사 target_name 스냅샷용 — 행이 사라지므로 사전 캡처)
     */
    @Auditable(action = AuditAction.USER_HARD_DELETE, targetType = "User",
            targetId = "#a0", targetName = "#result")
    @Transactional
    public String hardDelete(Long id, String requesterEmail) {
        User user = findOrThrow(id);

        if (user.getEmail().equals(requesterEmail)) {
            throw new BusinessException("본인 계정은 영구 삭제할 수 없습니다.", HttpStatus.CONFLICT);
        }
        if (user.getStatus() != UserStatus.inactive) {
            throw new BusinessException(
                    "먼저 계정을 비활성화한 뒤 영구 삭제할 수 있습니다.", HttpStatus.CONFLICT);
        }
        if (evidenceTypeRepository.existsByOwnerUserId(id)) {
            throw new BusinessException(
                    "이 계정이 담당자로 지정된 증빙 유형이 있습니다. "
                            + "먼저 담당자를 재배정한 뒤 다시 시도해주세요.", HttpStatus.CONFLICT);
        }
        // 마지막 관리자 보호 — 관리자 계정 삭제 시 다른 활성 관리자가 최소 1명 남아야 함
        // (기본/시드 관리자가 사실상 유일한 관리자인 동안 자동 보호. 특정 이메일 하드코딩 대신 불변식).
        if (user.getRole() == UserRole.admin && !hasOtherActiveAdmin(id)) {
            throw new BusinessException(
                    "최소 1명의 활성 관리자가 필요합니다. "
                            + "다른 관리자를 활성화한 뒤 삭제하세요.", HttpStatus.CONFLICT);
        }

        String name = user.getName();  // 행 삭제 전 스냅샷(감사 target_name)
        userRepository.delete(user);   // notification_preferences / ip_access_rules / reviewer_frameworks 는 FK CASCADE
        log.info("User hard-deleted: id={} email={} by={}", id, user.getEmail(), requesterEmail);
        return name;
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
    @Auditable(action = AuditAction.USER_UPDATE, targetType = "User",
               targetName = "#a0", detail = "'비밀번호 변경'")   // a0=email
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

    /**
     * 대상(excludeId) 을 제외하고 활성 관리자가 1명 이상 남는지.
     * 마지막 활성 관리자 보호(비활성화/영구 삭제)용. 기존 {@code findByRoleAndStatus} 재사용.
     */
    private boolean hasOtherActiveAdmin(Long excludeId) {
        return userRepository.findByRoleAndStatus(UserRole.admin, UserStatus.active).stream()
                .anyMatch(u -> !u.getId().equals(excludeId));
    }

    /**
     * v19.25 — 심사원(reviewer)은 항상 읽기 전용이므로 증빙 권한을 강제로 false.
     * 그 외 역할은 요청 값(null 이면 false)을 따른다.
     */
    private boolean effectivePermissionEvidence(UserRole role, Boolean requested) {
        if (role == UserRole.reviewer) return false;
        return Boolean.TRUE.equals(requested);
    }

    private String normalize(String search) {
        return (search == null || search.isBlank()) ? null : search.trim();
    }
}