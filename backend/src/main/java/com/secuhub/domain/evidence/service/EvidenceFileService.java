package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.EvidenceFileDto;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceFileService {

    private final EvidenceFileRepository evidenceFileRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final ControlRepository controlRepository;

    @Value("${app.storage.path:./storage}")
    private String storagePath;

    /**
     * 수동 파일 업로드 — 버전 자동 증가
     */
    @Transactional
    public EvidenceFileDto.Response upload(Long evidenceTypeId, MultipartFile file) {
        EvidenceType evidenceType = evidenceTypeRepository.findById(evidenceTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 유형", evidenceTypeId));

        // 다음 버전 계산
        int nextVersion = evidenceFileRepository.findMaxVersionByEvidenceTypeId(evidenceTypeId)
                .orElse(0) + 1;

        // 파일 저장
        String savedPath = saveFile(file, evidenceType.getControl().getCode(), evidenceTypeId);

        EvidenceFile evidenceFile = EvidenceFile.builder()
                .evidenceType(evidenceType)
                .fileName(file.getOriginalFilename())
                .filePath(savedPath)
                .fileSize(file.getSize())
                .version(nextVersion)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .build();

        evidenceFile = evidenceFileRepository.save(evidenceFile);
        log.info("증빙 파일 업로드 완료: {} (v{})", file.getOriginalFilename(), nextVersion);

        return EvidenceFileDto.Response.from(evidenceFile);
    }

    /**
     * 전체 증빙 파일 목록 (페이징)
     */
    @Transactional(readOnly = true)
    public Page<EvidenceFileDto.Response> findAll(Pageable pageable) {
        return evidenceFileRepository.findAll(pageable)
                .map(EvidenceFileDto.Response::from);
    }

    /**
     * 증빙 유형별 파일 이력
     */
    @Transactional(readOnly = true)
    public List<EvidenceFileDto.Response> findByEvidenceType(Long evidenceTypeId) {
        return evidenceFileRepository.findByEvidenceTypeIdOrderByVersionDesc(evidenceTypeId).stream()
                .map(EvidenceFileDto.Response::from)
                .toList();
    }

    /**
     * 증빙 파일 다운로드
     *
     * 1. DB에서 파일 메타정보 조회
     * 2. 물리 파일 경로 검증
     * 3. Resource 객체 반환
     *
     * @return EvidenceFileDto.DownloadResponse (Resource + 원본 파일명 + Content-Type)
     */
    @Transactional(readOnly = true)
    public EvidenceFileDto.DownloadResponse download(Long fileId) {
        EvidenceFile file = evidenceFileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 파일", fileId));

        Path filePath = Paths.get(file.getFilePath()).toAbsolutePath().normalize();

        // 물리 파일 존재 여부 확인
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

            // Content-Type 추정
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
     * 통제항목별 전체 증빙 파일 ZIP 다운로드 (신규 추가)
     *
     * 해당 통제항목에 속한 모든 증빙유형의 최신 버전 파일을 ZIP으로 묶어서
     * OutputStream에 직접 씁니다 (StreamingResponseBody 호환).
     *
     * @param controlId 통제항목 ID
     * @param outputStream ZIP 파일을 쓸 OutputStream
     * @return ZIP 파일명 (통제항목 코드 기반)
     */
    @Transactional(readOnly = true)
    public String downloadZip(Long controlId, OutputStream outputStream) {
        Control control = controlRepository.findById(controlId)
                .orElseThrow(() -> new ResourceNotFoundException("통제항목", controlId));

        List<EvidenceType> evidenceTypes = evidenceTypeRepository.findByControlId(controlId);

        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            int fileCount = 0;

            for (EvidenceType et : evidenceTypes) {
                // 각 증빙유형의 최신 버전 파일만 가져옴
                List<EvidenceFile> files = evidenceFileRepository
                        .findByEvidenceTypeIdOrderByVersionDesc(et.getId());

                if (files.isEmpty()) continue;

                EvidenceFile latestFile = files.get(0);
                Path filePath = Paths.get(latestFile.getFilePath()).toAbsolutePath().normalize();

                if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                    log.warn("ZIP 다운로드: 파일 누락 - {} (fileId={})", filePath, latestFile.getId());
                    continue;
                }

                // ZIP 엔트리명: 증빙유형명/파일명
                String entryName = et.getName() + "/" + latestFile.getFileName();
                ZipEntry entry = new ZipEntry(entryName);
                entry.setSize(latestFile.getFileSize());
                zos.putNextEntry(entry);

                Files.copy(filePath, zos);
                zos.closeEntry();
                fileCount++;
            }

            if (fileCount == 0) {
                // 빈 ZIP이면 README 추가
                ZipEntry readme = new ZipEntry("README.txt");
                zos.putNextEntry(readme);
                zos.write("수집된 증빙 파일이 없습니다.".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            log.info("ZIP 다운로드 완료: 통제항목 {} - {}개 파일", control.getCode(), fileCount);

        } catch (IOException e) {
            log.error("ZIP 다운로드 실패: 통제항목 {} - {}", control.getCode(), e.getMessage(), e);
            throw new BusinessException("ZIP 파일 생성에 실패했습니다.");
        }

        return control.getCode() + "_증빙자료.zip";
    }

    /**
     * 증빙 파일 통계
     */
    @Transactional(readOnly = true)
    public EvidenceFileDto.StatsResponse getStats() {
        List<EvidenceFile> allFiles = evidenceFileRepository.findAll();

        long totalFiles = allFiles.size();
        long totalSize = allFiles.stream().mapToLong(EvidenceFile::getFileSize).sum();

        // 이번 분기 파일 수
        LocalDateTime now = LocalDateTime.now();
        int currentQuarter = now.get(IsoFields.QUARTER_OF_YEAR);
        int currentYear = now.getYear();
        long quarterFiles = allFiles.stream()
                .filter(f -> f.getCollectedAt() != null
                        && f.getCollectedAt().getYear() == currentYear
                        && f.getCollectedAt().get(IsoFields.QUARTER_OF_YEAR) == currentQuarter)
                .count();

        // 통제항목 커버리지
        long totalControls = controlRepository.count();
        long coveredControls = 0;
        if (totalControls > 0) {
            List<Control> controls = controlRepository.findAll();
            coveredControls = controls.stream()
                    .filter(ctrl -> {
                        List<EvidenceType> types = evidenceTypeRepository.findByControlId(ctrl.getId());
                        if (types.isEmpty()) return false;
                        return types.stream().allMatch(et ->
                                !evidenceFileRepository.findByEvidenceTypeIdOrderByVersionDesc(et.getId()).isEmpty());
                    })
                    .count();
        }

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
     * 파일 삭제
     */
    @Transactional
    public void delete(Long fileId) {
        EvidenceFile file = evidenceFileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 파일", fileId));

        // 물리 파일 삭제
        try {
            Path path = Paths.get(file.getFilePath());
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("물리 파일 삭제 실패: {}", file.getFilePath(), e);
        }

        evidenceFileRepository.delete(file);
    }

    // ========================================
    // 내부 유틸 메서드
    // ========================================

    /**
     * 물리 파일 저장 — 절대경로로 변환하여 working directory와 무관하게 동작
     */
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

    /**
     * 파일 확장자 기반 Content-Type 결정
     */
    private String determineContentType(String fileName) {
        if (fileName == null) return "application/octet-stream";

        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".log")) return "text/plain";

        return "application/octet-stream";
    }
}