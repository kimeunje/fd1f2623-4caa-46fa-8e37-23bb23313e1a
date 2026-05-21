package com.secuhub;

import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.evidence.service.EvidenceFileService;
import com.secuhub.domain.evidence.service.ScriptExecutionService;
import com.secuhub.domain.evidence.service.SchedulerService;
import com.secuhub.domain.evidence.dto.EvidenceFileDto;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 2 후속 — 스크립트 실행 + 다운로드 API 테스트")
class ScriptExecutionTest {

    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;   // v14 Phase 5-14f
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;
    @Autowired private CollectionJobRepository collectionJobRepository;
    @Autowired private JobExecutionRepository jobExecutionRepository;
    @Autowired private ScriptRepository scriptRepository;             // v18.8.5 — Script entity (UID) 검증용
    @Autowired private ScriptExecutionService scriptExecutionService;
    @Autowired private EvidenceFileService evidenceFileService;
    @Autowired private SchedulerService schedulerService;             // v18.8.6 — 좀비 정리 검증용

    @Value("${app.storage.path:./storage}")
    private String storagePath;

    @Value("${app.scripts.base-dir:./scripts}")
    private String scriptsBaseDir;

    @Value("${app.scripts.timeout-seconds:300}")
    private long scriptTimeoutSeconds;

    /** Windows 환경 여부 */
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** OS에 맞는 스크립트 확장자 */
    private static String scriptExt() {
        return IS_WINDOWS ? ".bat" : ".sh";
    }

