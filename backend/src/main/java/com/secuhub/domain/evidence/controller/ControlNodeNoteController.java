package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.dto.ControlNodeNoteDto;
import com.secuhub.domain.evidence.service.ControlNodeNoteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * v19.27 — 관리 항목 인수인계 노트 API (관리자 전용).
 *
 * <ul>
 *   <li>GET    /api/v1/control-nodes/{nodeId}/notes           — 목록(작성순)</li>
 *   <li>POST   /api/v1/control-nodes/{nodeId}/notes           — 추가</li>
 *   <li>PATCH  /api/v1/control-nodes/{nodeId}/notes/{noteId}  — 수정</li>
 *   <li>DELETE /api/v1/control-nodes/{nodeId}/notes/{noteId}  — 삭제</li>
 * </ul>
 *
 * <p>트리 PATCH 와 분리된 즉시 반영 CRUD (낙관적 락 없음). 클래스 레벨
 * {@code @PreAuthorize("hasRole('ADMIN')")} 로 심사원(reviewer) 접근을 원천 차단한다 —
 * 인수인계 노트는 내부 관리 맥락이므로 심사원에게 노출하지 않는다.</p>
 */
@RestController
@RequestMapping("/api/v1/control-nodes/{nodeId}/notes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ControlNodeNoteController {

    private final ControlNodeNoteService noteService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ControlNodeNoteDto.Response>>> list(
            @PathVariable Long nodeId) {
        return ResponseEntity.ok(ApiResponse.ok(noteService.list(nodeId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ControlNodeNoteDto.Response>> create(
            @PathVariable Long nodeId,
            @Valid @RequestBody NoteRequest request) {
        ControlNodeNoteDto.Response created =
                noteService.create(nodeId, request.getAuthorName(), request.getBody());
        return ResponseEntity.ok(ApiResponse.ok("인수인계 노트가 추가되었습니다.", created));
    }

    @PatchMapping("/{noteId}")
    public ResponseEntity<ApiResponse<ControlNodeNoteDto.Response>> update(
            @PathVariable Long nodeId,
            @PathVariable Long noteId,
            @Valid @RequestBody NoteRequest request) {
        ControlNodeNoteDto.Response updated =
                noteService.update(nodeId, noteId, request.getAuthorName(), request.getBody());
        return ResponseEntity.ok(ApiResponse.ok("인수인계 노트가 수정되었습니다.", updated));
    }

    @DeleteMapping("/{noteId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long nodeId,
            @PathVariable Long noteId) {
        noteService.delete(nodeId, noteId);
        return ResponseEntity.ok(ApiResponse.ok("인수인계 노트가 삭제되었습니다."));
    }

    /**
     * 추가/수정 공통 요청 바디. 수정 시에도 작성자 이름을 함께 받는다(오탈자 정정 등).
     * 생성 시 두 필드 모두 필수. 수정 시 부분 수정을 허용하려면 서비스가 null 을
     * 미변경으로 처리하지만, 폼 특성상 두 값 모두 전송하는 것을 기본으로 한다.
     */
    @Getter
    @Setter
    public static class NoteRequest {
        @NotBlank(message = "작성자 이름은 필수입니다.")
        @Size(max = 100, message = "작성자 이름은 100자 이내여야 합니다.")
        private String authorName;

        @NotBlank(message = "노트 내용은 필수입니다.")
        private String body;
    }
}