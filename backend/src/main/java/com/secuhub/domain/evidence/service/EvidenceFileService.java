package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.domain.evidence.dto.EvidenceFileDto;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
// v15.3 5-15b — Control 엔티티 + ControlRepository 제거. ControlNodeRepository 위임.
//                wildcard import 그대로 사용 (Control 삭제 후 ControlNode + NodeType + ControlNodeRepository 자연 매칭).
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 증빙 파일 서비스.
 *
 * <h3>v15 Phase 5-15c (v15.7) 변경</h3>
 * <ul>
 *   <li>{@code evidenceType.getControlNode().getCode()} (line ~166, upload 시 storage path
 *       빌드) + {@code f.getEvidenceType().getControlNode().getId()} (line ~329, getStats
 *       의 coverage 계산) → {@code getControlNode().*} (Q1=B FORCED)</li>
 *   <li>{@link #downloadZip(Long, OutputStream)} 시그니처 {@code Long controlId} →
 *       {@code Long nodeId} (Q3=B URL path variable rename + Q6=A service param 정합).
 *       메서드 본문의 변수 사용 5 곳도 일괄 갱신.</li>
 *   <li>{@code evidenceTypeRepository.findByControlId} → {@code findByControlNodeId}
 *       (Q5=A)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceFileService {

    private final EvidenceFileRepository evidenceFileRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    // v15.3 5-15b — ControlRepository → ControlNodeRepository 위임 (leaf-only 의미).
    private final ControlNodeRepository controlNodeRepository;
    private final UserRepository userRepository;

    @Value("${app.storage.path:./storage}")
    private String storagePath;

    // ========================================
    // Phase 5-4: 승인 플로우
    // ========================================

    /**
     * 증빙 파일 승인 (admin 전용 — Controller 에서 hasRole 검증)
     *
     * <p>상태 전이: {@code pending → approved}.
     * 그 외 상태에서는 {@link BusinessException} (400) 던짐.</p>
     *
     * @param fileId   대상 파일 ID
     * @param reviewer 관리자 Principal
     * @param note     승인 코멘트 (optional, null 허용)
     * @return 갱신된 파일 DTO
     */
    @Transactional
    public EvidenceFileDto.Response approve(Long fileId, UserPrincipal reviewer, String note) {
        EvidenceFile file = evidenceFileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 파일", fileId));

        assertPendingStatus(file, "승인");

        User reviewerEntity = userRepository.findById(reviewer.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("사용자", reviewer.getUserId()));

        file.approve(reviewerEntity, note);
        log.info("증빙 파일 승인: fileId={}, reviewer={}", fileId, reviewer.getEmail());

        return EvidenceFileDto.Response.from(file);
    }

    /**
     * 증빙 파일 반려 (admin 전용 — Controller 에서 hasRole 검증)
     *
     * <p>상태 전이: {@code pending → rejected}.
     * reviewNote 빈 값은 DTO {@code @NotBlank} 로 검증되어 400 처리되므로
     * 여기선 다시 검증하지 않는다.</p>
     *
     * @param fileId   대상 파일 ID
     * @param reviewer 관리자 Principal
     * @param note     반려 사유 (DTO 에서 검증됨)
     * @return 갱신된 파일 DTO
     */
    @Transactional
    public EvidenceFileDto.Response reject(Long fileId, UserPrincipal reviewer, String note) {
        EvidenceFile file = evidenceFileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 파일", fileId));

        assertPendingStatus(file, "반려");

        User reviewerEntity = userRepository.findById(reviewer.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("사용자", reviewer.getUserId()));

        file.reject(reviewerEntity, note);
        log.info("증빙 파일 반려: fileId={}, reviewer={}, reason={}",
                fileId, reviewer.getEmail(), truncate(note, 60));

        return EvidenceFileDto.Response.from(file);
    }

    /**
     * 승인 대기 목록 (admin 전용 — Controller 에서 hasRole 검증).
     *
     * <p>대시보드 "내 승인 대기" 위젯 (Phase 5-8) 에서도 재사용한다.</p>
     */
    @Transactional(readOnly = true)
    public Page<EvidenceFileDto.Response> findPending(Pageable pageable) {
        return evidenceFileRepository.findByReviewStatus(ReviewStatus.pending, pageable)
                .map(EvidenceFileDto.Response::from);
    }

    /**
     * 검토 상태 전이 가능 여부 확인.
     * pending 만 approve/reject 대상이 된다. 나머지는 400 예외로 막아
     * reviewed_at 덮어쓰기·이중 처리를 방지한다.
     */
    private void assertPendingStatus(EvidenceFile file, String action) {
        ReviewStatus current = file.getReviewStatus();
        if (current != ReviewStatus.pending) {
            throw new BusinessException(String.format(
                    "%s 처리할 수 없습니다. 현재 상태: %s (검토 대기 상태에서만 처리 가능)",
                    action, current != null ? current.name() : "null"));
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ========================================
    // Phase 5-2: 업로드
    // ========================================

    /**
     * 수동 파일 업로드 — 버전 자동 증가
     *
     * <p>역할별 초기 검토 상태:</p>
     * <ul>
     *   <li>admin → auto_approved (관리자 직접 업로드는 검토 생략)</li>
     *   <li>담당자 → pending (관리자 검토 대기)</li>
     * </ul>
     *
     * <p>v15.7: storage path 빌드 시 {@code evidenceType.getControlNode().getCode()} →
     * {@code getControlNode().getCode()} (Q1=B).</p>
     */
    @Transactional
    public EvidenceFileDto.Response upload(Long evidenceTypeId,
                                           MultipartFile file,
                                           UserPrincipal uploader,
                                           String submitNote) {
        EvidenceType evidenceType = evidenceTypeRepository.findById(evidenceTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 유형", evidenceTypeId));

        int nextVersion = evidenceFileRepository.findMaxVersionByEvidenceTypeId(evidenceTypeId)
                .orElse(0) + 1;

        // v15.7 Q1=B: getControl() → getControlNode()
        String savedPath = saveFile(file, evidenceType.getControlNode().getCode(), evidenceTypeId);

        User uploaderEntity = userRepository.findById(uploader.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("사용자", uploader.getUserId()));

        boolean isAdmin = "admin".equalsIgnoreCase(uploader.getRole());
        ReviewStatus initialStatus = isAdmin ? ReviewStatus.auto_approved : ReviewStatus.pending;

        EvidenceFile evidenceFile = EvidenceFile.builder()
                .evidenceType(evidenceType)
                .fileName(file.getOriginalFilename())
                .filePath(savedPath)
                .fileSize(file.getSize())
                .version(nextVersion)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .uploadedBy(uploaderEntity)
                .submitNote(submitNote)
                .reviewStatus(initialStatus)
                .build();

        evidenceFile = evidenceFileRepository.save(evidenceFile);
        log.info("증빙 파일 업로드: {} (v{}, by={}/{}, status={})",
                file.getOriginalFilename(), nextVersion,
                uploader.getEmail(), uploader.getRole(), initialStatus);

        return EvidenceFileDto.Response.from(evidenceFile);
    }

    // ========================================
    // 조회
    // ========================================

    @Transactional(readOnly = true)
    public Page<EvidenceFileDto.Response> findAll(Pageable pageable) {
        return evidenceFileRepository.findAll(pageable)
                .map(EvidenceFileDto.Response::from);
    }

    @Transactional(readOnly = true)
    public List<EvidenceFileDto.Response> findByEvidenceType(Long evidenceTypeId) {
        return evidenceFileRepository.findByEvidenceTypeIdOrderByVersionDesc(evidenceTypeId).stream()
                .map(EvidenceFileDto.Response::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EvidenceFileDto.DownloadResponse download(Long fileId) {
        EvidenceFile file = evidenceFileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 파일", fileId));

        Path filePath = Paths.get(file.getFilePath()).toAbsolutePath().normalize();

        if (!Files.exists(filePath)) {
            log.error("증빙 파일 물리 경로에 파일이 없습니다: {} (fileId={})", filePath, fileId);
            throw new BusinessException("파일이 저장소에 존재하지 않습니다. 관리자에게 문의하세요.");
        }

        if (!Files.isReadable(filePath)) {
            log.error("증빙 파일 읽기 권한 없음: {} (fileId={})", filePath, fileId);
            throw new BusinessException("파일을 읽을 수 없습니다. 관리자에게 문의하세요.");
        }

        try {
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new BusinessException("파일 리소스를 읽을 수 없습니다.");
            }

            String contentType = determineContentType(file.getFileName());

            return EvidenceFileDto.DownloadResponse.builder()
                    .resource(resource)
                    .fileName(file.getFileName())
                    .contentType(contentType)
                    .fileSize(file.getFileSize())
                    .build();

        } catch (MalformedURLException e) {
            log.error("증빙 파일 URL 변환 실패: {} (fileId={})", filePath, fileId, e);
            throw new BusinessException("파일 경로가 올바르지 않습니다.");
        }
    }

    /**
     * leaf (또는 hybrid 노드) 산하 증빙 파일 ZIP 다운로드.
     *
     * <p>v15.3 5-15b — ControlRepository → ControlNodeRepository 위임. {@code nodeId}
     * 는 leaf control_node.id (5-14e Q1=A 정의). leaf 가 아니면 IllegalState — 자식
     * 이슈가 아닌 호출 측 잘못.</p>
     *
     * <p>v15.7: 시그니처 {@code Long controlId} → {@code Long nodeId} (Q3=B URL path
     * variable rename 정합 + Q6=A service param). 본문의 변수 사용 5 곳 + 에러 메시지
     * "nodeId=" 표기와 자연 정합. {@code findByControlId} → {@code findByControlNodeId}
     * (Q5=A).</p>
     */
    @Transactional(readOnly = true)
    public String downloadZip(Long nodeId, OutputStream outputStream) {
        // v15.3 5-15b — ControlRepository → ControlNodeRepository 위임. nodeId 는 leaf control_node.id
        // (5-14e Q1=A 정의). leaf 가 아니면 IllegalState — 자식 이슈가 아닌 호출 측 잘못.
        ControlNode leaf = controlNodeRepository.findById(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("통제항목", nodeId));
        if (leaf.getNodeType() != NodeType.control) {
            throw new BusinessException("통제(leaf) 만 ZIP 다운로드 가능합니다. nodeId=" + nodeId);
        }

        // v15.7 Q5=A: findByControlId → findByControlNodeId
        List<EvidenceType> evidenceTypes = evidenceTypeRepository.findByControlNodeId(nodeId);

        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            int fileCount = 0;

            for (EvidenceType et : evidenceTypes) {
                List<EvidenceFile> files = evidenceFileRepository
                        .findByEvidenceTypeIdOrderByVersionDesc(et.getId());

                if (files.isEmpty()) continue;

                EvidenceFile latestFile = files.get(0);
                Path filePath = Paths.get(latestFile.getFilePath()).toAbsolutePath().normalize();

                if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                    log.warn("ZIP 다운로드: 파일 누락 - {} (fileId={})", filePath, latestFile.getId());
                    continue;
                }

                String entryName = et.getName() + "/" + latestFile.getFileName();
                ZipEntry entry = new ZipEntry(entryName);
                entry.setSize(latestFile.getFileSize());
                zos.putNextEntry(entry);

                Files.copy(filePath, zos);
                zos.closeEntry();
                fileCount++;
            }

            if (fileCount == 0) {
                ZipEntry readme = new ZipEntry("README.txt");
                zos.putNextEntry(readme);
                zos.write("수집된 증빙 파일이 없습니다.".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            log.info("ZIP 다운로드 완료: 통제항목 {} - {}개 파일", leaf.getCode(), fileCount);

        } catch (IOException e) {
            log.error("ZIP 다운로드 실패: 통제항목 {} - {}", leaf.getCode(), e.getMessage(), e);
            throw new BusinessException("ZIP 파일 생성에 실패했습니다.");
        }

        return leaf.getCode() + "_증빙자료.zip";
    }

    @Transactional(readOnly = true)
    public EvidenceFileDto.StatsResponse getStats() {
        long totalFiles = evidenceFileRepository.count();

        LocalDateTime now = LocalDateTime.now();
        int currentYear = now.getYear();
        int currentQuarter = now.get(IsoFields.QUARTER_OF_YEAR);
        long quarterFiles = evidenceFileRepository.findAll().stream()
                .filter(f -> {
                    LocalDateTime collected = f.getCollectedAt();
                    return collected.getYear() == currentYear
                            && collected.get(IsoFields.QUARTER_OF_YEAR) == currentQuarter;
                })
                .count();

        long totalSize = evidenceFileRepository.findAll().stream()
                .mapToLong(EvidenceFile::getFileSize)
                .sum();

        // v15.3 5-15b — controlRepository.count() → ControlNode leaf 한정 카운트.
        // ControlNodeRepository.countByNodeType(NodeType) Spring Data JPA derived query.
        long totalControls = controlNodeRepository.countByNodeType(NodeType.control);
        // v15.7 Q1=B: getControl() → getControlNode()
        long coveredControls = evidenceFileRepository.findAll().stream()
                .map(f -> f.getEvidenceType().getControlNode().getId())
                .distinct()
                .count();
        int coverage = totalControls > 0 ?
                (int) (coveredControls * 100 / totalControls) : 0;

        return EvidenceFileDto.StatsResponse.builder()
                .totalFiles(totalFiles)
                .quarterFiles(quarterFiles)
                .totalSizeBytes(totalSize)
                .controlCoverage(coverage)
                .build();
    }

    @Transactional
    public void delete(Long fileId) {
        EvidenceFile file = evidenceFileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 파일", fileId));

        try {
            Path path = Paths.get(file.getFilePath());
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("물리 파일 삭제 실패: {}", file.getFilePath(), e);
        }

        evidenceFileRepository.delete(file);
    }

    // ========================================
    // 내부 유틸
    // ========================================

    private String saveFile(MultipartFile file, String controlCode, Long evidenceTypeId) {
        try {
            Path dir = Paths.get(storagePath, "evidence", controlCode, String.valueOf(evidenceTypeId))
                    .toAbsolutePath().normalize();
            Files.createDirectories(dir);

            String uniqueName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path filePath = dir.resolve(uniqueName);
            file.transferTo(filePath.toFile());

            return filePath.toString();
        } catch (IOException e) {
            throw new BusinessException("파일 저장에 실패했습니다: " + e.getMessage());
        }
    }

    private String determineContentType(String fileName) {
        if (fileName == null) return "application/octet-stream";

        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";

        return "application/octet-stream";
    }
}