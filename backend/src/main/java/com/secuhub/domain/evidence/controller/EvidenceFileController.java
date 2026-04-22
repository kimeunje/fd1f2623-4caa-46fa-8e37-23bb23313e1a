package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.common.dto.PageResponse;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.domain.evidence.dto.EvidenceFileDto;
import com.secuhub.domain.evidence.service.EvidenceAuthService;
import com.secuhub.domain.evidence.service.EvidenceFileService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 증빙 파일 API (Phase 5-2 권한 체계 적용)
 *
 * <h3>권한 매트릭스</h3>
 * <ul>
 *   <li>관리자 전용: list, stats, downloadZip, delete</li>
 *   <li>관리자 + 소유 담당자: listByType, upload, download</li>
 * </ul>
 *
 * <p>상세 규칙은 {@link EvidenceAuthService} 참조.</p>
 */
@RestController
@RequestMapping("/api/v1/evidence-files")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class EvidenceFileController {

    private final EvidenceFileService evidenceFileService;
    private final EvidenceAuthService evidenceAuthService;

    /**
     * 전체 증빙 파일 목록 (페이징) — 관리자 전용
     * GET /api/v1/evidence-files?page=0&size=20
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<EvidenceFileDto.Response>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<EvidenceFileDto.Response> result = evidenceFileService.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "collectedAt")));
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(result)));
    }

    /**
     * 증빙 유형별 파일 이력 — 관리자 또는 소유 담당자
     * GET /api/v1/evidence-files/by-type/{evidenceTypeId}
     */
    @GetMapping("/by-type/{evidenceTypeId}")
    public ResponseEntity<ApiResponse<List<EvidenceFileDto.Response>>> listByType(
            @PathVariable Long evidenceTypeId,
            @AuthenticationPrincipal UserPrincipal principal) {
        evidenceAuthService.assertCanAccessEvidenceType(evidenceTypeId, principal);
        return ResponseEntity.ok(ApiResponse.ok(evidenceFileService.findByEvidenceType(evidenceTypeId)));
    }

    /**
     * 증빙 파일 통계 — 관리자 전용
     * GET /api/v1/evidence-files/stats
     */
    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<EvidenceFileDto.StatsResponse>> stats() {
        return ResponseEntity.ok(ApiResponse.ok(evidenceFileService.getStats()));
    }

    /**
     * 증빙 파일 수동 업로드 — 관리자 또는 소유 담당자 (Phase 5-2 확장)
     * POST /api/v1/evidence-files/upload
     *
     * <p>admin 업로드는 {@code review_status=auto_approved},
     * 담당자 업로드는 {@code review_status=pending} 로 기록된다.
     * {@code uploaded_by} 는 항상 현재 인증된 사용자로 설정.</p>
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<EvidenceFileDto.Response>> upload(
            @RequestParam("evidenceTypeId") Long evidenceTypeId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "submitNote", required = false) String submitNote,
            @AuthenticationPrincipal UserPrincipal principal) {
        evidenceAuthService.assertCanAccessEvidenceType(evidenceTypeId, principal);

        EvidenceFileDto.Response response =
                evidenceFileService.upload(evidenceTypeId, file, principal, submitNote);
        return ResponseEntity.ok(ApiResponse.ok(
                "파일이 업로드되었습니다. (v" + response.getVersion() + ")", response));
    }

    /**
     * 증빙 파일 다운로드 — 관리자 또는 소유 담당자
     * GET /api/v1/evidence-files/{id}/download
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        evidenceAuthService.assertCanAccessFile(id, principal);

        EvidenceFileDto.DownloadResponse downloadInfo = evidenceFileService.download(id);

        // 한글 파일명 지원: RFC 5987 (filename*=UTF-8'')
        String encodedFileName = URLEncoder.encode(downloadInfo.getFileName(), StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        String contentDisposition = "attachment; filename=\"" + downloadInfo.getFileName() + "\"; "
                + "filename*=UTF-8''" + encodedFileName;

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(downloadInfo.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(downloadInfo.getFileSize()))
                .body(downloadInfo.getResource());
    }

    /**
     * 통제항목별 전체 증빙 파일 ZIP 다운로드 — 관리자 전용 (감사 대응 목적)
     * GET /api/v1/evidence-files/zip/{controlId}
     */
    @GetMapping("/zip/{controlId}")
    @PreAuthorize("hasRole('ADMIN')")
    public void downloadZip(@PathVariable Long controlId, HttpServletResponse response) throws IOException {
        String zipFileName = controlId + "_증빙자료.zip";

        String encodedFileName = URLEncoder.encode(zipFileName, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + zipFileName + "\"; filename*=UTF-8''" + encodedFileName);

        evidenceFileService.downloadZip(controlId, response.getOutputStream());
    }

    /**
     * 증빙 파일 삭제 — 관리자 전용 (담당자는 이력 보존 목적상 삭제 불가)
     * DELETE /api/v1/evidence-files/{id}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        evidenceFileService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("파일이 삭제되었습니다."));
    }
}