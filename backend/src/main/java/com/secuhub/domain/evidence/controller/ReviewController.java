package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.dto.ReviewDto;
import com.secuhub.domain.evidence.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * v19.25 — 심사원(reviewer) 전용 API.
 *
 * <p><b>별도 컨트롤러가 필수인 이유</b>: 관리자 트리 API({@code TreeController})는 클래스 레벨
 * {@code @PreAuthorize("hasRole('ADMIN')")} + SecurityConfig {@code /api/v1/frameworks/**} ADMIN
 * URL 규칙으로 이중 차단되어 심사원이 재사용할 수 없다. 심사원은 이 {@code /api/v1/review/**}
 * prefix 로만 접근하며, 응답 DTO 는 스크립트·작업·이력·버전·노트를 담지 않는다({@link ReviewDto}).</p>
 *
 * <p>권한: 클래스 레벨 {@code hasRole('REVIEWER')} — 대문자 (JwtAuthenticationFilter 가
 * {@code ROLE_ + role.toUpperCase()} 변환, L_AUTH_CONVENTION_GREP). SecurityConfig 의
 * {@code /api/v1/review/**} URL 규칙과 함께 defense in depth.</p>
 *
 * <ul>
 *   <li><b>GET /api/v1/review/frameworks</b> — active 프레임워크 목록(선택 랜딩)</li>
 *   <li><b>GET /api/v1/review/frameworks/{id}/tree</b> — 트리 + 항목별 최신 승인 파일</li>
 *   <li><b>GET /api/v1/review/files/{fileId}/download</b> — 승인 파일 다운로드(approved-only 가드)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("hasRole('REVIEWER')")
public class ReviewController {

    private final ReviewService reviewService;

    /** 심사원 랜딩 — 열람 가능한 프레임워크 목록. */
    @GetMapping("/review/frameworks")
    public ResponseEntity<ApiResponse<List<ReviewDto.FrameworkSummary>>> frameworks() {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.listActiveFrameworks()));
    }

    /** 읽기 전용 트리 + leaf 별 최신 승인 파일. */
    @GetMapping("/review/frameworks/{frameworkId}/tree")
    public ResponseEntity<ApiResponse<ReviewDto.TreeResponse>> tree(@PathVariable Long frameworkId) {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.getTree(frameworkId)));
    }

    /**
     * 승인 파일 다운로드. 관리자 blob 엔드포인트({@code /api/v1/evidence-files/**} — EvidenceAuthService 가
     * admin/소유자만 통과)를 재사용하면 심사원은 403 이므로, approved-only 가드를 건 전용 경로로 격리.
     * 한글 파일명은 v18.9.14 패턴대로 {@code ContentDisposition ... filename(name, UTF_8)} 빌더 사용.
     */
    @GetMapping("/review/files/{fileId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long fileId) {
        ReviewService.FileDownload dl = reviewService.loadApprovedFile(fileId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(dl.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(dl.resource());
    }
}