package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptExecutionService {

    private final CollectionJobRepository collectionJobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final EvidenceFileRepository evidenceFileRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;

    @Value("${app.storage.path:./storage}")
    private String storagePath;

    @Value("${app.scripts.base-dir:./scripts}")
    private String scriptsBaseDir;

    @Value("${app.scripts.timeout-seconds:300}")
    private long timeoutSeconds;

    /**
     * 비동기 스크립트 실행 — @Async("jobExecutor") 로 별도 스레드에서 실행
     *
     * 1. 스크립트 경로 검증
     * 2. output 디렉토리 준비
     * 3. ProcessBuilder로 스크립트 실행
     * 4. stdout/stderr 캡처
     * 5. 종료 코드 확인 후 성공/실패 마킹
     * 6. 성공 시 output 디렉토리에서 생성된 파일을 EvidenceFile로 자동 등록
     */
    @Async("jobExecutor")
    @Transactional
    public void executeAsync(CollectionJob job, JobExecution execution) {
        try {
            executeScript(job, execution);
        } catch (Exception e) {
            log.error("스크립트 비동기 실행 중 예외 발생: {} (jobId={})", e.getMessage(), job.getId(), e);
            execution.markFailed("비동기 실행 예외: " + e.getMessage());
            jobExecutionRepository.save(execution);
        }
    }

    /**
     * 동기 스크립트 실행 — 스케줄러에서 직접 호출
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
        String scriptPath = job.getScriptPath();

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

        } catch (IOException e) {
            execution.markFailed("스크립트 시작 실패: " + e.getMessage());
            jobExecutionRepository.save(execution);
            log.error("ProcessBuilder 시작 실패: jobId={}", job.getId(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            execution.markFailed("스크립트 실행이 인터럽트되었습니다.");
            jobExecutionRepository.save(execution);
            log.error("스크립트 인터럽트: jobId={}", job.getId(), e);
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
            String cleanPath = scriptPath.startsWith("/") ? scriptPath.substring(1) : scriptPath;
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
     * output 디렉토리에서 생성된 파일을 감지하여 EvidenceFile로 등록
     *
     * 스크립트가 output 디렉토리에 파일을 생성하면,
     * 해당 파일을 storage/evidence/ 하위로 이동하고 DB에 등록합니다.
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
                    .collect(Collectors.toList());

            for (Path file : fileList) {
                try {
                    // 다음 버전 계산
                    int nextVersion = evidenceFileRepository
                            .findMaxVersionByEvidenceTypeId(evidenceType.getId())
                            .orElse(0) + 1;

                    // storage/evidence/{controlCode}/{evidenceTypeId}/ 로 복사
                    String controlCode = evidenceType.getControl() != null
                            ? evidenceType.getControl().getCode() : "unknown";
                    Path destDir = Paths.get(storagePath, "evidence", controlCode,
                            String.valueOf(evidenceType.getId()));
                    Files.createDirectories(destDir);

                    String destFileName = file.getFileName().toString();
                    Path destPath = destDir.resolve(destFileName);

                    // 같은 이름이 있으면 타임스탬프 추가
                    if (Files.exists(destPath)) {
                        String nameWithoutExt = getNameWithoutExtension(destFileName);
                        String ext = getExtension(destFileName);
                        destFileName = nameWithoutExt + "_" + System.currentTimeMillis() + ext;
                        destPath = destDir.resolve(destFileName);
                    }

                    Files.copy(file, destPath);
                    long fileSize = Files.size(destPath);

                    // DB 등록
                    EvidenceFile evidenceFile = EvidenceFile.builder()
                            .evidenceType(evidenceType)
                            .execution(execution)
                            .fileName(destFileName)
                            .filePath(destPath.toString())
                            .fileSize(fileSize)
                            .version(nextVersion)
                            .collectionMethod(CollectionMethod.auto)
                            .collectedAt(LocalDateTime.now())
                            .build();
                    evidenceFileRepository.save(evidenceFile);

                    count++;
                    log.info("증빙 파일 자동 수집: {} (v{}, jobId={})", destFileName, nextVersion, job.getId());
                } catch (IOException e) {
                    log.warn("output 파일 처리 실패: {} — {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("output 디렉토리 스캔 실패: {}", outputDir, e);
        }

        return count;
    }

    private String getNameWithoutExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
    }

    private String getExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx > 0 ? fileName.substring(dotIdx) : "";
    }
}
