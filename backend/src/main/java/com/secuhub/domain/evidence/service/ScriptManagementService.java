package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.config.audit.Auditable;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.domain.audit.AuditAction;
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
 * <p>AUDIT-1: create/update/delete/rollback 에 {@code @Auditable} (targetId 포함) —
 * AuditAspect 가 성공/실패 + 대상 id 기록.</p>
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

    /** 파일 크기 제한 — 1MB. */
    private static final long MAX_SCRIPT_SIZE = 1024 * 1024L;

    // ========================================
    // CRUD
    // ========================================

    @Auditable(action = AuditAction.SCRIPT_CREATE, targetType = "Script", targetId = "#result.id")
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

            recordVersion(saved.getId(), 1, req.getContent(), "최초 작성");

            log.info("v18.8.3 스크립트 신규 등록: id={}, uuid={}, size={}",
                    saved.getId(), uuid, saved.getContentSize());
            return toResponse(saved, req.getContent());
        } catch (Exception e) {
            try { Files.deleteIfExists(target); } catch (IOException ignore) { }
            throw new BusinessException("스크립트 entity 저장 실패: " + e.getMessage());
        }
    }

    @Auditable(action = AuditAction.SCRIPT_UPDATE, targetType = "Script", targetId = "#a0")
    @Transactional
    public ScriptManagementDto.ScriptResponse update(Long id, ScriptManagementDto.UpdateRequest req) {
        validateContentSize(req.getContent());

        Script script = scriptRepository.findById(id)
                .orElseThrow(() -> new BusinessException("스크립트가 존재하지 않습니다: id=" + id));

        ensureSeedVersion(script);

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

        int next = scriptVersionRepository.findMaxVersionNo(id) + 1;
        recordVersion(id, next, req.getContent(), "수정");

        log.info("v18.8.2 스크립트 수정: id={}, size={}, version={}", id, saved.getContentSize(), next);
        return toResponse(saved, req.getContent());
    }

    /**
     * v19.19 — 스크립트 독립 복제 (프레임워크 상속 시 사용).
     *
     * <p>원본 {@code {uuid}.py} 내용을 읽어 <b>새 uuid 의 .py 파일 + 새 Script entity</b> 를
     * 생성한다. 복제본은 원본과 내용은 같지만 완전히 독립 — 복제본 수정이 원본에 영향 없음.
     * FrameworkService.inherit 의 Job 복제에서 {@code script} FK 를 새 사본으로 연결하기 위함.</p>
     *
     * <p>감사(@Auditable) 없음 — 프레임워크 상속의 하위 작업이라 상위 FRAMEWORK_CHANGE 감사로
     * 충분. 호출자(inherit)와 동일 트랜잭션에서 동작(파일 쓰기 실패 시 전체 롤백).</p>
     *
     * @param sourceScriptId 복제할 원본 Script id
     * @return 새로 생성된 Script entity (file_path = 새 uuid.py)
     */
    @Transactional
    public Script cloneScript(Long sourceScriptId) {
        Script source = scriptRepository.findById(sourceScriptId)
                .orElseThrow(() -> new BusinessException("원본 스크립트가 존재하지 않습니다: id=" + sourceScriptId));

        // 원본 파일 내용 읽기 (부재 시 BusinessException → 트랜잭션 롤백)
        String content = readFile(source);
        validateContentSize(content);

        String uuid = UUID.randomUUID().toString();
        String filename = uuid + ".py";
        Path target = resolveTargetPath(filename);

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new BusinessException("스크립트 복제 파일 저장 실패: " + e.getMessage());
        }

        try {
            Script cloned = scriptRepository.save(Script.builder()
                    .filePath(filename)
                    .contentSize((long) content.getBytes(StandardCharsets.UTF_8).length)
                    .build());

            recordVersion(cloned.getId(), 1, content,
                    "프레임워크 복제 (원본 scriptId=" + sourceScriptId + ")");

            log.info("v19.19 스크립트 독립 복제: sourceId={} → newId={}, uuid={}",
                    sourceScriptId, cloned.getId(), uuid);
            return cloned;
        } catch (Exception e) {
            // entity 저장 실패 시 방금 만든 파일 정리 (create 패턴 정합)
            try { Files.deleteIfExists(target); } catch (IOException ignore) { }
            throw new BusinessException("스크립트 복제 entity 저장 실패: " + e.getMessage());
        }
    }

    public ScriptManagementDto.ScriptResponse getContent(Long id) {
        Script script = scriptRepository.findById(id)
                .orElseThrow(() -> new BusinessException("스크립트가 존재하지 않습니다: id=" + id));
        return toResponse(script, readFile(script));
    }

    @Auditable(action = AuditAction.SCRIPT_DELETE, targetType = "Script", targetId = "#a0")
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

    @Transactional
    public List<ScriptVersionDto.VersionResponse> listVersions(Long scriptId) {
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new BusinessException("스크립트가 존재하지 않습니다: id=" + scriptId));
        ensureSeedVersion(script);
        return scriptVersionRepository.findByScriptIdOrderByVersionNoAsc(scriptId).stream()
                .map(ScriptVersionDto.VersionResponse::from)
                .toList();
    }

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

    @Auditable(action = AuditAction.SCRIPT_ROLLBACK, targetType = "Script",
            targetId = "#a0", detail = "'v' + #a1")
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