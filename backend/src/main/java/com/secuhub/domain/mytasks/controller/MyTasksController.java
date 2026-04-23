package com.secuhub.domain.mytasks.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.domain.mytasks.dto.MyTasksDto;
import com.secuhub.domain.mytasks.service.MyTasksService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 담당자 "내 할 일" API (Phase 5-5).
 *
 * <p>SecurityConfig 에서 {@code /api/v1/my-tasks/**} 는 {@code authenticated()} 로
 * 등록돼 있으며, 실제 권한 체크(permission_evidence + owner_user_id) 는
 * {@link MyTasksService} 에서 수행한다.</p>
 */
@RestController
@RequestMapping("/api/v1/my-tasks")
@RequiredArgsConstructor
public class MyTasksController {

    private final MyTasksService myTasksService;

    /**
     * 5섹션 묶음 응답.
     * GET /api/v1/my-tasks
     */
    @GetMapping
    public ResponseEntity<ApiResponse<MyTasksDto.Response>> list(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(myTasksService.findMyTasks(principal)));
    }

    /**
     * 단일 증빙 유형 상세 — 재제출 페이지용.
     * GET /api/v1/my-tasks/{evidenceTypeId}
     */
    @GetMapping("/{evidenceTypeId}")
    public ResponseEntity<ApiResponse<MyTasksDto.DetailResponse>> detail(
            @PathVariable Long evidenceTypeId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                myTasksService.findMyTaskDetail(evidenceTypeId, principal)));
    }
}