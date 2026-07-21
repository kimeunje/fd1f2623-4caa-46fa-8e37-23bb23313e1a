package com.secuhub.domain.evidence.service;

import com.secuhub.domain.evidence.dto.ReviewDto;
import com.secuhub.domain.evidence.entity.EvidenceFile;
import com.secuhub.domain.evidence.entity.ReviewStatus;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import com.secuhub.domain.evidence.repository.ReviewerFrameworkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v19.25 — 심사원(reviewer) 전용 서비스.
 *
 * <p>관리자 트리 서비스(TreeService)와 분리된 읽기 전용 조회 계층. 스크립트·작업·이력·버전·노트는
 * 애초에 로딩하지 않는다(DTO 단계 제외). 모든 조회는 프레임워크 단위 벌크 쿼리 3회로 N+1 을 피한다.</p>
 *
 * <p>404/403 신호는 프로젝트 커스텀 예외에 의존하지 않고 {@link ResponseStatusException} 으로 통일
 * (심사원 계층은 신규라 GlobalExceptionHandler 커플링을 최소화). 미승인 파일 접근은 존재 자체를
 * 숨기기 위해 403 대신 404 로 응답.</p>
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final FrameworkRepository frameworkRepository;
    private final ControlNodeRepository controlNodeRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final EvidenceFileRepository evidenceFileRepository;
    private final ReviewerFrameworkRepository reviewerFrameworkRepository;

    /**
     * 심사원에게 배정된 active 프레임워크 목록. 배정이 없으면 빈 목록(fail-closed).
     */
    @Transactional(readOnly = true)
    public List<ReviewDto.FrameworkSummary> listActiveFrameworks(Long reviewerUserId) {
        List<Long> assignedIds = reviewerFrameworkRepository.findFrameworkIdsByUserId(reviewerUserId);
        if (assignedIds.isEmpty()) {
            return List.of();
        }
        List<Object[]> rows = frameworkRepository.findActiveIdNameByIdsForReview(assignedIds);
        List<ReviewDto.FrameworkSummary> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(ReviewDto.FrameworkSummary.builder()
                    .id((Long) r[0])
                    .name((String) r[1])
                    .build());
        }
        return out;
    }

    /**
     * 프레임워크 트리(평탄화) + leaf 별 증빙 유형 + 각 유형 최신 승인 파일.
     *
     * <p>벌크 3회: (1) 노드 행, (2) 증빙 유형 행, (3) 최신 승인 파일 행. 이후 메모리에서 조립.
     * 심사원에게 배정되지 않은 프레임워크는 404(존재 은닉).</p>
     */
    @Transactional(readOnly = true)
    public ReviewDto.TreeResponse getTree(Long frameworkId, Long reviewerUserId) {
        // 스코프 검증 — 배정 안 된 프레임워크는 존재를 숨기기 위해 404
        if (!reviewerFrameworkRepository.existsByUserIdAndFrameworkId(reviewerUserId, frameworkId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프레임워크를 찾을 수 없습니다.");
        }
        // 이름 조회(+ 잔존 검증)
        List<Object[]> fwRows = frameworkRepository.findIdNameByIdForReview(frameworkId);
        if (fwRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프레임워크를 찾을 수 없습니다.");
        }
        Object[] fwRow = fwRows.get(0);

        // (3) 최신 승인 파일: evidenceTypeId → FileView (id/파일명만 — 이력·버전·크기·수집일 미노출)
        Map<Long, ReviewDto.FileView> latestByEt = new HashMap<>();
        for (Object[] r : evidenceFileRepository.findLatestApprovedFileRowsByFramework(frameworkId)) {
            Long etId = (Long) r[1];
            latestByEt.put(etId, ReviewDto.FileView.builder()
                    .id((Long) r[0])
                    .fileName((String) r[2])
                    .build());
        }

        // (2) 증빙 유형: leaf nodeId → [EvidenceTypeView...] (조회 순서 보존)
        Map<Long, List<ReviewDto.EvidenceTypeView>> etByNode = new LinkedHashMap<>();
        for (Object[] r : evidenceTypeRepository.findReviewEvidenceTypeRows(frameworkId)) {
            Long etId = (Long) r[0];
            String etName = (String) r[1];
            Long nodeId = (Long) r[2];
            etByNode.computeIfAbsent(nodeId, k -> new ArrayList<>())
                    .add(ReviewDto.EvidenceTypeView.builder()
                            .id(etId)
                            .name(etName)
                            .latestFile(latestByEt.get(etId))   // 없으면 null
                            .build());
        }

        // (1) 노드: depth ASC, displayOrder ASC 로 이미 정렬됨
        List<Object[]> nodeRows = controlNodeRepository.findReviewTreeRows(frameworkId);
        List<ReviewDto.Node> nodes = new ArrayList<>(nodeRows.size());
        for (Object[] r : nodeRows) {
            Long nodeId = (Long) r[0];
            nodes.add(ReviewDto.Node.builder()
                    .id(nodeId)
                    .parentId((Long) r[1])          // 루트는 null (LEFT JOIN)
                    .code((String) r[2])
                    .name((String) r[3])
                    .depth((Integer) r[4])
                    .evidenceTypes(etByNode.getOrDefault(nodeId, List.of()))
                    .build());
        }

        return ReviewDto.TreeResponse.builder()
                .framework(ReviewDto.FrameworkSummary.builder()
                        .id((Long) fwRow[0])
                        .name((String) fwRow[1])
                        .build())
                .nodes(nodes)
                .build();
    }

    /**
     * 다운로드 대상 승인 파일 로드. 승인(approved/auto_approved) 파일 + 심사원에게 배정된 프레임워크
     * 소속 파일만 허용 — 그 외는 존재를 숨기기 위해 404.
     */
    @Transactional(readOnly = true)
    public FileDownload loadApprovedFile(Long fileId, Long reviewerUserId) {
        EvidenceFile ef = evidenceFileRepository.findById(fileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다."));

        ReviewStatus status = ef.getReviewStatus();
        if (status != ReviewStatus.approved && status != ReviewStatus.auto_approved) {
            // 미승인(pending/rejected) 파일은 심사원에게 미노출 — 403 대신 404 로 존재 은닉
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다.");
        }

        // 스코프 검증 — 파일이 속한 프레임워크가 이 심사원에게 배정돼 있어야 함
        Long frameworkId = evidenceFileRepository.findFrameworkIdByFileId(fileId).orElse(null);
        if (frameworkId == null
                || !reviewerFrameworkRepository.existsByUserIdAndFrameworkId(reviewerUserId, frameworkId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다.");
        }

        Path path = Paths.get(ef.resolveFilePath());   // v18.6a: asset 우선 / filePath fallback
        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "파일 실체를 찾을 수 없습니다.");
        }
        return new FileDownload(ef.getFileName(), new FileSystemResource(path));
    }

    /** 다운로드 응답 재료(파일명 + 리소스). 컨트롤러가 Content-Disposition(UTF-8) 조립. */
    public record FileDownload(String fileName, Resource resource) {}
}