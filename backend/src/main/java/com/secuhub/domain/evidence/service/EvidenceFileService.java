package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.config.security.FileUploadValidator;
import com.secuhub.domain.evidence.dto.EvidenceFileDto;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
// v15.3 5-15b — Control 엔티티 + ControlRepository 제거. ControlNodeRepository 위임.
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.repository.UserRepository;
import com.secuhub.config.audit.Auditable;
import com.secuhub.domain.audit.AuditAction;
import com.secuhub.domain.audit.AuditResult;
import com.secuhub.domain.audit.AuditService;

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
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Map;


/**
 * 증빙 파일 서비스.
 *
 * <h3>AUDIT — 감사 (1c + B: targetName)</h3>
 * <ul>
 *   <li>approve/reject/upload/linkExistingAsset — {@code @Auditable}(targetId/targetName) AOP 기록.
 *       targetName 으로 파일명을 남겨 표의 '대상' 칸에 이름이 보인다.</li>
 *   <li>delete — 명시 기록(삭제 전 파일명 스냅샷 → targetName + detail). "무엇을 삭제했는지" 보존.</li>
 *   <li>download/downloadZip — FILE_DOWNLOAD 명시 기록(targetName = 파일명 / 통제 코드).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceFileService {

    private final EvidenceFileRepository evidenceFileRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final ControlNodeRepository controlNodeRepository;
    private final UserRepository userRepository;

    // v18.6a — Evidence Asset 신규 채널
    private final EvidenceAssetService evidenceAssetService;
    private final EvidenceAssetRepository evidenceAssetRepository;
    private final FileUploadValidator fileUploadValidator;
    private final AuditService auditService; // AUDIT-1c

    @Value("${app.storage.path:./storage}")
    private String storagePath;

    // ========================================
    // Phase 5-4: 승인 플로우
    // ========================================

    @Auditable(action = AuditAction.EVIDENCE_APPROVE, targetType = "EvidenceFile",
               targetId = "#a0", targetName = "#result.fileName")  // a0=fileId
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

    @Auditable(action = AuditAction.EVIDENCE_REJECT, targetType = "EvidenceFile",
               targetId = "#a0", targetName = "#result.fileName")  // a0=fileId
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

    @Transactional(readOnly = true)
    public Page<EvidenceFileDto.Response> findPending(Pageable pageable) {
        return evidenceFileRepository.findByReviewStatus(ReviewStatus.pending, pageable)
                .map(EvidenceFileDto.Response::from);
    }

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
    // Phase 5-2: 업로드 (v18.6a 변경)
    // ========================================

    @Auditable(action = AuditAction.FILE_UPLOAD, targetType = "EvidenceFile",
               targetId = "#a0", targetName = "#a1.originalFilename")  // a0=evidenceTypeId, a1=MultipartFile
    @Transactional
    public EvidenceFileDto.UploadResponse upload(Long evidenceTypeId,
                                                  MultipartFile file,
                                                  UserPrincipal uploader,
                                                  String submitNote,
                                                  boolean forceUpload) {
        // v19.10 — 확장자 allowlist + 크기 + 매직바이트 검증 (위반 시 400)
        fileUploadValidator.validate(file);

        EvidenceType evidenceType = evidenceTypeRepository.findById(evidenceTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 유형", evidenceTypeId));

        User uploaderEntity = userRepository.findById(uploader.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("사용자", uploader.getUserId()));

        // 1. SHA-256 계산
        String sha256 = evidenceAssetService.computeSha256(file);

        // 2. 중복 검사 (forceUpload=false 시)
        if (!forceUpload) {
            Optional<EvidenceAsset> existing = evidenceAssetService.findBySha256(sha256);
            if (existing.isPresent()) {
                long usedInCount = evidenceAssetRepository.countLinkedFiles(existing.get().getId());
                log.info("중복 감지: sha256={}, existingAssetId={}, usedInCount={}, " +
                                "uploader={}, evidenceTypeId={}",
                        sha256, existing.get().getId(), usedInCount,
                        uploader.getEmail(), evidenceTypeId);
                return EvidenceFileDto.UploadResponse.duplicateDetected(
                        existing.get(), usedInCount);
            }
        }

        // 3. 신규 asset 생성 (또는 forceUpload=true 시 별도 생성, Q9)
        EvidenceAsset asset = evidenceAssetService.createNewAssetFromMultipart(
                file, sha256, uploader);

        // 4. EvidenceFile (link) 생성
        int nextVersion = evidenceFileRepository.findMaxVersionByEvidenceTypeId(evidenceTypeId)
                .orElse(0) + 1;

        boolean isAdmin = "admin".equalsIgnoreCase(uploader.getRole());
        ReviewStatus initialStatus = isAdmin ?
                ReviewStatus.auto_approved : ReviewStatus.pending;

        EvidenceFile evidenceFile = EvidenceFile.builder()
                .evidenceType(evidenceType)
                .asset(asset)                       // v18.6a NEW
                .fileName(file.getOriginalFilename())
                .filePath(asset.getFilePath())      // transitional — v18.6b 후 폐기
                .fileSize(file.getSize())
                .version(nextVersion)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .uploadedBy(uploaderEntity)
                .submitNote(submitNote)
                .reviewStatus(initialStatus)
                .build();

        evidenceFile = evidenceFileRepository.save(evidenceFile);
        log.info("증빙 파일 업로드: {} (v{}, by={}/{}, status={}, assetId={}, forceUpload={})",
                file.getOriginalFilename(), nextVersion,
                uploader.getEmail(), uploader.getRole(), initialStatus,
                asset.getId(), forceUpload);

        return EvidenceFileDto.UploadResponse.created(evidenceFile);
    }

    @Auditable(action = AuditAction.FILE_UPLOAD, targetType = "EvidenceFile",
               targetId = "#a0", targetName = "#result.fileName")  // a0=evidenceTypeId
    @Transactional
    public EvidenceFileDto.Response linkExistingAsset(Long evidenceTypeId,
                                                       Long assetId,
                                                       String fileName,
                                                       String submitNote,
                                                       UserPrincipal uploader) {
        EvidenceType evidenceType = evidenceTypeRepository.findById(evidenceTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 유형", evidenceTypeId));

        EvidenceAsset asset = evidenceAssetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 자산", assetId));

        User uploaderEntity = userRepository.findById(uploader.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("사용자", uploader.getUserId()));

        int nextVersion = evidenceFileRepository.findMaxVersionByEvidenceTypeId(evidenceTypeId)
                .orElse(0) + 1;

        boolean isAdmin = "admin".equalsIgnoreCase(uploader.getRole());
        ReviewStatus initialStatus = isAdmin ?
                ReviewStatus.auto_approved : ReviewStatus.pending;

        String resolvedFileName = (fileName != null && !fileName.isBlank())
                ? fileName
                : asset.getOriginalFileName();

        EvidenceFile evidenceFile = EvidenceFile.builder()
                .evidenceType(evidenceType)
                .asset(asset)
                .fileName(resolvedFileName)
                .filePath(asset.getFilePath())     // transitional
                .fileSize(asset.getFileSize())
                .version(nextVersion)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .uploadedBy(uploaderEntity)
                .submitNote(submitNote)
                .reviewStatus(initialStatus)
                .build();

        evidenceFile = evidenceFileRepository.save(evidenceFile);
        log.info("증빙 파일 link (asset reuse): assetId={}, evidenceTypeId={}, v{}, by={}",
                assetId, evidenceTypeId, nextVersion, uploader.getEmail());

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

        Path filePath = Paths.get(file.resolveFilePath()).toAbsolutePath().normalize();

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

            // AUDIT — 다운로드(접근) 기록. 파일명을 대상명으로.
            safeAudit(AuditAction.FILE_DOWNLOAD, AuditResult.SUCCESS, "EvidenceFile",
                    String.valueOf(fileId), file.getFileName(), null);

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

    @Transactional(readOnly = true)
    public String downloadZip(Long nodeId, OutputStream outputStream) {
        ControlNode leaf = controlNodeRepository.findById(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("통제항목", nodeId));
        if (leaf.getNodeType() != NodeType.control) {
            throw new BusinessException("통제(leaf) 만 ZIP 다운로드 가능합니다. nodeId=" + nodeId);
        }

        List<EvidenceType> evidenceTypes = evidenceTypeRepository.findByControlNodeId(nodeId);

        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            int fileCount = 0;

            for (EvidenceType et : evidenceTypes) {
                List<EvidenceFile> files = evidenceFileRepository
                        .findByEvidenceTypeIdOrderByVersionDesc(et.getId());

                if (files.isEmpty()) continue;

                EvidenceFile latestFile = files.get(0);
                Path filePath = Paths.get(latestFile.resolveFilePath()).toAbsolutePath().normalize();

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

            // AUDIT — 일괄(ZIP) 다운로드 기록. 통제 코드를 대상명으로.
            safeAudit(AuditAction.FILE_DOWNLOAD, AuditResult.SUCCESS, "ControlNode",
                    String.valueOf(nodeId), leaf.getCode(),
                    leaf.getCode() + " ZIP (" + fileCount + " files)");

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

        long totalControls = controlNodeRepository.countByNodeType(NodeType.control);
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

    /**
     * 증빙 파일 삭제.
     *
     * <p>AUDIT: "무엇을 삭제했는지"(파일명)는 AOP 로 못 잡으므로 삭제 전 스냅샷을 떠
     * targetName + detail 로 명시 기록한다(@Auditable 미사용).</p>
     */
    @Transactional
    public void delete(Long fileId) {
        EvidenceFile file = evidenceFileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 파일", fileId));

        // AUDIT — 삭제 전 스냅샷 (삭제 후/세션 밖에서도 남도록 미리 캡처)
        String deletedName = file.getFileName();
        Long etId = (file.getEvidenceType() != null) ? file.getEvidenceType().getId() : null;

        Long assetIdToGc = (file.getAsset() != null) ? file.getAsset().getId() : null;

        if (assetIdToGc == null) {
            try {
                Path path = Paths.get(file.getFilePath());
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("물리 파일 삭제 실패 (옛 채널): {}", file.getFilePath(), e);
            }
        }

        evidenceFileRepository.delete(file);
        log.info("증빙 파일 삭제: fileId={}, assetId={}", fileId, assetIdToGc);

        if (assetIdToGc != null) {
            evidenceAssetService.gcIfUnused(assetIdToGc);
        }

        // AUDIT — 무엇을 삭제했는지 (파일명 = 대상명, evidenceTypeId 는 detail)
        safeAudit(AuditAction.FILE_DELETE, AuditResult.SUCCESS, "EvidenceFile",
                String.valueOf(fileId), deletedName,
                auditService.toJson(Map.of(
                        "fileName", String.valueOf(deletedName),
                        "evidenceTypeId", String.valueOf(etId))));
    }

    // ========================================
    // 내부 유틸
    // ========================================

    /**
     * 감사 기록 — 실패가 업무 흐름을 막지 않도록 삼킴.
     * {@code auditService.record} 는 REQUIRES_NEW 라 readOnly tx(다운로드) 안에서도 적재된다.
     */
    private void safeAudit(AuditAction action, AuditResult result,
                           String targetType, String targetId, String targetName, String detail) {
        try {
            auditService.record(action, result, targetType, targetId, targetName, detail);
        } catch (Exception ignore) {
            // 감사 실패는 본 흐름에 영향 없음
        }
    }

    /**
     * 옛 저장 흐름.
     * @deprecated v18.6a 부터 {@link EvidenceAssetService#createNewAssetFromMultipart} 가 대체.
     */
    @Deprecated
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