    // ========================================
    // 1. 스크립트 경로 미설정 시 실패 처리
    // ========================================
    @Test
    @Order(1)
    @DisplayName("[Script] 스크립트 경로 미설정 시 실패 마킹")
    @Transactional
    void testExecuteWithNoScriptPath() {
        Framework fw = frameworkRepository.save(Framework.builder().name("테스트 FW").build());
        ControlNode ctrl = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("SC-01").name("스크립트 테스트 항목")
                .displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl).name("스크립트 증빙").build());

        CollectionJob job = collectionJobRepository.save(CollectionJob.builder()
                .name("경로 미설정 작업")
                .jobType(JobType.log_extract)
                .scriptPath(null)
                .evidenceType(et)
                .build());

        JobExecution execution = jobExecutionRepository.save(JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build());

        scriptExecutionService.executeSync(job, execution);

        JobExecution saved = jobExecutionRepository.findById(execution.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.failed);
        assertThat(saved.getErrorMessage()).contains("스크립트 경로가 설정되지 않았습니다");
        assertThat(saved.getFinishedAt()).isNotNull();

        System.out.println("✅ [Script] 스크립트 경로 미설정 시 실패 마킹 정상");
    }

    // ========================================
    // 2. 존재하지 않는 스크립트 파일 시 실패 처리
    // ========================================
    @Test
    @Order(2)
    @DisplayName("[Script] 존재하지 않는 스크립트 파일 시 실패 마킹")
    @Transactional
    void testExecuteWithNonExistentScript() {
        Framework fw = frameworkRepository.save(Framework.builder().name("테스트 FW").build());
        ControlNode ctrl = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("SC-02").name("스크립트 테스트 항목2")
                .displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl).name("스크립트 증빙2").build());

        CollectionJob job = collectionJobRepository.save(CollectionJob.builder()
                .name("없는 스크립트 작업")
                .jobType(JobType.web_scraping)
                .scriptPath("nonexistent_script" + scriptExt())
                .evidenceType(et)
                .build());

        JobExecution execution = jobExecutionRepository.save(JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build());

        scriptExecutionService.executeSync(job, execution);

        JobExecution saved = jobExecutionRepository.findById(execution.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.failed);
        assertThat(saved.getErrorMessage()).contains("존재하지 않습니다");

        System.out.println("✅ [Script] 존재하지 않는 스크립트 파일 시 실패 마킹 정상");
    }

    // ========================================
    // 3. 성공 스크립트 실행 + 파일 자동 수집
    // ========================================
    @Test
    @Order(3)
    @DisplayName("[Script] 성공 스크립트 실행 + 파일 자동 수집")
    @Transactional
    void testExecuteSuccessScript() throws IOException {
        Path scriptDir = Paths.get(scriptsBaseDir).toAbsolutePath().normalize();
        Files.createDirectories(scriptDir);

        Path testScript;
        if (IS_WINDOWS) {
            testScript = scriptDir.resolve("test_collect.bat");
            Files.writeString(testScript,
                    "@echo off\r\n" +
                    "set \"OUTPUT_DIR=%~1\"\r\n" +
                    "if \"%OUTPUT_DIR%\"==\"\" set \"OUTPUT_DIR=%SECUHUB_OUTPUT_DIR%\"\r\n" +
                    "echo === Server Access Log === > \"%OUTPUT_DIR%\\collect_result.txt\"\r\n" +
                    "echo web-server-01  access: 1234  blocked: 12 >> \"%OUTPUT_DIR%\\collect_result.txt\"\r\n" +
                    "echo web-server-02  access: 987   blocked: 3 >> \"%OUTPUT_DIR%\\collect_result.txt\"\r\n" +
                    "exit /b 0\r\n");
        } else {
            testScript = scriptDir.resolve("test_collect.sh");
            Files.writeString(testScript,
                    "#!/bin/bash\n" +
                    "OUTPUT_DIR=\"$1\"\n" +
                    "echo \"=== Server Access Log ===\" > \"$OUTPUT_DIR/collect_result.txt\"\n" +
                    "echo \"web-server-01  access: 1234  blocked: 12\" >> \"$OUTPUT_DIR/collect_result.txt\"\n" +
                    "echo \"web-server-02  access: 987   blocked: 3\" >> \"$OUTPUT_DIR/collect_result.txt\"\n" +
                    "exit 0\n");
            testScript.toFile().setExecutable(true);
        }

        Framework fw = frameworkRepository.save(Framework.builder().name("테스트 FW").build());
        ControlNode ctrl = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("SC-03").name("성공 스크립트 항목")
                .displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl).name("서버 점검 결과").build());

        CollectionJob job = collectionJobRepository.save(CollectionJob.builder()
                .name("서버 점검 수집")
                .jobType(JobType.log_extract)
                .scriptPath(testScript.getFileName().toString())
                .evidenceType(et)
                .build());

        JobExecution execution = jobExecutionRepository.save(JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build());

        scriptExecutionService.executeSync(job, execution);

        // 디버그: 실행 결과 확인
        JobExecution saved = jobExecutionRepository.findById(execution.getId()).orElseThrow();
        System.out.println("   실행 상태: " + saved.getStatus());
        if (saved.getErrorMessage() != null) {
            System.out.println("   에러: " + saved.getErrorMessage());
        }

        // 디버그: output 디렉토리 확인
        Path outputBase = Paths.get(storagePath, "output", String.valueOf(job.getId()))
                .toAbsolutePath().normalize();
        if (Files.exists(outputBase)) {
            try (var walk = Files.walk(outputBase, 3)) {
                walk.forEach(p -> System.out.println("   output: " + p + " (size=" + p.toFile().length() + ")"));
            }
        } else {
            System.out.println("   output 디렉토리 없음: " + outputBase);
        }

        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.success);
        assertThat(saved.getFinishedAt()).isNotNull();

        evidenceFileRepository.flush();
        List<EvidenceFile> files = evidenceFileRepository.findByEvidenceTypeIdOrderByVersionDesc(et.getId());

        // 전체 evidence_files 테이블도 확인
        List<EvidenceFile> allFiles = evidenceFileRepository.findAll();
        System.out.println("   DB 전체 파일 수: " + allFiles.size());
        allFiles.forEach(f -> System.out.println("     -> id=" + f.getId()
                + " etId=" + f.getEvidenceType().getId()
                + " method=" + f.getCollectionMethod()
                + " name=" + f.getFileName()
                + " assetId=" + (f.getAsset() != null ? f.getAsset().getId() : "null")));
        System.out.println("   ET " + et.getId() + " 파일 수: " + files.size());

        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFileName()).contains("collect_result");
        assertThat(files.get(0).getCollectionMethod()).isEqualTo(CollectionMethod.auto);
        assertThat(files.get(0).getVersion()).isEqualTo(1);
        assertThat(files.get(0).getExecution()).isNotNull();

        // v18.6a — Asset 채널 검증 (Q7 자동 reuse)
        assertThat(files.get(0).getAsset()).isNotNull();
        assertThat(files.get(0).getAsset().getSha256()).hasSize(64);
        assertThat(files.get(0).getAsset().getFilePath()).contains("assets");
        assertThat(files.get(0).getAsset().getFilePath())
                .contains(String.valueOf(files.get(0).getAsset().getId()));

        System.out.println("✅ [Script] 성공 스크립트 실행 + asset 자동 수집 정상");
        System.out.println("   수집 파일: " + files.get(0).getFileName()
                + " (v" + files.get(0).getVersion()
                + ", assetId=" + files.get(0).getAsset().getId() + ")");
        System.out.println("   수집 파일: " + files.get(0).getFileName() + " (v" + files.get(0).getVersion() + ")");

        Files.deleteIfExists(testScript);
    }

    // ========================================
    // 4. 실패 스크립트 실행 시 에러 메시지 기록
    // ========================================
    @Test
    @Order(4)
    @DisplayName("[Script] 실패 스크립트 실행 시 에러 메시지 기록")
    @Transactional
    void testExecuteFailingScript() throws IOException {
        Path scriptDir = Paths.get(scriptsBaseDir).toAbsolutePath().normalize();
        Files.createDirectories(scriptDir);

        Path testScript;
        if (IS_WINDOWS) {
            testScript = scriptDir.resolve("test_fail.bat");
            Files.writeString(testScript,
                    "@echo off\r\n" +
                    "echo ERROR: Target server 192.168.1.100 connection refused 1>&2\r\n" +
                    "exit /b 1\r\n");
        } else {
            testScript = scriptDir.resolve("test_fail.sh");
            Files.writeString(testScript,
                    "#!/bin/bash\n" +
                    "echo \"ERROR: Target server 192.168.1.100 connection refused\" >&2\n" +
                    "exit 1\n");
            testScript.toFile().setExecutable(true);
        }

        Framework fw = frameworkRepository.save(Framework.builder().name("테스트 FW").build());
        ControlNode ctrl = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("SC-04").name("실패 스크립트 항목")
                .displayOrder(0).depth(1).build());

        CollectionJob job = collectionJobRepository.save(CollectionJob.builder()
                .name("실패 테스트 작업")
                .jobType(JobType.log_extract)
                .scriptPath(testScript.getFileName().toString())
                .build());

        JobExecution execution = jobExecutionRepository.save(JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build());

        scriptExecutionService.executeSync(job, execution);

        JobExecution saved = jobExecutionRepository.findById(execution.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.failed);
        assertThat(saved.getErrorMessage()).contains("Exit code: 1");

        System.out.println("✅ [Script] 실패 스크립트 에러 메시지 기록 정상");
        System.out.println("   에러: " + saved.getErrorMessage().substring(0, Math.min(80, saved.getErrorMessage().length())));

        Files.deleteIfExists(testScript);
    }

    // ========================================
    // 5. 파일 다운로드 — 정상 케이스
    // ========================================
    @Test
    @Order(5)
    @DisplayName("[Download] 증빙 파일 다운로드 정상 동작")
    @Transactional
    void testFileDownload() throws IOException {
        Path fileDir = Paths.get(storagePath, "evidence", "DL-01", "1");
        Files.createDirectories(fileDir);
        Path testFile = fileDir.resolve("test_download_policy.pdf");
        Files.writeString(testFile, "테스트 PDF 내용입니다.");

        Framework fw = frameworkRepository.save(Framework.builder().name("다운로드 FW").build());
        ControlNode ctrl = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("DL-01").name("다운로드 테스트 항목")
                .displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl).name("다운로드 증빙").build());

        EvidenceFile dbFile = evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName("정보보호_정책서_v1.pdf")
                .filePath(testFile.toString())
                .fileSize(Files.size(testFile))
                .version(1)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .build());

        EvidenceFileDto.DownloadResponse download = evidenceFileService.download(dbFile.getId());

        assertThat(download).isNotNull();
        assertThat(download.getResource()).isNotNull();
        assertThat(download.getResource().exists()).isTrue();
        assertThat(download.getFileName()).isEqualTo("정보보호_정책서_v1.pdf");
        assertThat(download.getContentType()).isEqualTo("application/pdf");
        assertThat(download.getFileSize()).isGreaterThan(0);

        System.out.println("✅ [Download] 증빙 파일 다운로드 정상 동작");

        Files.deleteIfExists(testFile);
    }

    // ========================================
    // 6. 파일 다운로드 — 존재하지 않는 파일 ID
    // ========================================
    @Test
    @Order(6)
    @DisplayName("[Download] 존재하지 않는 파일 ID 시 예외")
    @Transactional
    void testFileDownloadNotFound() {
        assertThatThrownBy(() -> evidenceFileService.download(99999L))
                .hasMessageContaining("증빙 파일");

        System.out.println("✅ [Download] 존재하지 않는 파일 ID 시 ResourceNotFoundException 정상");
    }

    // ========================================
    // 7. 파일 다운로드 — 물리 파일 없음 시 예외
    // ========================================
    @Test
    @Order(7)
    @DisplayName("[Download] DB는 있으나 물리 파일 없을 시 BusinessException")
    @Transactional
    void testFileDownloadPhysicalFileMissing() {
        Framework fw = frameworkRepository.save(Framework.builder().name("미싱 FW").build());
        ControlNode ctrl = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("DL-02").name("미싱 테스트 항목")
                .displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl).name("미싱 증빙").build());

        EvidenceFile dbFile = evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName("삭제된_파일.pdf")
                .filePath("C:\\nonexistent\\path\\deleted_file.pdf")
                .fileSize(1000L)
                .version(1)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .build());

        assertThatThrownBy(() -> evidenceFileService.download(dbFile.getId()))
                .hasMessageContaining("저장소에 존재하지 않습니다");

        System.out.println("✅ [Download] DB는 있으나 물리 파일 없을 시 BusinessException 정상");
    }

    // ========================================
    // 8. 파일 다운로드 — Content-Type 추정
    // ========================================
    @Test
    @Order(8)
    @DisplayName("[Download] 다양한 확장자별 Content-Type 추정")
    @Transactional
    void testContentTypeDetermination() throws IOException {
        Framework fw = frameworkRepository.save(Framework.builder().name("CT 테스트").build());
        ControlNode ctrl = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("CT-01").name("CT 항목")
                .displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl).name("CT 증빙").build());

        Path fileDir = Paths.get(storagePath, "evidence", "CT-01", "test");
        Files.createDirectories(fileDir);
        Path xlsxFile = fileDir.resolve("report.xlsx");
        Files.writeString(xlsxFile, "fake xlsx content");

        EvidenceFile dbFile = evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName("접근권한_현황.xlsx")
                .filePath(xlsxFile.toString())
                .fileSize(Files.size(xlsxFile))
                .version(1)
                .collectionMethod(CollectionMethod.auto)
                .collectedAt(LocalDateTime.now())
                .build());

        EvidenceFileDto.DownloadResponse download = evidenceFileService.download(dbFile.getId());
        assertThat(download.getContentType()).contains("spreadsheetml");

        System.out.println("✅ [Download] xlsx Content-Type 추정 정상: " + download.getContentType());

        Files.deleteIfExists(xlsxFile);
    }

    // ========================================
    // 9. 회귀 보호 — git checkout 된 .sh 파일은 LF only 여야 함
    //    (v18.5-platform-b — .gitattributes 의 "*.sh text eol=lf" 규칙 위반 사전 차단)
    // ========================================
    @Test
    @Order(9)
    @DisplayName("[LineEnding] git checkout 된 .sh 파일은 LF only — CRLF 발견 시 fail")
    void testGitCheckedInScriptHasLfLineEnding() throws IOException {
        Path scriptDir = Paths.get(scriptsBaseDir).toAbsolutePath().normalize();

        if (!Files.exists(scriptDir)) {
            System.out.println("⚠ scripts 디렉토리 없음 — skip: " + scriptDir);
            return;
        }

        List<Path> shFiles;
        try (Stream<Path> walk = Files.walk(scriptDir).filter(p -> p.toString().endsWith(".sh"))) {
            shFiles = walk.toList();
        }

        if (shFiles.isEmpty()) {
            System.out.println("⚠ .sh 파일 없음 — skip");
            return;
        }

        for (Path sh : shFiles) {
            byte[] bytes = Files.readAllBytes(sh);
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == 0x0D) {  // CR (\r)
                    fail(".sh 파일에 CRLF 발견: " + sh + " (offset=" + i + "). " +
                         ".gitattributes 의 '*.sh text eol=lf' 규칙 위반 — " +
                         "`git add --renormalize .` 실행 후 commit 필요");
                }
            }
        }

        System.out.println("✅ [LineEnding] " + shFiles.size() + "개 .sh 파일 모두 LF only 확인");
    }

    // ========================================
    // 10. 회귀 보호 — Linux 환경에서 .ps1 등록 시 BusinessException
    //     (v18.5-platform-b — .ps1 은 Windows 한정 운영 정책)
    // ========================================
    @Test
    @Order(10)
    @DisplayName("[Script] Linux 환경에서 .ps1 등록 시 BusinessException — Windows 에서는 skip")
    @EnabledOnOs(OS.LINUX)
    @Transactional
    void testPs1ScriptRejectedOnLinux() throws IOException {
        Path scriptDir = Paths.get(scriptsBaseDir).toAbsolutePath().normalize();
        Files.createDirectories(scriptDir);

        Path ps1Script = scriptDir.resolve("test_reject.ps1");
        Files.writeString(ps1Script,
                "Write-Host 'this should never execute on Linux'\n");

        Framework fw = frameworkRepository.save(Framework.builder().name("PS1 거부 FW").build());
        ControlNode ctrl = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("PS-01").name(".ps1 거부 항목")
                .displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl).name(".ps1 거부 증빙").build());

        CollectionJob job = collectionJobRepository.save(CollectionJob.builder()
                .name(".ps1 거부 테스트")
                .jobType(JobType.log_extract)
                .scriptPath(ps1Script.getFileName().toString())
                .evidenceType(et)
                .build());

        JobExecution execution = jobExecutionRepository.save(JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build());

        scriptExecutionService.executeSync(job, execution);

        JobExecution saved = jobExecutionRepository.findById(execution.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.failed);
        assertThat(saved.getErrorMessage()).contains(".ps1 스크립트는 Windows 환경에서만 지원됩니다");

        System.out.println("✅ [Script] Linux 환경에서 .ps1 거부 정상 (BusinessException → JobExecution.failed)");

        Files.deleteIfExists(ps1Script);
    }

    // ========================================
    // 11. v18.7 — 자동 수집 실패 진단 JSON 자동 보관
    //     (L_USER_NEEDS_REDIRECT 결과물)
    // ========================================
    @Test
    @Order(11)
    @DisplayName("[Diagnosis] v18.7 — output 디렉토리의 _diagnosis.json 자동 보관 + errorDiagnosis 갱신")
    @Transactional
    void testCollectsDiagnosisJsonIntoJobExecution() throws IOException {
        // ── 1. 테스트 스크립트 작성 — output 디렉토리에 _diagnosis.json 떨어뜨리고 exit 0 ──
        Path scriptDir = Paths.get(scriptsBaseDir).toAbsolutePath().normalize();
        Files.createDirectories(scriptDir);

        Path testScript;
        if (IS_WINDOWS) {
            testScript = scriptDir.resolve("test_diagnosis.bat");
            Files.writeString(testScript,
                    "@echo off\r\n" +
                    "set \"OUTPUT_DIR=%~1\"\r\n" +
                    "if \"%OUTPUT_DIR%\"==\"\" set \"OUTPUT_DIR=%SECUHUB_OUTPUT_DIR%\"\r\n" +
                    "(\r\n" +
                    "echo {\r\n" +
                    "echo   \"schema_version\": \"1.0\",\r\n" +
                    "echo   \"execution\": {\"status\": \"failed\", \"duration_sec\": 1.4},\r\n" +
                    "echo   \"scenario\": {\"name\": \"테스트 시나리오\", \"total_steps\": 3},\r\n" +
                    "echo   \"steps\": [],\r\n" +
                    "echo   \"diagnosis\": {\"primary_cause\": \"button.login-submit 가 변경됨\"}\r\n" +
                    "echo }\r\n" +
                    ") > \"%OUTPUT_DIR%\\_diagnosis.json\"\r\n" +
                    "exit /b 0\r\n");
        } else {
            testScript = scriptDir.resolve("test_diagnosis.sh");
            Files.writeString(testScript,
                    "#!/bin/bash\n" +
                    "OUTPUT_DIR=\"$1\"\n" +
                    "cat > \"$OUTPUT_DIR/_diagnosis.json\" << 'EOF'\n" +
                    "{\n" +
                    "  \"schema_version\": \"1.0\",\n" +
                    "  \"execution\": {\"status\": \"failed\", \"duration_sec\": 1.4},\n" +
                    "  \"scenario\": {\"name\": \"테스트 시나리오\", \"total_steps\": 3},\n" +
                    "  \"steps\": [],\n" +
                    "  \"diagnosis\": {\"primary_cause\": \"button.login-submit 가 변경됨\"}\n" +
                    "}\n" +
                    "EOF\n" +
                    "exit 0\n");
            testScript.toFile().setExecutable(true);
        }

        // ── 2. CollectionJob + JobExecution 셋업 ──
        Framework fw = frameworkRepository.save(Framework.builder().name("진단 테스트 FW").build());
        ControlNode ctrl = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("DG-01").name("진단 테스트 항목")
                .displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl).name("진단 증빙").build());

        CollectionJob job = collectionJobRepository.save(CollectionJob.builder()
                .name("진단 보관 테스트")
                .jobType(JobType.web_scraping)
                .scriptPath(testScript.getFileName().toString())
                .evidenceType(et)
                .build());

        JobExecution execution = jobExecutionRepository.save(JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build());

        // ── 3. 실행 ──
        scriptExecutionService.executeSync(job, execution);

        // ── 4. 검증 — JobExecution.errorDiagnosis 가 _diagnosis.json 내용으로 채워졌는지 ──
        JobExecution saved = jobExecutionRepository.findById(execution.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.success);  // exit 0 이라 성공
        assertThat(saved.getErrorDiagnosis())
                .as("v18.7 — _diagnosis.json 의 내용이 errorDiagnosis 에 보관되어야 함")
                .isNotNull()
                .contains("\"schema_version\": \"1.0\"")
                .contains("button.login-submit 가 변경됨");

        System.out.println("✅ [Diagnosis] v18.7 진단 JSON 자동 보관 정상 — bytes=" +
                saved.getErrorDiagnosis().length());

        Files.deleteIfExists(testScript);
    }

    // ========================================
    // 12. v18.8.5 — Script entity (UID) 기반 작업 실행 정상
    //     (v18.8.2 LAZY proxy cross-thread 회귀 보호)
    // ========================================
    /**
     * v18.8.5 — v18.8.2 가 도입한 {@code CollectionJob.script} (`@ManyToOne(fetch = LAZY)`)
     * entity 가 {@code executeScript} 의 {@code job.getScript()} 분기에서 정상 resolve 되는지 검증.
     *
     * <p>옛 회귀 = async 컨텍스트 (executeAsync) 에서 부모 transaction 의 attached entity 가
     * cross-thread session 폐쇄 후 LAZY proxy 접근 시 "Session/EntityManager is closed".
     * v18.8.5 의 정공 fix = {@code executeAsync(Long, Long)} 시그니처 변경 — async 안에서 fresh
     * fetch. 본 테스트는 {@code executeSync} 로 동일 코드 경로 ({@code job.getScript()} LAZY 분기)
     * 의 정상 동작 검증.</p>
     *
     * <p>async 직접 검증은 단위 테스트에서 어려움 (executor 스레드 + 완료 대기 + 트랜잭션 격리).
     * 시그니처 변경 자체로 구조적 보호 — entity 가 아닌 ID 만 받음.</p>
     */
    @Test
    @Order(12)
    @DisplayName("[Script-UID] v18.8.5 — Script entity (UID) 기반 작업 실행 정상 (LAZY proxy 회귀 보호)")
    @Transactional
    void testExecuteWithScriptEntity() throws IOException {
        // ── 1. 테스트 스크립트 파일 작성 ──
        Path scriptDir = Paths.get(scriptsBaseDir).toAbsolutePath().normalize();
        Files.createDirectories(scriptDir);

        String fileName = "uid_test_" + System.currentTimeMillis() + scriptExt();
        Path testScript = scriptDir.resolve(fileName);

        if (IS_WINDOWS) {
            Files.writeString(testScript,
                    "@echo off\r\n" +
                    "set \"OUTPUT_DIR=%~1\"\r\n" +
                    "if \"%OUTPUT_DIR%\"==\"\" set \"OUTPUT_DIR=%SECUHUB_OUTPUT_DIR%\"\r\n" +
                    "echo uid-test result > \"%OUTPUT_DIR%\\uid_result.txt\"\r\n" +
                    "exit /b 0\r\n");
        } else {
            Files.writeString(testScript,
                    "#!/bin/bash\n" +
                    "OUTPUT_DIR=\"$1\"\n" +
                    "echo \"uid-test result\" > \"$OUTPUT_DIR/uid_result.txt\"\n" +
                    "exit 0\n");
            testScript.toFile().setExecutable(true);
        }

        // ── 2. Script entity 생성 (UID 기반) ──
        Script script = scriptRepository.save(Script.builder()
                .filePath(fileName)                       // 파일명만 (base-dir 기준 resolve)
                .contentSize((long) Files.size(testScript))
                .build());

        // ── 3. CollectionJob — script (entity, NOT scriptPath) 만 설정 ──
        Framework fw = frameworkRepository.save(Framework.builder().name("UID 테스트 FW").build());
        ControlNode ctrl = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("UID-01").name("UID 기반 작업 검증")
                .displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl).name("UID 증빙").build());

        CollectionJob job = collectionJobRepository.save(CollectionJob.builder()
                .name("UID 기반 수집 작업")
                .jobType(JobType.log_extract)
                .script(script)                            // v18.8.2 — UID 분기 (scriptPath = null)
                .scriptPath(null)
                .evidenceType(et)
                .build());

        JobExecution execution = jobExecutionRepository.save(JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build());

        // ── 4. 실행 — executeScript 의 job.getScript() LAZY 분기 검증 ──
        scriptExecutionService.executeSync(job, execution);

        // ── 5. 검증 ──
        JobExecution saved = jobExecutionRepository.findById(execution.getId()).orElseThrow();
        assertThat(saved.getStatus())
                .as("v18.8.5 — Script entity (UID) 기반 작업이 성공해야 함 (옛 v18.8.2 까지의 LAZY 회귀 fix)")
                .isEqualTo(ExecutionStatus.success);
        assertThat(saved.getFinishedAt()).isNotNull();
        assertThat(saved.getErrorMessage()).isNull();

        System.out.println("✅ [Script-UID] v18.8.5 Script entity (UID) 기반 실행 정상 — scriptId=" + script.getId());

        Files.deleteIfExists(testScript);
    }

    // ========================================
    // 13. v18.8.6 — 좀비 실행 정리 (운영 무재부팅 환경 안전망)
    //     (v18.8.5 commit timing race + BE 비정상 종료 등의 좀비 패턴 종결)
    // ========================================
    /**
     * v18.8.6 — SchedulerService 의 좀비 정리 메서드 회귀 보호.
     *
     * <p><b>검증 시나리오</b>:</p>
     * <ol>
     *   <li>현재 시각 기준 (timeout × 2 + 1분) 이전의 started_at 으로 running JobExecution 생성
     *       — "좀비" 시뮬레이션</li>
     *   <li>비교 대조군 — 방금 시작된 running JobExecution 생성 (정상 실행 중)</li>
     *   <li>{@code cleanupZombieExecutionsInternal} 직접 호출</li>
     *   <li>좀비는 status=failed + errorMessage 채워짐, 정상 실행은 status=running 그대로 유지</li>
     * </ol>
     *
     * <p>운영 환경 추정 좀비 발생 패턴:</p>
     * <ul>
     *   <li>BE 가 OOM / SIGKILL / 정전 으로 markFailed 호출 못 한 채 종료</li>
     *   <li>ProcessBuilder 가 hang 후 waitFor timeout 도 우회 (드물지만 가능)</li>
     *   <li>async 의 catch 안에서 markFailed 자체가 또 예외 → status 갱신 실패</li>
     *   <li>v18.8.5 의 commit timing race (v18.8.6 의 afterCommit 패턴으로 종결됐지만 안전망)</li>
     * </ul>
     *
     * <p>본 테스트는 startup hook + 정기 검사 양쪽이 의존하는 공용 helper
     * ({@link SchedulerService#cleanupZombieExecutionsInternal}) 의 단위 검증.</p>
     */
    @Test
    @Order(13)
    @DisplayName("[Zombie] v18.8.6 — 좀비 실행 자동 정리 (timeout × 2 임계값)")
    @Transactional
    void testZombieExecutionCleanup() {
        long zombieThresholdSec = scriptTimeoutSeconds * 2;

        // ── 1. 좀비 시뮬레이션 — started_at 을 임계값보다 더 옛 시각으로 ──
        Framework fw = frameworkRepository.save(Framework.builder().name("좀비 테스트 FW").build());
        ControlNode ctrl = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("ZB-01").name("좀비 정리 항목")
                .displayOrder(0).depth(1).build());

        CollectionJob job = collectionJobRepository.save(CollectionJob.builder()
                .name("좀비 정리 검증 작업")
                .jobType(JobType.log_extract)
                .scriptPath("dummy.sh")
                .build());

        LocalDateTime zombieStartedAt = LocalDateTime.now().minusSeconds(zombieThresholdSec + 60);
        JobExecution zombie = jobExecutionRepository.save(JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(zombieStartedAt)
                .build());

        // ── 2. 대조군 — 방금 시작된 정상 실행 (좀비 아님) ──
        JobExecution fresh = jobExecutionRepository.save(JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build());

        // ── 3. 좀비 정리 호출 (startup / scheduled 공용 helper) ──
        long cleaned = schedulerService.cleanupZombieExecutionsInternal("test");

        // ── 4. 검증 ──
        assertThat(cleaned)
                .as("v18.8.6 — 좀비 1건 검출되어야 함 (fresh 는 임계값 안이라 제외)")
                .isGreaterThanOrEqualTo(1);

        JobExecution zombieAfter = jobExecutionRepository.findById(zombie.getId()).orElseThrow();
        assertThat(zombieAfter.getStatus())
                .as("좀비는 failed 로 markFailed 되어야 함")
                .isEqualTo(ExecutionStatus.failed);
        assertThat(zombieAfter.getFinishedAt()).isNotNull();
        assertThat(zombieAfter.getErrorMessage())
                .as("좀비 정리 사유가 errorMessage 에 기록되어야 함")
                .contains("좀비 실행 자동 정리")
                .contains("test")
                .contains(String.valueOf(zombieThresholdSec));

        JobExecution freshAfter = jobExecutionRepository.findById(fresh.getId()).orElseThrow();
        assertThat(freshAfter.getStatus())
                .as("임계값 안의 정상 실행은 건드리지 않아야 함")
                .isEqualTo(ExecutionStatus.running);
        assertThat(freshAfter.getFinishedAt())
                .as("정상 실행의 finished_at 은 그대로 NULL 유지")
                .isNull();

        System.out.println("✅ [Zombie] v18.8.6 좀비 정리 정상 — 정리 " + cleaned +
                "건, 임계값=" + zombieThresholdSec + "초");
    }
}