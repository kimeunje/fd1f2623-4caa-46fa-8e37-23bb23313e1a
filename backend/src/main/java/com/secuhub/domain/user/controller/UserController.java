package com.secuhub.domain.user.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.user.dto.UserDto;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.entity.UserStatus;
import com.secuhub.domain.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Phase 5-15e (v15.9) — admin 사용자 관리 + 사용자 본인 비밀번호 변경.
 *
 * <h3>도입 동기</h3>
 * <p>v15.8 ApiSurfaceTest SET_1_KNOWN_GAPS 첫 항목 closure. FE {@code usersApi} 의 8
 * 메서드가 호출하는 BE 매핑 부재 (v15.7 까지 누적된 갭) — admin UI 의 사용자 관리
 * 기능 운영 시 모두 404 가능. 본 phase 가 BE 신설로 매핑 보강.</p>
 *
 * <h3>endpoint 일람 (FE {@code frontend/src/services/api.ts} 의 {@code usersApi} 정합)</h3>
 * <ul>
 *   <li>{@code GET /api/v1/users} — 사용자 list (paging + filter + search)</li>
 *   <li>{@code POST /api/v1/users} — 사용자 생성</li>
 *   <li>{@code GET /api/v1/users/{id}} — 단건 조회</li>
 *   <li>{@code PATCH /api/v1/users/{id}} — 부분 수정</li>
 *   <li>{@code DELETE /api/v1/users/{id}} — soft delete (status=inactive)</li>
 *   <li>{@code DELETE /api/v1/users/{id}/permanent} — hard delete (영구 삭제, v19.30)</li>
 *   <li>{@code GET /api/v1/users/approvers} — 승인자 목록 (UserBrief[])</li>
 *   <li>{@code GET /api/v1/users/developers?team=X} — 개발자 목록 (UserBrief[])</li>
 *   <li>{@code PATCH /api/v1/users/me/password} — 본인 비밀번호 변경 (admin 권한 무관)</li>
 * </ul>
 *
 * <h3>권한 모델 (Q1=A 결정)</h3>
 * <ul>
 *   <li>위 endpoint = {@code @PreAuthorize("hasRole('ADMIN')")} (클래스 레벨) — admin 전용</li>
 *   <li>{@code /me/password} = 인증된 사용자 누구나. 메서드 단위
 *       {@code @PreAuthorize("isAuthenticated()")} 로 클래스 레벨 admin 제약 override</li>
 * </ul>
 *
 * <h3>Q&amp;A 결정 사항 정합</h3>
 * <ul>
 *   <li>Q1=A — {@code @PreAuthorize} (Spring Security 표준 + 기존 controller 권한 패턴 정합)</li>
 *   <li>Q2=A — changePassword 의 현재 비번 검증 (service 측 처리)</li>
 *   <li>Q3=A — list 의 search 가 name + email LIKE (repository 측 JPQL)</li>
 *   <li>Q5=A — UserDto 신설 (도메인 자체 정합)</li>
 * </ul>
 *
 * <p>spec §5 (API 전체) 정합. v15.9 신규. v19.30 영구 삭제 endpoint 추가.</p>
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    /**
     * 사용자 list — paging + filter + search.
     *
     * <p>FE {@code usersApi.list({ page, size, role, status, search })} 호출 정합.
     * 정렬: {@code createdAt DESC} (최근 가입자 우선).</p>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<UserDto.ListResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) String search) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.ok(userService.list(role, status, search, pageable)));
    }

    @GetMapping("/approvers")
    public ResponseEntity<ApiResponse<List<UserDto.BriefResponse>>> getApprovers() {
        return ResponseEntity.ok(ApiResponse.ok(userService.getApprovers()));
    }

    @GetMapping("/developers")
    public ResponseEntity<ApiResponse<List<UserDto.BriefResponse>>> getDevelopers(
            @RequestParam(required = false) String team) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getDevelopers(team)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserDto.DetailResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.get(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserDto.DetailResponse>> create(
            @Valid @RequestBody UserDto.CreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.create(request)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<UserDto.DetailResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UserDto.UpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * 계정 영구 삭제(hard delete) — v19.30. soft delete({@code DELETE /{id}})와 별개의
     * 비가역 액션. 가드는 service 측: 본인 불가 / 비활성 상태만 / 증빙 담당자면 거부(모두 409).
     * 요청자 email({@code principal.getUsername()})을 넘겨 본인 삭제를 방지한다.
     */
    @DeleteMapping("/{id}/permanent")
    public ResponseEntity<ApiResponse<Void>> hardDelete(
            @PathVariable Long id,
            Authentication authentication) {
        // 이 프로젝트 JWT 필터는 principal 을 UserDetails 로 세팅하지 않으므로
        // @AuthenticationPrincipal UserDetails 는 null 이 된다. Authentication.getName()
        // 은 principal 타입과 무관하게 subject(email) 를 돌려주므로 그것을 요청자 신원으로 사용.
        userService.hardDelete(id, authentication.getName());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * 본인 비밀번호 변경. admin 권한 무관 — 인증된 누구나 본인 비번 변경 가능.
     * 클래스 레벨 {@code hasRole('ADMIN')} 을 메서드 레벨 {@code isAuthenticated()}
     * 로 override.
     *
     * <p>{@code @AuthenticationPrincipal UserDetails.username} 은 본 프로젝트의
     * Spring Security 설정상 {@code email} 로 가정 (JwtTokenProvider 가 email 을
     * subject 로 발급). 다를 경우 service 측 {@code changePassword(email, ...)} 의
     * 첫 인자 변경 필요.</p>
     */
    @PatchMapping("/me/password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody UserDto.ChangePasswordRequest request) {
        userService.changePassword(principal.getUsername(), request);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}