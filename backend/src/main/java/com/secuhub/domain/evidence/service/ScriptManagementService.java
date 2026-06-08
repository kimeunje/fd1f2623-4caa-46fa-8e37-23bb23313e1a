package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.domain.evidence.dto.ScriptManagementDto;
import com.secuhub.domain.evidence.dto.ScriptVersionDto;
import com.secuhub.domain.evidence.entity.Script;
import com.secuhub.domain.evidence.entity.ScriptVersion;
import com.secuhub.domain.evidence.repository.CollectionJobRepository;
import com.secuhub.domain.evidence.repository.ScriptRepository;
import com.secuhub.domain.evidence.repository.ScriptVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/**
 * v18.8.2 — Python 스크립트 UID 기반 관리.
 * v19.4 — 버전 이력 + 롤백 (carry-over ⑤).
 *
 * <p>스크립트 본문은 기존대로 {@code {base-dir}/{uuid}.py} 파일에 저장·실행되고(실행 경로
 * 무변경), 저장(create/update)할 때마다 {@link ScriptVersion} 으로 스냅샷이 적재된다.
 * 롤백은 옛 버전 내용을 복사한 "새 버전"을 만들어 전진하므로 이력은 사라지지 않고,
 * "실행 파일 = 항상 최신 versionNo" 불변식이 유지된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptManagementService {

    private final ScriptRepository scriptRepository;
    private final CollectionJobRepository collectionJobRepository;  // v18.8.7 — 사용 중 검사용
    private final ScriptVersionRepository scriptVersionRepository;  // v19.4 — 버전 이력

    @Value("${app.scripts.base-dir:./scripts}")
    private String scriptsBaseDir;

    /** 파일 크기 제한 — 1MB (Python 스크립트로 충분). */
    private static final long MAX_SCRIPT_SIZE = 1024 * 1024L;

    // ========================================
    // CRUD
    // ========================================

    /**
     * 신규 작성 — UUID 부여 + {uuid}.py 파일 저장 + entity save + v1 기록.
     */
    @Transactional
    public ScriptManagementDto.ScriptResponse create(ScriptManagementDto.CreateRequest req) {
        validateContentSize(req.getContent());

        String uuid = UUID.randomUUID().toString();
        String filename = uuid + ".py";
        Path target = resolveTargetPath(filename);

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, req.getContent(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new BusinessException("스크립트 파일 저장 실패: " + e.getMessage());
        }

        try {
            Script script = Script.builder()
                    .filePath(filename)
                    .contentSize((long) req.getContent().getBytes(StandardCharsets.UTF_8).length)
                    .build();
            Script saved = scriptRepository.save(script);

            // v19.4 — 최초 버전 기록
            recordVersion(saved.getId(), 1, req.getContent(), "최초 작성");

            log.info("v18.8.3 스크립트 신규 등록: id={}, uuid={}, size={}",
                    saved.getId(), uuid, saved.getContentSize());
            return toResponse(saved, req.getContent());
        } catch (Exception e) {
            try { Files.deleteIfExists(target); } catch (IOException ignore) { }
            throw new BusinessException("스크립트 entity 저장 실패: " + e.getMessage());
        }
    }

    /**
     * 기존 스크립트 수정 — 같은 file_path 에 덮어쓰기 + 새 버전 기록.
     *
     * <p>레거시(버저닝 이전) 스크립트는 수정 직전 현재 내용을 v1 로 자동 시드한 뒤,
     * 새 내용을 다음 버전으로 기록한다.</p>
     */
    @Transactional
    public ScriptManagementDto.ScriptResponse update(Long id, ScriptManagementDto.UpdateRequest req) {
        validateContentSize(req.getContent());

        Script script = scriptRepository.findById(id)
                .orElseThrow(() -> new BusinessException("스크립트가 존재하지 않습니다: id=" + id));

        ensureSeedVersion(script); // v19.4 — 레거시 시드

        Path target = resolveTargetPath(script.getFilePath());
        try {
            Files.writeString(target, req.getContent(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new BusinessException("스크립트 파일 수정 실패: " + e.getMessage());
        }

        script.setContentSize((long) req.getContent().getBytes(StandardCharsets.UTF_8).length);
        Script saved = scriptRepository.save(script);

        // v19.4 — 새 버전 기록 (메모 입력은 FE sub-phase 에서 추가 예정; 지금은 고정값)
        int next = scriptVersionRepository.findMaxVersionNo(id) + 1;
        recordVersion(id, next, req.getContent(), "수정");

        log.info("v18.8.2 스크립트 수정: id={}, size={}, version={}", id, saved.getContentSize(), next);
        return toResponse(saved, req.getContent());
    }

    /**
     * 스크립트 내용 조회 — FE 의 ScriptEditorDialog 가 편집 모드 진입 시 호출.
     */
    public ScriptManagementDto.ScriptResponse getContent(Long id) {
        Script script = scriptRepository.findById(id)
                .orElseThrow(() -> new BusinessException("스크립트가 존재하지 않습니다: id=" + id));
        return toResponse(script, readFile(script));
    }

    /**
     * v18.8.7 — 스크립트 삭제 (Hard delete). 버전 이력은 FK ON DELETE CASCADE 로 동반 삭제.
     */
    @Transactional
    public void delete(Long id) {
        Script script = scriptRepository.findById(id)
                .orElseThrow(() -> new BusinessException("스크립트가 존재하지 않습니다: id=" + id));

        if (collectionJobRepository.existsByScriptId(id)) {
            throw new BusinessException(
                    "이 스크립트를 사용 중인 수집 작업이 있습니다. " +
                    "먼저 작업에서 스크립트를 해제하거나 작업을 삭제한 후 다시 시도해주세요.");
        }

        Path target = resolveTargetPath(script.getFilePath());
        try {
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) {
                log.info("v18.8.7 스크립트 파일 삭제: {} (id={})", target, id);
            } else {
                log.warn("v18.8.7 스크립트 파일 부재 (entity 만 삭제 진행): {} (id={})", target, id);
            }
        } catch (IOException e) {
            log.warn("v18.8.7 스크립트 파일 삭제 실패 (entity 는 계속 삭제): id={}, path={}, error={}",
                    id, target, e.getMessage());
        }

        scriptRepository.deleteById(id);
        log.info("v18.8.7 스크립트 entity 삭제 완료: id={}", id);
    }

    // ========================================
    // v19.4 — 버전 이력 / 롤백
    // ========================================

    /**
     * 버전 목록 (오래된 순). 레거시 스크립트는 현재 내용을 v1 로 시드한 뒤 반환.
     */
    @Transactional
    public List<ScriptVersionDto.VersionResponse> listVersions(Long scriptId) {
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new BusinessException("스크립트가 존재하지 않습니다: id=" + scriptId));
        ensureSeedVersion(script);
        return scriptVersionRepository.findByScriptIdOrderByVersionNoAsc(scriptId).stream()
                .map(ScriptVersionDto.VersionResponse::from)
                .toList();
    }

    /**
     * 특정 버전 내용 (미리보기).
     */
    @Transactional
    public ScriptVersionDto.VersionContentResponse getVersionContent(Long scriptId, int versionNo) {
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new BusinessException("스크립트가 존재하지 않습니다: id=" + scriptId));
        ensureSeedVersion(script);
        ScriptVersion v = scriptVersionRepository.findByScriptIdAndVersionNo(scriptId, versionNo)
                .orElseThrow(() -> new BusinessException(
                        "해당 버전이 없습니다: id=" + scriptId + ", version=" + versionNo));
        return ScriptVersionDto.VersionContentResponse.from(v);
    }

    /**
     * 롤백 — 대상 버전 내용을 실행 파일에 다시 쓰고, 그 내용으로 "새 버전"을 전진 기록.
     * 이력은 사라지지 않으며 "실행 파일 = 최신 버전" 불변식 유지.
     */
    @Transactional
    public ScriptManagementDto.ScriptResponse rollback(Long scriptId, int versionNo) {
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new BusinessException("스크립트가 존재하지 않습니다: id=" + scriptId));
        ensureSeedVersion(script);

        ScriptVersion target = scriptVersionRepository.findByScriptIdAndVersionNo(scriptId, versionNo)
                .orElseThrow(() -> new BusinessException(
                        "해당 버전이 없습니다: id=" + scriptId + ", version=" + versionNo));

        String content = target.getContent();
        validateContentSize(content);

        Path file = resolveTargetPath(script.getFilePath());
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new BusinessException("롤백 파일 쓰기 실패: " + e.getMessage());
        }

        script.setContentSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        scriptRepository.save(script);

        int next = scriptVersionRepository.findMaxVersionNo(scriptId) + 1;
        recordVersion(scriptId, next, content, "v" + versionNo + " 내용으로 복귀");

        log.info("v19.4 스크립트 롤백: id={}, from=v{}, newVersion=v{}", scriptId, versionNo, next);
        return toResponse(script, content);
    }

    // ========================================
    // 내부 helper
    // ========================================

    /** 레거시(버전 0건) 스크립트면 현재 파일 내용을 v1 로 시드. */
    private void ensureSeedVersion(Script script) {
        if (scriptVersionRepository.existsByScriptId(script.getId())) {
            return;
        }
        String current;
        try {
            current = readFile(script);
        } catch (BusinessException e) {
            log.warn("레거시 시드 실패 (파일 부재): id={} — 버전 시드 생략", script.getId());
            return;
        }
        recordVersion(script.getId(), 1, current, "최초 버전 (자동 시드)");
        log.info("v19.4 레거시 스크립트 v1 자동 시드: id={}", script.getId());
    }

    private void recordVersion(Long scriptId, int versionNo, String content, String note) {
        scriptVersionRepository.save(ScriptVersion.builder()
                .scriptId(scriptId)
                .versionNo(versionNo)
                .content(content)
                .contentSize((long) content.getBytes(StandardCharsets.UTF_8).length)
                .note(note)
                .createdBy(currentUserId())
                .build());
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getUserId();
        }
        return null;
    }

    private String readFile(Script script) {
        Path target = resolveTargetPath(script.getFilePath());
        if (!Files.exists(target)) {
            throw new BusinessException("스크립트 파일이 존재하지 않습니다: " + script.getFilePath());
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("스크립트 파일 읽기 실패: " + e.getMessage());
        }
    }

    private Path resolveTargetPath(String relativeFilename) {
        Path baseDir = Paths.get(scriptsBaseDir).toAbsolutePath().normalize();
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
                    "스크립트 크기가 1MB 를 초과합니다: " + byteSize + " bytes (최대 " + MAX_SCRIPT_SIZE + ")");
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