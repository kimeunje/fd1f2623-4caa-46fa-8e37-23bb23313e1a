package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.dto.ControlDto;
import com.secuhub.domain.evidence.service.ControlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ControlController {

    private final ControlService controlService;

    /**
     * 프레임워크별 통제항목 목록
     * GET /api/v1/frameworks/{frameworkId}/controls
     */
    @GetMapping("/frameworks/{frameworkId}/controls")
    public ResponseEntity<ApiResponse<List<ControlDto.Response>>> list(
            @PathVariable Long frameworkId) {
        return ResponseEntity.ok(ApiResponse.ok(controlService.findByFramework(frameworkId)));
    }

    /**
     * 통제항목 상세 (증빙 유형별 파일이력 포함)
     * GET /api/v1/controls/{id}
     */
    @GetMapping("/controls/{id}")
    public ResponseEntity<ApiResponse<ControlDto.DetailResponse>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(controlService.findDetail(id)));
    }

    /**
     * 통제항목 생성
     * POST /api/v1/frameworks/{frameworkId}/controls
     */
    @PostMapping("/frameworks/{frameworkId}/controls")
    public ResponseEntity<ApiResponse<ControlDto.Response>> create(
            @PathVariable Long frameworkId,
            @Valid @RequestBody ControlDto.CreateRequest request) {
        ControlDto.Response response = controlService.create(frameworkId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("통제항목이 생성되었습니다.", response));
    }

    /**
     * 통제항목 수정
     * PUT /api/v1/controls/{id}
     */
    @PutMapping("/controls/{id}")
    public ResponseEntity<ApiResponse<ControlDto.Response>> update(
            @PathVariable Long id,
            @Valid @RequestBody ControlDto.UpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("통제항목이 수정되었습니다.", controlService.update(id, request)));
    }

    /**
     * 통제항목 삭제
     * DELETE /api/v1/controls/{id}
     */
    @DeleteMapping("/controls/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        controlService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("통제항목이 삭제되었습니다."));
    }

    /**
     * 증빙 유형 추가
     * POST /api/v1/controls/{controlId}/evidence-types
     */
    @PostMapping("/controls/{controlId}/evidence-types")
    public ResponseEntity<ApiResponse<ControlDto.EvidenceTypeResponse>> addEvidenceType(
            @PathVariable Long controlId,
            @Valid @RequestBody ControlDto.EvidenceTypeRequest request) {
        ControlDto.EvidenceTypeResponse response = controlService.addEvidenceType(controlId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("증빙 유형이 추가되었습니다.", response));
    }

    /**
     * 증빙 유형 삭제
     * DELETE /api/v1/evidence-types/{id}
     */
    @DeleteMapping("/evidence-types/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteEvidenceType(@PathVariable Long id) {
        controlService.deleteEvidenceType(id);
        return ResponseEntity.ok(ApiResponse.ok("증빙 유형이 삭제되었습니다."));
    }
}
