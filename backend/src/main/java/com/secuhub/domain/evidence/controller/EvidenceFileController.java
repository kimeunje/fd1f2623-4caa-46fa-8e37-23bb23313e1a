package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.common.dto.PageResponse;
import com.secuhub.domain.evidence.dto.EvidenceFileDto;
import com.secuhub.domain.evidence.service.EvidenceFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/evidence-files")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class EvidenceFileController {

    private final EvidenceFileService evidenceFileService;

    /**
     * 전체 증빙 파일 목록 (페이징)
     * GET /api/v1/evidence-files?page=0&size=20
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<EvidenceFileDto.Response>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<EvidenceFileDto.Response> result = evidenceFileService.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "collectedAt")));
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(result)));
    }

    /**
     * 증빙 유형별 파일 이력
     * GET /api/v1/evidence-files/by-type/{evidenceTypeId}
     */
    @GetMapping("/by-type/{evidenceTypeId}")
    public ResponseEntity<ApiResponse<List<EvidenceFileDto.Response>>> listByType(
            @PathVariable Long evidenceTypeId) {
        return ResponseEntity.ok(ApiResponse.ok(evidenceFileService.findByEvidenceType(evidenceTypeId)));
    }

    /**
     * 증빙 파일 통계
     * GET /api/v1/evidence-files/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<EvidenceFileDto.StatsResponse>> stats() {
        return ResponseEntity.ok(ApiResponse.ok(evidenceFileService.getStats()));
    }

    /**
     * 증빙 파일 수동 업로드
     * POST /api/v1/evidence-files/upload
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<EvidenceFileDto.Response>> upload(
            @RequestParam("evidenceTypeId") Long evidenceTypeId,
            @RequestParam("file") MultipartFile file) {
        EvidenceFileDto.Response response = evidenceFileService.upload(evidenceTypeId, file);
        return ResponseEntity.ok(ApiResponse.ok("파일이 업로드되었습니다. (v" + response.getVersion() + ")", response));
    }

    /**
     * 증빙 파일 다운로드
     * GET /api/v1/evidence-files/{id}/download
     *
     * DB에서 파일 메타정보를 조회하고, 물리 파일을 Resource로 반환합니다.
     * 한글 파일명을 위해 RFC 5987 인코딩을 적용합니다.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
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
     * 증빙 파일 삭제
     * DELETE /api/v1/evidence-files/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        evidenceFileService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("파일이 삭제되었습니다."));
    }
}
