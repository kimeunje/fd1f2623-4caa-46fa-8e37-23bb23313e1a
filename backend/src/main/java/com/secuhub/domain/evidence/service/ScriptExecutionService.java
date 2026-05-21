package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.CollectionJobRepository;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
import com.secuhub.domain.evidence.repository.JobExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ProcessBuilder 기반 스크립트 실행 서비스
 *
 * 수집 작업(CollectionJob)에 등록된 스크립트를 실제로 실행하고,
 * 실행 결과를 JobExecution에 기록합니다.
 *
 * 스크립트 실행 후 output 디렉토리에 생성된 파일을 감지하여
 * 자동으로 EvidenceFile로 등록합니다.
 *
 * 보안 고려사항:
 * - scriptPath 허용 디렉토리(scripts.base-dir) 외부 경로 차단
 * - 실행 타임아웃 적용 (기본 5분)
 * - 스크립트 존재 여부 및 실행 권한 사전 검증
 *
 * <h3>v18.5-platform-b — .ps1 Linux 차단</h3>
 * <p>{@link #buildCommand} 의 .ps1 분기에 {@code if (!isWindows) throw} 추가.
 * 운영 정책 = Rocky 에 PowerShell Core 부재, .sh/.py 통일.</p>
 *
 * <h3>v18.6a — collectOutputFiles asset 채널 전환 (Q7 자동 reuse)</h3>
 * <p>{@link #collectOutputFiles} 의 옛 {@code {storage}/evidence/{controlCode}/{etId}/}
 * 직접 저장 → asset 채널 ({@link EvidenceAssetService#findBySha256} +
 * {@link EvidenceAssetService#createNewAssetFromPath}). 자동 수집은 사용자 개입 0
 * 정책 = 같은 sha256 발견 시 자동 reuse (confirm 없음, L_HASH_DEDUP_UX 패턴 ④
 * 자동·수동 분리).</p>
 *
 * <h3>v18.7 — 자동 수집 실패 진단 (L_USER_NEEDS_REDIRECT 결과물)</h3>
 * <p>{@link #collectDiagnosis} 신규 메서드 — output 디렉토리의 {@code _diagnosis.json}
 * 자동 감지 + {@link JobExecution#setErrorDiagnosis} 갱신. collectOutputFiles 와 분리
 * 이유 = collectOutputFiles 는 성공 시 (exitCode==0) 만 호출되는데 진단은 실패 시점이
 * 본질. executeScript 의 모든 경로 (성공 / 실패 / 타임아웃 / 인터럽트) 에서 호출.</p>
 *
 * <p>스크린샷 / page_source 는 outputDir 안의 표준 파일명 (_diag_screenshot.png /
 * _diag_page_source.html) 으로 보관. JobExecutionController 의 endpoint 가
 * storagePath + jobId + executionId + 표준 파일명으로 도출하여 스트리밍.</p>
 *
 * <h3>v18.8.5 — async 영속성 컨텍스트 회귀 fix (v18.8.2 carry-over)</h3>
 * <p><b>회귀 원인</b>: v18.8.2 가 {@link CollectionJob#getScript()} (LAZY ManyToOne) 도입.
 * 옛 {@code executeAsync(CollectionJob, JobExecution)} 시그니처는 호출 측 (CollectionJobService)
 * 의 transaction 에서 attached 된 entity 를 async thread 로 전달 → async 의 새 transaction
 * 안에서 LAZY proxy 접근 시 "Session/EntityManager is closed" (원본 session 닫힘).</p>
 *
 * <p><b>정공 fix</b>: {@link #executeAsync(Long, Long)} 시그니처 변경 — ID 만 받고
 * async transaction 안에서 fresh fetch. 호출 측 (CollectionJobService.executeManually)
 * 도 ID 전달로 정합. {@link #executeSync(CollectionJob, JobExecution)} 는 그대로 유지
 * (스케줄러의 sync 컨텍스트는 같은 thread/session 이라 LAZY OK).</p>
 *
 * <p>회귀 보호: {@link com.secuhub.ScriptExecutionTest} 에 Script entity (UID) 시나리오
 * 케이스 추가 — {@code job.getScript()} 가 executeScript 안에서 정상 resolve 검증.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptExecutionService {

    private final CollectionJobRepository collectionJobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final EvidenceFileRepository evidenceFileRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;

    // v18.6a — Evidence Asset 신규 채널
    private final EvidenceAssetService evidenceAssetService;

    @Value("${app.storage.path:./storage}")
    private String storagePath;

    @Value("${app.scripts.base-dir:./scripts}")
    private String scriptsBaseDir;

    @Value("${app.scripts.timeout-seconds:300}")
    private long timeoutSeconds;

    /**
     * 비동기 스크립트 실행 — {@code @Async("jobExecutor")} 로 별도 스레드에서 실행.
     *
     * <h4>v18.8.5 시그니처 변경 (LAZY proxy 회귀 fix)</h4>
     * <p>옛: {@code executeAsync(CollectionJob job, JobExecution execution)} — 호출 측
     * transaction 의 attached entity 를 별도 스레드로 전달. async 안에서 LAZY 접근 시
     * "Session/EntityManager is closed".</p>
     * <p>새: ID 만 받음. async transaction 안에서 fresh fetch → 자기 session 소유 → LAZY 정상.</p>
     *
     * <h4>실행 흐름</h4>
     * <ol>
     *   <li>fresh fetch (CollectionJob + JobExecution)</li>
     *   <li>스크립트 경로 검증 / output 디렉토리 준비 / ProcessBuilder 실행 / stdout-stderr 캡처</li>
     *   <li>종료 코드 확인 후 성공/실패 마킹</li>
     *   <li>성공 시 output 디렉토리에서 생성된 파일을 EvidenceFile 로 자동 등록</li>
     *   <li>v18.7 — 성공/실패 무관하게 _diagnosis.json 자동 보관 (실패 시 본질)</li>
     * </ol>
     *
     * @param jobId       실행할 작업 id
     * @param executionId 사전 생성된 JobExecution id (status=running)
     */
    @Async("jobExecutor")
    @Transactional
    public void executeAsync(Long jobId, Long executionId) {
        try {
            CollectionJob job = collectionJobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("수집 작업", jobId));
            JobExecution execution = jobExecutionRepository.findById(executionId)
                    .orElseThrow(() -> new ResourceNotFoundException("작업 실행", executionId));
            executeScript(job, execution);
        } catch (Exception e) {
            log.error("스크립트 비동기 실행 중 예외 발생: jobId={}, executionId={}, error={}",
                    jobId, executionId, e.getMessage(), e);
            markExecutionFailed(executionId, "비동기 실행 예외: " + e.getMessage());
        }
    }

    /**
     * v18.8.5 — async 예외 시점 execution 실패 마킹 helper.
     *
     * <p>최초 catch 안에서 execution 이 detached 거나 fetch 자체가 실패했을 수 있으므로
     * 별도 fresh fetch 후 markFailed + save. 내부 예외는 log 만 (상위 흐름 방해 금지).</p>
     */
    private void markExecutionFailed(Long executionId, String errorMessage) {
        try {
            JobExecution execution = jobExecutionRepository.findById(executionId).orElse(null);
            if (execution != null) {
                execution.markFailed(errorMessage);
                jobExecutionRepository.save(execution);
            } else {
                log.warn("실패 마킹 대상 JobExecution 없음: executionId={}", executionId);
            }
        } catch (Exception inner) {
            log.error("실패 마킹 중 추가 예외 발생: executionId={}", executionId, inner);
        }
    }

    /**
     * 동기 스크립트 실행 — 스케줄러에서 직접 호출.
     *
     * <p>v18.8.5 — 시그니처 유지. 스케줄러는 같은 thread/session 에서 호출하므로
     * LAZY proxy 정상. executeAsync 와 달리 cross-thread session 문제 없음.</p>
     */
    @Transactional
    public void executeSync(CollectionJob job, JobExecution execution) {
        try {
            executeScript(job, execution);
        } catch (Exception e) {
            log.error("스크립트 동기 실행 중 예외 발생: {} (jobId={})", e.getMessage(), job.getId(), e);
            execution.markFailed("동기 실행 예외: " + e.getMessage());
            jobExecutionRepository.save(execution);
        }
    }

    /**
     * 스크립트 실행 본체
     */
    private void executeScript(CollectionJob job, JobExecution execution) {
        // v18.8.2 — script_id 우선, 없으면 script_path fallback (Q2=A)
        // v18.8.5 — async 컨텍스트에서도 정상 동작 (executeAsync 가 fresh fetch 후 호출)
        String scriptPath;
        Script linkedScript = job.getScript();
        if (linkedScript != null) {
            scriptPath = linkedScript.getFilePath();
        } else {
            scriptPath = job.getScriptPath();
        }

        // ── 1. 스크립트 경로 검증 ──
        if (scriptPath == null || scriptPath.isBlank()) {
            execution.markFailed("스크립트 경로가 설정되지 않았습니다.");
            jobExecutionRepository.save(execution);
            return;
        }

        Path script = resolveScriptPath(scriptPath);
        if (!Files.exists(script)) {
            execution.markFailed("스크립트 파일이 존재하지 않습니다: " + script);
            jobExecutionRepository.save(execution);
            log.warn("스크립트 파일 없음: {}", script);
            return;
        }

        if (!Files.isReadable(script)) {
            execution.markFailed("스크립트 파일을 읽을 수 없습니다: " + script);
            jobExecutionRepository.save(execution);
            return;
        }

        // ── 2. output 디렉토리 준비 ──
        Path outputDir = prepareOutputDir(job, execution);

        // ── 3. 실행 명령어 조립 ──
        List<String> command = buildCommand(script, outputDir);
        log.info("스크립트 실행 시작: {} (jobId={}, executionId={})", command, job.getId(), execution.getId());

        // ── 4. ProcessBuilder 실행 ──
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(script.getParent().toFile());
        pb.redirectErrorStream(false); // stdout/stderr 분리 캡처

        // 환경변수 설정
        pb.environment().put("SECUHUB_OUTPUT_DIR", outputDir.toString());
        pb.environment().put("SECUHUB_JOB_ID", String.valueOf(job.getId()));
        pb.environment().put("SECUHUB_EXECUTION_ID", String.valueOf(execution.getId()));

        Process process = null;
        try {
            process = pb.start();

            // stdout/stderr 캡처 (별도 스레드로 읽어야 deadlock 방지)
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutReader = startStreamReader(process, stdout, false);
            Thread stderrReader = startStreamReader(process, stderr, true);

            // 타임아웃 대기
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                execution.markFailed("스크립트 실행 시간 초과 (" + timeoutSeconds + "초)");
                jobExecutionRepository.save(execution);
                log.error("스크립트 타임아웃: jobId={}", job.getId());
                // v18.7 — 타임아웃 시점에도 wrapper 가 _diagnosis.json 을 미리 썼을 수 있음
                collectDiagnosis(execution, outputDir);
                return;
            }

            // 스트림 리더 완료 대기
            stdoutReader.join(5000);
            stderrReader.join(5000);

            int exitCode = process.exitValue();
            log.info("스크립트 종료: exitCode={}, jobId={}", exitCode, job.getId());

            if (exitCode == 0) {
                // ── 5. 성공 처리 ──
                execution.markSuccess();
                jobExecutionRepository.save(execution);

                // ── 6. output 디렉토리에서 파일 수집 ──
                log.debug("output 디렉토리 확인: {} (exists={}, isDir={})",
                        outputDir, Files.exists(outputDir), Files.isDirectory(outputDir));
                try (var listing = Files.list(outputDir)) {
                    listing.forEach(f -> log.debug("  output 파일 발견: {} (size={})",
                            f.getFileName(), f.toFile().length()));
                } catch (IOException ignored) {}

                int collectedFiles = collectOutputFiles(job, execution, outputDir);
                log.info("스크립트 실행 성공: jobId={}, 수집 파일 {}건", job.getId(), collectedFiles);
            } else {
                // 실패: stderr 또는 stdout 앞부분을 에러 메시지로 저장
                String errorMsg = buildErrorMessage(exitCode, stdout, stderr);
                execution.markFailed(errorMsg);
                jobExecutionRepository.save(execution);
                log.error("스크립트 실행 실패: exitCode={}, jobId={}, error={}", exitCode, job.getId(), errorMsg);
            }

            // v18.7 — 성공/실패 무관하게 진단 JSON 보관 (성공도 단계별 시간 기록 가능)
            collectDiagnosis(execution, outputDir);

        } catch (IOException e) {
            execution.markFailed("스크립트 시작 실패: " + e.getMessage());
            jobExecutionRepository.save(execution);
            log.error("ProcessBuilder 시작 실패: jobId={}", job.getId(), e);
            // v18.7 — 시작 실패 시점에도 outputDir 가 prepared 됐으므로 진단 시도
            collectDiagnosis(execution, outputDir);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            execution.markFailed("스크립트 실행이 인터럽트되었습니다.");
            jobExecutionRepository.save(execution);
            log.error("스크립트 인터럽트: jobId={}", job.getId(), e);
            collectDiagnosis(execution, outputDir);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    // ========================================
    // 내부 유틸 메서드
    // ========================================

    /**
     * 스크립트 경로를 base-dir 기준으로 안전하게 resolve
     * 절대 경로가 들어오면 base-dir 하위인지 검증, 상대 경로면 base-dir 기준 resolve
     */
    private Path resolveScriptPath(String scriptPath) {
        Path basePath = Paths.get(scriptsBaseDir).toAbsolutePath().normalize();
        Path resolved;

        if (Paths.get(scriptPath).isAbsolute()) {
            resolved = Paths.get(scriptPath).normalize();
        } else {
            // /scripts/xxx.py 형태일 경우 앞의 / 제거 후 base-dir 기준 resolve
            String cleanPath = scriptPath.startsWith("/") ?
                    scriptPath.substring(1) : scriptPath;
            resolved = basePath.resolve(cleanPath).normalize();
        }

        // 보안: base-dir 밖으로 나가는 경로 차단
        if (!resolved.startsWith(basePath)) {
            log.warn("스크립트 경로 보안 위반: {} (base={})", resolved, basePath);
            throw new BusinessException("허용되지 않은 스크립트 경로입니다: " + scriptPath);
        }

        return resolved;
    }

    /**
     * 실행별 output 디렉토리 생성
     * {storagePath}/output/{jobId}/{executionId}/
     */
    private Path prepareOutputDir(CollectionJob job, JobExecution execution) {
        Path outputDir = Paths.get(storagePath, "output",
                String.valueOf(job.getId()),
                String.valueOf(execution.getId()))
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new BusinessException("output 디렉토리 생성 실패: " + e.getMessage());
        }
        return outputDir;
    }

    /**
     * 스크립트 확장자에 따른 실행 명령어 조립 (Windows/Linux 크로스 플랫폼)
     */
    private List<String> buildCommand(Path script, Path outputDir) {
        String fileName = script.getFileName().toString().toLowerCase();
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> command = new ArrayList<>();

        if (fileName.endsWith(".py")) {
            command.add(isWindows ? "python" : "python3");
            command.add(script.toString());
        } else if (fileName.endsWith(".sh")) {
            if (isWindows) {
                // Windows: Git Bash 또는 WSL의 bash 탐색
                String bash = findWindowsBash();
                command.add(bash);
                command.add(script.toString());
            } else {
                command.add("/bin/bash");
                command.add(script.toString());
            }
        } else if (fileName.endsWith(".bat") || fileName.endsWith(".cmd")) {
            command.add("cmd.exe");
            command.add("/c");
            command.add(script.toString());
        } else if (fileName.endsWith(".ps1")) {
            // v18.5-platform-b: .ps1 은 Windows 환경에서만 지원.
            // Rocky 운영 환경에는 PowerShell Core (pwsh) 가 기본 설치되어 있지 않고,
            // 운영 정책상 자동 수집 스크립트는 .sh / .py 로 통일.
            if (!isWindows) {
                throw new BusinessException(
                    ".ps1 스크립트는 Windows 환경에서만 지원됩니다. " +
                    "Linux 운영 환경에서는 .sh 또는 .py 스크립트로 변환해주세요.");
            }
            command.add("powershell");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(script.toString());
        } else {
            // 기본: 직접 실행
            if (isWindows) {
                command.add("cmd.exe");
                command.add("/c");
            }
            command.add(script.toString());
        }

        // output 디렉토리를 인자로 전달
        command.add(outputDir.toString());

        return command;
    }

    /**
     * Windows에서 bash 실행 경로 탐색 (Git Bash → WSL → 실패)
     */
    private String findWindowsBash() {
        // Git Bash (가장 흔한 경로)
        String[] candidates = {
            "C:\\Program Files\\Git\\bin\\bash.exe",
            "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
            System.getenv("PROGRAMFILES") + "\\Git\\bin\\bash.exe",
        };
        for (String path : candidates) {
            if (path != null && new java.io.File(path).exists()) {
                return path;
            }
        }
        // WSL bash
        String wslBash = "C:\\Windows\\System32\\bash.exe";
        if (new java.io.File(wslBash).exists()) {
            return wslBash;
        }
        // 마지막 시도: PATH에서 bash 탐색
        return "bash";
    }

    /**
     * Process의 InputStream을 별도 스레드에서 읽어 StringBuilder에 저장
     */
    private Thread startStreamReader(Process process, StringBuilder sb, boolean isError) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            isError ? process.getErrorStream() : process.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } catch (IOException e) {
                log.debug("스트림 읽기 종료: {}", isError ? "stderr" : "stdout");
            }
        }, "script-" + (isError ? "stderr" : "stdout") + "-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 에러 메시지 조립 (최대 2000자 제한)
     */
    private String buildErrorMessage(int exitCode, StringBuilder stdout, StringBuilder stderr) {
        String error = stderr.length() > 0 ? stderr.toString() : stdout.toString();
        String prefix = "Exit code: " + exitCode + "\n";
        int maxLen = 2000 - prefix.length();
        if (error.length() > maxLen) {
            error = error.substring(0, maxLen) + "... (truncated)";
        }
        return prefix + error;
    }

    /**
     * output 디렉토리에서 생성된 파일을 감지하여 EvidenceFile로 등록.
     *
     * <h3>v18.6a — Asset 채널 전환 (Q7 자동 reuse, confirm 없음)</h3>
     * <p>운영 정책: 자동 수집은 사용자 개입 0 — 같은 sha256 발견 시 confirm dialog 없이
     * 자동 reuse (FE 수동 업로드와 분리, L_HASH_DEDUP_UX 패턴 ④ 자동·수동 분리).</p>
     *
     * <p>흐름:</p>
     * <ol>
     *   <li>output 디렉토리의 파일 enumerate</li>
     *   <li>각 파일 SHA-256 계산</li>
     *   <li>기존 asset 조회 — 있으면 reuse + output 파일 정리, 없으면 신규 asset 생성
     *       ({@link EvidenceAssetService#createNewAssetFromPath} = move)</li>
     *   <li>EvidenceFile (link) 생성 + asset 매핑</li>
     * </ol>
     *
     * <p><b>v18.7 보강</b>: {@code _diagnosis.json} 과 {@code _diag_*} 접두 파일은
     * EvidenceFile 로 수집하지 않음 (진단 자산은 별도 책임 — collectDiagnosis 가 처리).</p>
     */
    private int collectOutputFiles(CollectionJob job, JobExecution execution, Path outputDir) {
        if (!Files.exists(outputDir) || !Files.isDirectory(outputDir)) {
            log.debug("output 디렉토리 없음 또는 비정상: {}", outputDir);
            return 0;
        }

        // Job을 DB에서 다시 조회하여 Lazy proxy 문제 완전 방지
        CollectionJob freshJob = collectionJobRepository.findById(job.getId()).orElse(null);
        if (freshJob == null || freshJob.getEvidenceType() == null) {
            log.info("증빙 유형 미연결 — output 파일 수집 스킵 (jobId={})", job.getId());
            return 0;
        }

        Long evidenceTypeId = freshJob.getEvidenceType().getId();
        EvidenceType evidenceType = evidenceTypeRepository.findById(evidenceTypeId).orElse(null);
        if (evidenceType == null) {
            log.info("증빙 유형 DB 조회 실패 — output 파일 수집 스킵 (evidenceTypeId={})", evidenceTypeId);
            return 0;
        }

        log.debug("output 디렉토리 스캔 시작: {} (evidenceTypeId={})", outputDir, evidenceType.getId());

        int count = 0;
        try (Stream<Path> files = Files.list(outputDir)) {
            List<Path> fileList = files
                    .filter(Files::isRegularFile)
                    // v18.7 — 진단 자산 (_diagnosis.json / _diag_*) 은 EvidenceFile 로 수집 안 함
                    .filter(p -> !isDiagnosisArtifact(p.getFileName().toString()))
                    .collect(Collectors.toList());

            for (Path file : fileList) {
                try {
                    String originalFileName = file.getFileName().toString();

                    // v18.6a — 자동 수집은 Q7 (자동 reuse, confirm 없음)
                    // 1. SHA-256 계산
                    String sha256 = evidenceAssetService.computeSha256(file);

                    // 2. 기존 asset 조회 — 있으면 reuse, 없으면 신규 생성
                    Optional<EvidenceAsset> existing = evidenceAssetService.findBySha256(sha256);
                    EvidenceAsset asset;
                    if (existing.isPresent()) {
                        asset = existing.get();
                        // reuse — outputDir 의 파일 정리 (이미 asset 에 있으므로 불필요)
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException ex) {
                            log.debug("reuse 후 output 파일 정리 실패: {}", file, ex);
                        }
                        log.info("증빙 파일 자동 수집 (asset reuse): assetId={}, sha256={}, jobId={}",
                                asset.getId(), sha256, job.getId());
                    } else {
                        // 신규 asset 생성 — createNewAssetFromPath 가 file 을 move (복사 아님)
                        asset = evidenceAssetService.createNewAssetFromPath(
                                file, originalFileName, sha256, null);
                    }

                    // 3. EvidenceFile (link) 생성
                    int nextVersion = evidenceFileRepository
                            .findMaxVersionByEvidenceTypeId(evidenceType.getId())
                            .orElse(0) + 1;

                    EvidenceFile evidenceFile = EvidenceFile.builder()
                            .evidenceType(evidenceType)
                            .execution(execution)
                            .asset(asset)
                            .fileName(originalFileName)
                            .filePath(asset.getFilePath())    // transitional
                            .fileSize(asset.getFileSize())
                            .version(nextVersion)
                            .collectionMethod(CollectionMethod.auto)
                            .collectedAt(LocalDateTime.now())
                            .build();
                    evidenceFileRepository.save(evidenceFile);

                    count++;
                    log.info("증빙 파일 자동 수집 link: {} (v{}, jobId={}, assetId={})",
                            originalFileName, nextVersion, job.getId(), asset.getId());
                } catch (Exception e) {
                    log.warn("output 파일 처리 실패: {} — {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("output 디렉토리 스캔 실패: {}", outputDir, e);
        }

        return count;
    }

    // ========================================
    // v18.7 — 자동 수집 실패 진단 (L_USER_NEEDS_REDIRECT 결과물)
    // ========================================

    /**
     * v18.7 — output 디렉토리의 _diagnosis.json 자동 감지 + JobExecution.errorDiagnosis 보관.
     *
     * <p>호출 위치 (executeScript 의 모든 종료 경로):</p>
     * <ul>
     *   <li>exitCode == 0 (성공) — 성공도 단계별 시간 기록 가능</li>
     *   <li>exitCode != 0 (실패) — 실패 본질, wrapper 가 진단 JSON 생성</li>
     *   <li>timeout — wrapper 가 finally 안에서 진단 JSON 미리 쓸 수 있음</li>
     *   <li>IOException / InterruptedException — outputDir 가 prepared 됐다면 시도</li>
     * </ul>
     *
     * <p>graceful degradation — 진단 정보 누락이 본 흐름을 차단하지 않음. _diag_screenshot.png
     * 와 _diag_page_source.html 은 outputDir 안에 그대로 보존됨 (JobExecutionController 의
     * endpoint 가 storagePath + jobId + executionId + 표준 파일명으로 도출).</p>
     *
     * <p>L_RESPONSIBILITY_SEPARATION — EvidenceAsset / EvidenceFile 시스템 미관여.
     * 진단 정보는 JobExecution 의 책임 (실행 수명과 동일).</p>
     */
    private void collectDiagnosis(JobExecution execution, Path outputDir) {
        if (outputDir == null || !Files.exists(outputDir)) {
            return;
        }
        Path diagnosisFile = outputDir.resolve("_diagnosis.json");
        if (!Files.exists(diagnosisFile)) {
            return;
        }
        try {
            String diagnosisJson = Files.readString(diagnosisFile, StandardCharsets.UTF_8);
            execution.setErrorDiagnosis(diagnosisJson);
            jobExecutionRepository.save(execution);
            log.info("v18.7 진단 JSON 보관 — executionId={}, bytes={}",
                    execution.getId(), diagnosisJson.length());
        } catch (IOException e) {
            log.warn("v18.7 진단 JSON 읽기 실패. executionId={}, error={}",
                    execution.getId(), e.getMessage());
            // 진단 누락이 본 흐름을 차단하지 않음 (graceful degradation)
        }
    }

    /**
     * v18.7 — 진단 자산 파일명 패턴 검출. EvidenceFile 자동 수집 대상에서 제외.
     *
     * <p>패턴:</p>
     * <ul>
     *   <li>{@code _diagnosis.json} — 진단 JSON</li>
     *   <li>{@code _diag_*} — 진단 부속 파일 (스크린샷, page_source, 향후 추가 가능)</li>
     * </ul>
     */
    private boolean isDiagnosisArtifact(String fileName) {
        return "_diagnosis.json".equals(fileName) || fileName.startsWith("_diag_");
    }

    // ========================================
    // 옛 helper (v18.6a 후 호출처 0, v18.6b 후 제거 예정)
    // ========================================

    /**
     * @deprecated v18.6a 의 asset 채널 전환으로 호출처 0. v18.6b 마이그레이션 후 제거 예정.
     */
    @Deprecated
    private String getNameWithoutExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
    }

    /**
     * @deprecated v18.6a 의 asset 채널 전환으로 호출처 0. v18.6b 마이그레이션 후 제거 예정.
     */
    @Deprecated
    private String getExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx > 0 ? fileName.substring(dotIdx) : "";
    }
}