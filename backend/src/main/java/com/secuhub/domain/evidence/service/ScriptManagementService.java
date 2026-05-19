package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.domain.evidence.dto.ScriptManagementDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * v18.8 — 어드민 UI 만으로 Python 스크립트 등록/수정 (L_USER_NEEDS_REDIRECT).
 *
 * <p>운영 가설 — admin 이 SSH 없이 어드민 UI 만으로 수집 스크립트 관리.
 * v18.7 의 진단 패널에서 selector 깨짐 발견 → 즉시 수정 → 재실행 흐름 정합.</p>
 *
 * <h3>책임</h3>
 * <ul>
 *   <li>파일 I/O 만 (Files.readString / writeString / list)</li>
 *   <li>검증 — 확장자 (.py) / path traversal / size 제한 (1MB)</li>
 *   <li>CollectionJob 의 scriptPath 매핑은 본 service 책임 아님 (ScriptExecutionService 정합)</li>
 * </ul>
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>base-dir 밖 path 차단 (resolveAndValidatePath)</li>
 *   <li>filename 검증 = DTO 의 @Pattern 으로 1차 + 본 service 의 normalize 로 2차</li>
 *   <li>Controller 가 @PreAuthorize("hasRole('ADMIN')") 으로 권한 차단</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptManagementService {

    @Value("${app.scripts.base-dir:./scripts}")
    private String scriptsBaseDir;

    /** 파일 크기 제한 — 1MB (Python 스크립트로 충분). */
    private static final long MAX_SCRIPT_SIZE = 1024 * 1024L;

    /**
     * 신규 업로드.
     *
     * <p>충돌 시 거부 (Q4 결정). 사용자가 다른 이름으로 재업로드.</p>
     *
     * @throws BusinessException 충돌 / size 초과 / I/O 실패
     */
    public ScriptManagementDto.ScriptContent upload(ScriptManagementDto.UploadRequest req) {
        validateContentSize(req.getContent());
        Path target = resolveAndValidatePath(req.getFilename());

        if (Files.exists(target)) {
            throw new BusinessException("동일한 파일명의 스크립트가 이미 존재합니다: " + req.getFilename()
                    + ". 다른 이름으로 등록하거나 기존 스크립트를 수정해주세요.");
        }

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, req.getContent(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            log.info("v18.8 스크립트 신규 등록: filename={}, size={}", req.getFilename(), req.getContent().length());
            return toScriptContent(target);
        } catch (IOException e) {
            throw new BusinessException("스크립트 파일 저장 실패: " + e.getMessage());
        }
    }

    /**
     * 기존 스크립트 수정 (덮어쓰기).
     *
     * <p>파일이 없으면 거부 (신규는 별도 upload). v18.7 진단 패널의 selector 수정 흐름 정합.</p>
     */
    public ScriptManagementDto.ScriptContent update(String filename, ScriptManagementDto.UpdateRequest req) {
        validateContentSize(req.getContent());
        Path target = resolveAndValidatePath(filename);

        if (!Files.exists(target)) {
            throw new BusinessException("수정 대상 스크립트가 존재하지 않습니다: " + filename
                    + ". 신규 업로드 메뉴를 활용해주세요.");
        }

        try {
            Files.writeString(target, req.getContent(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            log.info("v18.8 스크립트 수정: filename={}, size={}", filename, req.getContent().length());
            return toScriptContent(target);
        } catch (IOException e) {
            throw new BusinessException("스크립트 파일 수정 실패: " + e.getMessage());
        }
    }

    /**
     * 스크립트 내용 조회.
     *
     * <p>FE 의 ScriptEditorDialog 가 편집 모드 진입 시 호출.</p>
     */
    public ScriptManagementDto.ScriptContent getContent(String filename) {
        Path target = resolveAndValidatePath(filename);

        if (!Files.exists(target)) {
            throw new BusinessException("스크립트 파일이 존재하지 않습니다: " + filename);
        }

        return toScriptContent(target);
    }

    /**
     * base-dir 안의 .py 스크립트 목록.
     *
     * <p>templates/ 하위 디렉토리는 제외 (selenium_wrapper.py 같은 보조 모듈은 노출 안 함).</p>
     */
    public ScriptManagementDto.ListResponse list() {
        Path baseDir = Paths.get(scriptsBaseDir).toAbsolutePath().normalize();

        if (!Files.isDirectory(baseDir)) {
            return ScriptManagementDto.ListResponse.builder()
                    .scripts(List.of())
                    .build();
        }

        try (Stream<Path> stream = Files.list(baseDir)) {
            List<ScriptManagementDto.ScriptInfo> scripts = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".py"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(this::toScriptInfo)
                    .toList();

            return ScriptManagementDto.ListResponse.builder()
                    .scripts(scripts)
                    .build();
        } catch (IOException e) {
            throw new BusinessException("스크립트 디렉토리 조회 실패: " + e.getMessage());
        }
    }

    // ========================================
    // 내부 helper
    // ========================================

    /**
     * 파일명 → base-dir 안의 절대 경로로 변환 + path traversal 검증.
     *
     * <p>filename 에 / \ .. 가 포함되어도 base-dir 밖으로 못 나가도록 차단.</p>
     */
    private Path resolveAndValidatePath(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BusinessException("파일명이 비어있습니다.");
        }

        Path baseDir = Paths.get(scriptsBaseDir).toAbsolutePath().normalize();
        // filename 의 path separator 제거 (path traversal 방지 — DTO @Pattern 외 2차 방어)
        String safeFilename = Paths.get(filename).getFileName().toString();
        Path resolved = baseDir.resolve(safeFilename).normalize();

        if (!resolved.startsWith(baseDir)) {
            log.warn("v18.8 path traversal 차단: filename={}, resolved={}, base={}",
                    filename, resolved, baseDir);
            throw new BusinessException("허용되지 않은 파일 경로입니다: " + filename);
        }

        if (!safeFilename.toLowerCase().endsWith(".py")) {
            throw new BusinessException("Python 스크립트 (.py) 만 허용됩니다: " + filename);
        }

        return resolved;
    }

    /**
     * 내용 크기 검증 — UTF-8 byte 기준 1MB.
     */
    private void validateContentSize(String content) {
        if (content == null) {
            throw new BusinessException("스크립트 내용이 null 입니다.");
        }
        long byteSize = content.getBytes(StandardCharsets.UTF_8).length;
        if (byteSize > MAX_SCRIPT_SIZE) {
            throw new BusinessException(
                    String.format("스크립트 크기가 너무 큽니다: %d bytes (최대 %d bytes / 1MB)",
                            byteSize, MAX_SCRIPT_SIZE));
        }
    }

    private ScriptManagementDto.ScriptContent toScriptContent(Path target) {
        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class);
            return ScriptManagementDto.ScriptContent.builder()
                    .filename(target.getFileName().toString())
                    .content(content)
                    .size(attrs.size())
                    .lastModified(LocalDateTime.ofInstant(
                            attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault()))
                    .build();
        } catch (IOException e) {
            throw new BusinessException("스크립트 파일 읽기 실패: " + e.getMessage());
        }
    }

    private ScriptManagementDto.ScriptInfo toScriptInfo(Path target) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class);
            return ScriptManagementDto.ScriptInfo.builder()
                    .filename(target.getFileName().toString())
                    .size(attrs.size())
                    .lastModified(LocalDateTime.ofInstant(
                            attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault()))
                    .scriptPath(target.toString())
                    .build();
        } catch (IOException e) {
            throw new BusinessException("스크립트 파일 정보 조회 실패: " + e.getMessage());
        }
    }
}