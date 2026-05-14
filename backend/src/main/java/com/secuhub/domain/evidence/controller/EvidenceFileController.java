package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.common.dto.PageResponse;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.domain.evidence.dto.EvidenceFileDto;
import com.secuhub.domain.evidence.service.EvidenceAuthService;
import com.secuhub.domain.evidence.service.EvidenceFileService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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
 * 증빙 파일 API
 *
 * <h3>권한 매트릭스</h3>
 * <ul>
 *   <li>관리자 전용: list, stats, downloadZip, delete,
 *       <b>approve, reject, pending</b> (Phase 5-4)</li>
 *   <li>관리자 + 소유 담당자: listByType, upload, download, <b>link</b> (v18.6a)</li>
 * </ul>
 *
 * <h3>v15 Phase 5-15c (v15.7) — Q3=B URL path variable rename</h3>
 * <p>{@link #downloadZip} 의 {@code @GetMapping("/zip/{controlId}")} →
 * {@code "/zip/{nodeId}"} + {@code @PathVariable Long controlId} →
 * {@code Long nodeId} + 본문 변수 사용 4 곳 일괄 갱신. BC 0 — 같은 phase 안 FE 의
 * downloadZip 호출 (evidenceApi.ts) 도 동기 변경. 외부 통합 0 가정.</p>
 *
 * <h3>v18.6a — Evidence Asset 신규 채널 (§2.4 진입)</h3>
 * <ul>
 *   <li>{@link #upload} — 응답 shape 변경 ({@code Response} → {@code UploadResponse}).
 *       {@code ?forceUpload=true} 쿼리 추가 (default false). status="duplicate_detected"
 *       시 FE 가 confirm dialog 노출</li>
 *   <li>{@link #link} — POST /link 신규. 기존 asset 에 link 만 생성 (multipart 없음)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/evidence-files")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class EvidenceFileController {

    private final EvidenceFileService evidenceFileService;
    private final EvidenceAuthService evidenceAuthService;

    // ========================================================================
    // Phase 5-4: 승인 플로우 (신규)
    // ========================================================================

    /**
     * 승인 대기 목록 (관리자 전용) — 페이징
     * GET /api/v1/evidence-files/pending?page=0&size=20
     */
    @GetMapping("/pending")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<EvidenceFileDto.Response>>> pending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<EvidenceFileDto.Response> result = evidenceFileService.findPending(
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt")));
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(result)));
    }

    /**
     * 증빙 파일 승인 (관리자 전용)
     * POST /api/v1/evidence-files/{id}/approve
     *
     * <p>Body (optional): {@code { "reviewNote": "검토 완료되었습니다." }}</p>
     * <p>상태 전이: pending → approved.
     * 그 외 상태에서는 400 BusinessException.</p>
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<EvidenceFileDto.Response>> approve(
            @PathVariable Long id,
            @RequestBody(required = false) EvidenceFileDto.ApproveRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        String note = request != null ? request.getReviewNote() : null;
        EvidenceFileDto.Response response = evidenceFileService.approve(id, principal, note);
        return ResponseEntity.ok(ApiResponse.ok("승인 처리되었습니다.", response));
    }

    /**
     * 증빙 파일 반려 (관리자 전용) — 반려 사유 필수
     * POST /api/v1/evidence-files/{id}/reject
     *
     * <p>Body (required): {@code { "reviewNote": "형식이 맞지 않습니다." }}</p>
     * <p>reviewNote 빈 값이면 400 Validation Error.
     * 상태 전이: pending → rejected. 그 외 상태에서는 400.</p>
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<EvidenceFileDto.Response>> reject(
            @PathVariable Long id,
            @Valid @RequestBody EvidenceFileDto.RejectRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        EvidenceFileDto.Response response =
                evidenceFileService.reject(id, principal, request.getReviewNote());
        return ResponseEntity.ok(ApiResponse.ok("반려 처리되었습니다.", response));
    }

    // ========================================================================
    // 기존 엔드포인트 (Phase 5-2 기준)
    // ========================================================================

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
     * 파일 업로드 — v18.6a 변경 (sha256 기반 중복 감지 + asset 채널).
     * POST /api/v1/evidence-files/upload
     *
     * <p>admin 업로드 → review_status=auto_approved,
     * 담당자 업로드 → review_status=pending. uploaded_by 항상 기록.</p>
     *
     * <p>응답 status 분기 (Q1=b / Q4=a):</p>
     * <ul>
     *   <li>{@code "created"} — 신규 업로드 (forceUpload=true 또는 sha256 unique)</li>
     *   <li>{@code "duplicate_detected"} — 같은 sha256 발견. FE 가 confirm dialog 노출 후
     *       사용자 선택:
     *     <ul>
     *       <li>[기존 사용] → POST /link (link only, multipart 없음)</li>
     *       <li>[새로 등록] → POST /upload?forceUpload=true (multipart 재전송)</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>응답 toast 메시지 분기 — FE 가 status 따라 다르게 표시 (Chat 4).</p>
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<EvidenceFileDto.UploadResponse>> upload(
            @RequestParam("evidenceTypeId") Long evidenceTypeId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "submitNote", required = false) String submitNote,
            @RequestParam(value = "forceUpload", defaultValue = "false") boolean forceUpload,
            @AuthenticationPrincipal UserPrincipal principal) {
        evidenceAuthService.assertCanAccessEvidenceType(evidenceTypeId, principal);

        EvidenceFileDto.UploadResponse response =
                evidenceFileService.upload(evidenceTypeId, file, principal, submitNote, forceUpload);

        String message;
        if ("duplicate_detected".equals(response.getStatus())) {
            message = "기존 파일과 동일한 파일이 발견되었습니다. 선택해주세요.";
        } else {
            message = "파일이 업로드되었습니다. (v" + response.getEvidenceFile().getVersion() + ")";
        }
        return ResponseEntity.ok(ApiResponse.ok(message, response));
    }

    /**
     * 기존 asset link 생성 — POST /api/v1/evidence-files/link (v18.6a 신규).
     *
     * <p>화면 mockup [기존 파일에서 선택] 흐름 또는 [중복 감지 confirm] 의 [기존 사용]
     * 결과. Multipart 없이 body 로 evidenceTypeId + assetId + (옵션) fileName + submitNote.</p>
     *
     * <p>권한 — {@link EvidenceAuthService#assertCanAccessEvidenceType} 으로 본인
     * EvidenceType 만 link 가능. EvidenceAssetController 의 검색 권한 (Q8 = read-only
     * broad) 과 정합 분리: 검색은 broad, link 생성은 fine-grained.</p>
     */
    @PostMapping("/link")
    public ResponseEntity<ApiResponse<EvidenceFileDto.Response>> link(
            @RequestBody EvidenceFileDto.LinkRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        evidenceAuthService.assertCanAccessEvidenceType(req.getEvidenceTypeId(), principal);

        EvidenceFileDto.Response response = evidenceFileService.linkExistingAsset(
                req.getEvidenceTypeId(),
                req.getAssetId(),
                req.getFileName(),
                req.getSubmitNote(),
                principal);
        return ResponseEntity.ok(ApiResponse.ok(
                "기존 파일에서 연결되었습니다. (v" + response.getVersion() + ")", response));
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
     * 통제항목별 (또는 hybrid 노드) 전체 증빙 파일 ZIP 다운로드 — 관리자 전용.
     * GET /api/v1/evidence-files/zip/{nodeId}
     *
     * <p>v15 Phase 5-15c (v15.7) — Q3=B URL path variable rename. 옛 path
     * {@code /zip/{controlId}} 폐기 (BC 0). FE evidenceApi.ts 의 downloadZip 호출도
     * 동기 갱신 (path variable 명만 변경, 클라이언트 입장 path 의 ID 자체는 같음).</p>
     */
    @GetMapping("/zip/{nodeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public void downloadZip(@PathVariable Long nodeId, HttpServletResponse response) throws IOException {
        String zipFileName = nodeId + "_증빙자료.zip";

        String encodedFileName = URLEncoder.encode(zipFileName, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + zipFileName + "\"; filename*=UTF-8''" + encodedFileName);

        evidenceFileService.downloadZip(nodeId, response.getOutputStream());
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