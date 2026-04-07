package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.dto.CollectionJobDto;
import com.secuhub.domain.evidence.service.CollectionJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class CollectionJobController {

    private final CollectionJobService collectionJobService;

    /**
     * 수집 작업 목록
     * GET /api/v1/jobs
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<CollectionJobDto.Response>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(collectionJobService.findAll()));
    }

    /**
     * 수집 작업 상세 (실행 이력 포함)
     * GET /api/v1/jobs/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CollectionJobDto.DetailResponse>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(collectionJobService.findDetail(id)));
    }

    /**
     * 수집 작업 생성
     * POST /api/v1/jobs
     */
    @PostMapping
    public ResponseEntity<ApiResponse<CollectionJobDto.Response>> create(
            @Valid @RequestBody CollectionJobDto.CreateRequest request) {
        CollectionJobDto.Response response = collectionJobService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("수집 작업이 생성되었습니다.", response));
    }

    /**
     * 수집 작업 수정
     * PUT /api/v1/jobs/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CollectionJobDto.Response>> update(
            @PathVariable Long id,
            @Valid @RequestBody CollectionJobDto.UpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("수집 작업이 수정되었습니다.", collectionJobService.update(id, request)));
    }

    /**
     * 수집 작업 삭제
     * DELETE /api/v1/jobs/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        collectionJobService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("수집 작업이 삭제되었습니다."));
    }

    /**
     * 활성/비활성 토글
     * PATCH /api/v1/jobs/{id}/toggle
     */
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<ApiResponse<Void>> toggleActive(@PathVariable Long id) {
        collectionJobService.toggleActive(id);
        return ResponseEntity.ok(ApiResponse.ok("작업 상태가 변경되었습니다."));
    }

    /**
     * 수동 실행
     * POST /api/v1/jobs/{id}/execute
     */
    @PostMapping("/{id}/execute")
    public ResponseEntity<ApiResponse<CollectionJobDto.ExecutionSummary>> execute(@PathVariable Long id) {
        CollectionJobDto.ExecutionSummary result = collectionJobService.executeManually(id);
        return ResponseEntity.ok(ApiResponse.ok("수집 작업이 실행되었습니다.", result));
    }
}
