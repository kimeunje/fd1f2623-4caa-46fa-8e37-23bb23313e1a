package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.dto.ScriptManagementDto;
import com.secuhub.domain.evidence.service.ScriptManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * v18.8 — 어드민 UI 만으로 Python 스크립트 등록/수정.
 *
 * <p>모든 endpoint = admin 한정 (Python = 임의 코드 실행 → 보안 차단 필수).
 * CollectionJobController 패턴 정합 — class 레벨 @PreAuthorize + ApiResponse.ok.</p>
 *
 * <p>FE 의 ScriptEditorDialog.vue 가 본 endpoint 호출:</p>
 * <ul>
 *   <li>JobsView 작업 등록 dialog 의 "작성" 버튼 → POST (upload)</li>
 *   <li>FailureDiagnosisPanel 의 "수정 스크립트 업로드" 버튼 → GET + PUT (조회 후 수정)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/scripts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ScriptManagementController {

    private final ScriptManagementService scriptManagementService;

    /**
     * 스크립트 목록 조회
     * GET /api/v1/admin/scripts
     */
    @GetMapping
    public ResponseEntity<ApiResponse<ScriptManagementDto.ListResponse>> list() {
        return ResponseEntity.ok(ApiResponse.ok(scriptManagementService.list()));
    }

    /**
     * 신규 스크립트 업로드 — 충돌 시 400 거부 (Q4)
     * POST /api/v1/admin/scripts
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ScriptManagementDto.ScriptContent>> upload(
            @Valid @RequestBody ScriptManagementDto.UploadRequest request) {
        ScriptManagementDto.ScriptContent saved = scriptManagementService.upload(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("스크립트가 등록되었습니다.", saved));
    }

    /**
     * 기존 스크립트 내용 조회 — 편집 모드 진입 시
     * GET /api/v1/admin/scripts/{filename}
     */
    @GetMapping("/{filename}")
    public ResponseEntity<ApiResponse<ScriptManagementDto.ScriptContent>> getContent(
            @PathVariable String filename) {
        return ResponseEntity.ok(ApiResponse.ok(scriptManagementService.getContent(filename)));
    }

    /**
     * 기존 스크립트 수정 (덮어쓰기) — scriptPath 유지로 재실행 시 수정 반영 (Q6)
     * PUT /api/v1/admin/scripts/{filename}
     */
    @PutMapping("/{filename}")
    public ResponseEntity<ApiResponse<ScriptManagementDto.ScriptContent>> update(
            @PathVariable String filename,
            @Valid @RequestBody ScriptManagementDto.UpdateRequest request) {
        ScriptManagementDto.ScriptContent saved = scriptManagementService.update(filename, request);
        return ResponseEntity.ok(ApiResponse.ok("스크립트가 수정되었습니다.", saved));
    }
}