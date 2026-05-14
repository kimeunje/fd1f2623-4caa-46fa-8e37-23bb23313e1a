package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.domain.evidence.dto.EvidenceAssetDto;
import com.secuhub.domain.evidence.service.EvidenceAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * EvidenceAsset 검색 / 단건 조회 controller. v18.6a 신규.
 *
 * <h3>endpoint 목록</h3>
 * <ul>
 *   <li>{@code GET /api/v1/evidence-assets} — 검색 (페이지네이션 + 다중 필터)</li>
 *   <li>{@code GET /api/v1/evidence-assets/{id}} — 단건 조회</li>
 * </ul>
 *
 * <p>POST /upload + POST /link 는 EvidenceFileController 에서 정의 (Chat 2B) — asset
 * 은 link 의 종속물 (EvidenceFile 의 일부) 이라 같은 URL 트리 하에 배치.</p>
 *
 * <h3>권한 (Q8)</h3>
 * <p>{@code @PreAuthorize("isAuthenticated()")} — admin + 담당자 모두 검색 가능. 검색
 * 자체는 read-only 라 broad 허용 (담당자가 본인 항목에 link 추가하려면 다른 framework
 * 의 asset 도 검색 필요). Link 생성 시점의 fine-grained 권한 (본인 EvidenceType 만)
 * 은 EvidenceAuthService 가 별도 처리 (Chat 2B 의 EvidenceFileService.linkExistingAsset).</p>
 *
 * <h3>본 프로젝트 controller 컨벤션 정합</h3>
 * <ul>
 *   <li>{@code @RestController + @RequestMapping("/api/v1/...") + @RequiredArgsConstructor}
 *       (EvidenceFileController / ControlController 정합)</li>
 *   <li>{@code ApiResponse.ok(...)} 응답 래핑 — 본 프로젝트 공통 응답 shape</li>
 *   <li>{@code hasRole('ADMIN')} 대문자 (v16.4b-fix L_AUTH_CONVENTION_GREP 정합)
 *       — 본 controller 는 ADMIN-only 가 아니므로 적용 안 됨, 다른 controller 정합 참고용</li>
 *   <li>v18.4 의 L_TEST_MOCKMVC_BUILD_PATTERN 정합 — Chat 3 의 통합 테스트가
 *       {@code @SpringBootTest + @AutoConfigureMockMvc + @Autowired MockMvc} 표준 패턴
 *       사용 예정</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/evidence-assets")
@RequiredArgsConstructor
public class EvidenceAssetController {

    private final EvidenceAssetService evidenceAssetService;

    /**
     * 검색 — GET /api/v1/evidence-assets
     *
     * <p>FE {@code EvidenceAssetSearchDialog} 호출. FULLTEXT MATCH AGAINST 부분 일치
     * (prod) 또는 LIKE prefix (dev/test) — service 가 환경 분기.</p>
     *
     * <p>query string params:</p>
     * <ul>
     *   <li>{@code q} — 검색어 (선택, null/공백 = 전체)</li>
     *   <li>{@code uploaderId} — 업로더 user.id 필터 (선택)</li>
     *   <li>{@code from} — 등록일 시작 (ISO date-time, 선택)</li>
     *   <li>{@code to} — 등록일 끝 (ISO date-time, 선택)</li>
     *   <li>{@code page} — 페이지 번호 (0-base, default 0)</li>
     *   <li>{@code size} — 페이지 크기 (default 50, 상한 100)</li>
     * </ul>
     *
     * <p>검색어 정규화 (null → "" + trim) 는 service 측. controller 는 raw 전달만.</p>
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Page<EvidenceAssetDto.Response>>> search(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "uploaderId", required = false) Long uploaderId,
            @RequestParam(value = "from", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(value = "to", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        EvidenceAssetDto.SearchRequest req = new EvidenceAssetDto.SearchRequest(
                query, uploaderId, fromDate, toDate);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<EvidenceAssetDto.Response> result = evidenceAssetService.search(req, pageable);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * 단건 조회 — GET /api/v1/evidence-assets/{id}
     *
     * <p>FE 가 link 생성 직전 asset 정보 재확인 또는 admin UI 의 상세 조회.</p>
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<EvidenceAssetDto.Response>> getById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        EvidenceAssetDto.Response response = evidenceAssetService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}