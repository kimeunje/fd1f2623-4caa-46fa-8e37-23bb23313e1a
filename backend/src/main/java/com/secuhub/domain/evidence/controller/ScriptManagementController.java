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
 * v18.8.2 — 어드민 UI 만으로 Python 스크립트 등록/수정 (UID 기반).
 *
 * <p>EvidenceAsset 패턴 정합 — 사용자가 보는 표면 (작업 name) 과 내부 저장 (script id) 분리.</p>
 *
 * <p>모든 endpoint = admin 한정. CollectionJobController 정합 (class 레벨 @PreAuthorize +
 * ApiResponse.ok).</p>
 *
 * <p>FE 의 ScriptEditorDialog.vue 호출:</p>
 * <ul>
 *   <li>JobsView / EvidenceTypeDetailView 의 "작성" 버튼 → POST (create)</li>
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
     * 신규 스크립트 작성 — content 만 받아 자동 id 부여.
     * POST /api/v1/admin/scripts
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ScriptManagementDto.ScriptResponse>> create(
            @Valid @RequestBody ScriptManagementDto.CreateRequest request) {
        ScriptManagementDto.ScriptResponse saved = scriptManagementService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("스크립트가 등록되었습니다.", saved));
    }

    /**
     * 기존 스크립트 내용 조회.
     * GET /api/v1/admin/scripts/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ScriptManagementDto.ScriptResponse>> getContent(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(scriptManagementService.getContent(id)));
    }

    /**
     * 기존 스크립트 수정 (덮어쓰기) — 같은 작업의 scriptId 유지 → 재실행 시 반영 (Q6).
     * PUT /api/v1/admin/scripts/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ScriptManagementDto.ScriptResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody ScriptManagementDto.UpdateRequest request) {
        ScriptManagementDto.ScriptResponse saved = scriptManagementService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok("스크립트가 수정되었습니다.", saved));
    }
}