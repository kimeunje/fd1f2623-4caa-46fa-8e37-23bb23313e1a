package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.dto.ControlDto;
import com.secuhub.domain.evidence.service.ControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * v15 Phase 5-15g (v15.11) + v18 — EvidenceType 단위 운영 API.
 *
 * <h3>v18 추가</h3>
 * <p>PUT /{id} — 증빙 유형 수정 (이름/설명/담당자/마감일). EvidenceTypeDetailView 의
 * 인라인 편집 UI 에서 호출. 기존 DELETE 와 동일 admin 전용 권한.</p>
 */
@RestController
@RequestMapping("/api/v1/evidence-types")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class EvidenceTypeController {

    private final ControlService controlService;

    // ========================================================================
    // POST — 증빙 유형 생성 (v18)
    // ========================================================================

    /**
     * 증빙 유형 생성 — 특정 통제 노드에 증빙 유형 추가.
     *
     * @param request nodeId + name (필수)
     * @return 200 OK + 생성 완료 메시지
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> create(@Valid @RequestBody CreateRequest request) {
        controlService.createEvidenceType(request.getNodeId(), request.getName(), request.getDescription());
        return ResponseEntity.ok(ApiResponse.ok("증빙 유형이 추가되었습니다."));
    }

    @Getter
    @Setter
    public static class CreateRequest {
        @jakarta.validation.constraints.NotNull(message = "통제 노드 ID는 필수입니다.")
        private Long nodeId;
        @jakarta.validation.constraints.NotBlank(message = "증빙 유형 이름은 필수입니다.")
        @Size(max = 300, message = "이름은 300자 이내여야 합니다.")
        private String name;
        private String description;
    }

    // ========================================================================
    // PUT — 증빙 유형 수정 (v18)
    // ========================================================================

    /**
     * 증빙 유형 수정 (이름, 설명, 담당자, 마감일).
     *
     * <p>부분 수정 지원: null 필드는 기존 값 유지.</p>
     *
     * @param id      evidence_type.id (PK)
     * @param request 수정할 필드들
     * @return 200 OK + 수정 완료 메시지
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRequest request) {
        controlService.updateEvidenceType(id, request.getName(), request.getDescription(),
                request.getOwnerUserId(), request.getDueDate());
        return ResponseEntity.ok(ApiResponse.ok("증빙 유형이 수정되었습니다."));
    }

    @Getter
    @Setter
    public static class UpdateRequest {
        @Size(max = 300, message = "이름은 300자 이내여야 합니다.")
        private String name;
        private String description;
        /** null = 변경 안 함, 0 = 담당자 해제 */
        private Long ownerUserId;
        /** ISO-8601 날짜 문자열 (yyyy-MM-dd). null = 변경 안 함, "" = 마감일 해제 */
        private String dueDate;
    }

    // ========================================================================
    // DELETE — 증빙 유형 삭제 (v15.11)
    // ========================================================================

    /**
     * 증빙 유형 단건 삭제.
     *
     * @param id evidence_type.id (PK)
     * @return 200 OK + 빈 데이터 응답
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        controlService.deleteEvidenceType(id);
        return ResponseEntity.ok(ApiResponse.ok("증빙 유형이 삭제되었습니다."));
    }
}