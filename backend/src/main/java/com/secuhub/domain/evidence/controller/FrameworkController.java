package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.dto.ExcelImportDto;
import com.secuhub.domain.evidence.dto.FrameworkDto;
import com.secuhub.domain.evidence.service.ExcelImportService;
import com.secuhub.domain.evidence.service.FrameworkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/frameworks")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class FrameworkController {

    private final FrameworkService frameworkService;
    private final ExcelImportService excelImportService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<FrameworkDto.Response>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(frameworkService.findAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FrameworkDto.Response>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(frameworkService.findById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FrameworkDto.Response>> create(
            @Valid @RequestBody FrameworkDto.CreateRequest request) {
        FrameworkDto.Response response = frameworkService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("프레임워크가 생성되었습니다.", response));
    }

    /**
     * v11 Phase 5-6 — 기존 Framework 의 구조를 상속하여 새 Framework 를 생성한다.
     *
     * <p>POST /api/v1/frameworks/inherit</p>
     *
     * <p>Body 필수 필드:</p>
     * <ul>
     *   <li>sourceFrameworkId — 상속받을 원본 Framework ID</li>
     *   <li>name — 새 Framework 이름</li>
     * </ul>
     *
     * <p>복제 대상: 통제 항목 / 증빙 유형(담당자·마감일 포함) / 수집 작업.
     * 파일 · 실행 이력은 복제하지 않는다.</p>
     */
    @PostMapping("/inherit")
    public ResponseEntity<ApiResponse<FrameworkDto.Response>> inherit(
            @Valid @RequestBody FrameworkDto.InheritRequest request) {
        FrameworkDto.Response response = frameworkService.inherit(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("프레임워크 상속이 완료되었습니다.", response));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<FrameworkDto.Response>> update(
            @PathVariable Long id,
            @Valid @RequestBody FrameworkDto.UpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("프레임워크가 수정되었습니다.", frameworkService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        frameworkService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("프레임워크가 삭제되었습니다."));
    }

    /**
     * 통제항목 엑셀 Import
     * POST /api/v1/frameworks/{id}/import
     */
    @PostMapping(value = "/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ExcelImportDto.ImportResult>> importControls(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        ExcelImportDto.ImportResult result = excelImportService.importControls(id, file);
        return ResponseEntity.ok(ApiResponse.ok("엑셀 Import가 완료되었습니다.", result));
    }
}