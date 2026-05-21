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
 *   <li><b>v18.8.7</b> — ScriptEditorDialog 의 편집 모드 [삭제] 버튼 → DELETE</li>
 * </ul>
 *
 * <h3>v18.8.7 — 스크립트 삭제 endpoint 추가</h3>
 * <p>DELETE /api/v1/admin/scripts/{id} — Hard delete (DB row + 물리 파일). 사용 중 검사
 * (Q2=안전) 로 active CollectionJob 이 참조 중이면 BusinessException → FE alert 안내.
 * v18.8.2 의 {@code @OnDelete(SET_NULL)} 정책 활용 — 옛 작업들은 script_id=NULL 됨
 * (legacy scriptPath 와 동일 fallback).</p>
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

    /**
     * v18.8.7 — 스크립트 삭제 (Hard delete).
     *
     * <p>DELETE /api/v1/admin/scripts/{id}</p>
     *
     * <p>흐름: id 로 entity 조회 → 사용 중 검사 (active CollectionJob 참조 여부) →
     * 사용 중이면 BusinessException, 아니면 물리 파일 삭제 + entity 삭제.</p>
     *
     * <p>{@code @OnDelete(SET_NULL)} 로 옛 CollectionJob 들의 script_id 자동 NULL 처리 —
     * 옛 작업이 legacy scriptPath 와 동일 fallback 으로 자연 정리.</p>
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        scriptManagementService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("스크립트가 삭제되었습니다.", null));
    }
}