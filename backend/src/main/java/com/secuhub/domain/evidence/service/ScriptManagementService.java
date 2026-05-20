package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.domain.evidence.dto.ScriptManagementDto;
import com.secuhub.domain.evidence.entity.Script;
import com.secuhub.domain.evidence.repository.ScriptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * v18.8.2 — Python 스크립트 UID 기반 관리.
 *
 * <p>사용자 의도: "스크립트 이름은 의미 없다. UID 로 관리." → filename 입력 제거,
 * 시스템 자동 id 부여 + {@code {base-dir}/{id}.py} 파일 시스템 매핑.</p>
 *
 * <h3>책임</h3>
 * <ul>
 *   <li>Script entity CRUD (DB)</li>
 *   <li>파일 시스템 I/O ({@code {base-dir}/{id}.py})</li>
 *   <li>크기 제한 검증 (1MB)</li>
 *   <li>path traversal 차단 (id 기반이므로 자연 차단, 추가 검증 불필요)</li>
 * </ul>
 *
 * <h3>호출 흐름</h3>
 * <ol>
 *   <li>create — content 받아 Script entity 저장 → id 부여 → {id}.py 파일 작성</li>
 *   <li>update — id 로 entity 조회 → 같은 file_path 에 덮어쓰기 + content_size 갱신</li>
 *   <li>getContent — id 로 entity 조회 → file_path 읽어 내용 반환</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptManagementService {

    private final ScriptRepository scriptRepository;

    @Value("${app.scripts.base-dir:./scripts}")
    private String scriptsBaseDir;

    /** 파일 크기 제한 — 1MB (Python 스크립트로 충분). */
    private static final long MAX_SCRIPT_SIZE = 1024 * 1024L;

    /**
     * 신규 작성 — UUID 부여 + {uuid}.py 파일 저장 + entity save.
     *
     * <p>v18.8.3 — UUID 파일명으로 변경 (DB id 노출 방지, 추측 불가, 충돌 확률 0).
     * DB id 는 PK 로 유지 (CollectionJob.script FK 관계 정합).</p>
     *
     * <p>실패 시 정리 — 파일 작성 후 entity save 실패 시 파일 best-effort 삭제.</p>
     */
    @Transactional
    public ScriptManagementDto.ScriptResponse create(ScriptManagementDto.CreateRequest req) {
        validateContentSize(req.getContent());

        // 1 단계 — UUID 부여 + filename 결정 (DB save 전에 미리 확정)
        String uuid = UUID.randomUUID().toString();
        String filename = uuid + ".py";
        Path target = resolveTargetPath(filename);

        // 2 단계 — 파일 작성 먼저
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, req.getContent(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new BusinessException("스크립트 파일 저장 실패: " + e.getMessage());
        }

        // 3 단계 — entity save (실패 시 파일 정리)
        try {
            Script script = Script.builder()
                    .filePath(filename)
                    .contentSize((long) req.getContent().getBytes(StandardCharsets.UTF_8).length)
                    .build();
            Script saved = scriptRepository.save(script);
            log.info("v18.8.3 스크립트 신규 등록: id={}, uuid={}, size={}",
                    saved.getId(), uuid, saved.getContentSize());
            return toResponse(saved, req.getContent());
        } catch (Exception e) {
            // entity save 실패 시 파일 best-effort 정리 (고아 파일 회피)
            try { Files.deleteIfExists(target); } catch (IOException ignore) { }
            throw new BusinessException("스크립트 entity 저장 실패: " + e.getMessage());
        }
    }

    /**
     * 기존 스크립트 수정 — 같은 file_path 에 덮어쓰기.
     *
     * <p>v18.7 진단 패널의 selector 수정 흐름 정합. 같은 작업의 scriptId 유지 →
     * 재실행 시 수정 반영 (Q6 정합).</p>
     */
    @Transactional
    public ScriptManagementDto.ScriptResponse update(Long id, ScriptManagementDto.UpdateRequest req) {
        validateContentSize(req.getContent());

        Script script = scriptRepository.findById(id)
                .orElseThrow(() -> new BusinessException("스크립트가 존재하지 않습니다: id=" + id));

        Path target = resolveTargetPath(script.getFilePath());

        try {
            Files.writeString(target, req.getContent(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE);  // 파일이 없어졌으면 새로 작성
        } catch (IOException e) {
            throw new BusinessException("스크립트 파일 수정 실패: " + e.getMessage());
        }

        script.setContentSize((long) req.getContent().getBytes(StandardCharsets.UTF_8).length);
        Script saved = scriptRepository.save(script);
        log.info("v18.8.2 스크립트 수정: id={}, size={}", id, saved.getContentSize());
        return toResponse(saved, req.getContent());
    }

    /**
     * 스크립트 내용 조회 — FE 의 ScriptEditorDialog 가 편집 모드 진입 시 호출.
     */
    public ScriptManagementDto.ScriptResponse getContent(Long id) {
        Script script = scriptRepository.findById(id)
                .orElseThrow(() -> new BusinessException("스크립트가 존재하지 않습니다: id=" + id));

        Path target = resolveTargetPath(script.getFilePath());
        if (!Files.exists(target)) {
            throw new BusinessException("스크립트 파일이 존재하지 않습니다: " + script.getFilePath());
        }

        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            return toResponse(script, content);
        } catch (IOException e) {
            throw new BusinessException("스크립트 파일 읽기 실패: " + e.getMessage());
        }
    }

    // ========================================
    // 내부 helper
    // ========================================

    private Path resolveTargetPath(String relativeFilename) {
        Path baseDir = Paths.get(scriptsBaseDir).toAbsolutePath().normalize();
        // filename 만 사용 (path separator 제거로 안전성 확보)
        String safeFilename = Paths.get(relativeFilename).getFileName().toString();
        return baseDir.resolve(safeFilename).normalize();
    }

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

    private ScriptManagementDto.ScriptResponse toResponse(Script script, String content) {
        return ScriptManagementDto.ScriptResponse.builder()
                .id(script.getId())
                .content(content)
                .contentSize(script.getContentSize())
                .createdAt(script.getCreatedAt())
                .updatedAt(script.getUpdatedAt())
                .build();
    }
}