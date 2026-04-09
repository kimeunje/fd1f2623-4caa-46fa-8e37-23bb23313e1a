package com.secuhub;

import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.evidence.service.EvidenceFileService;
import com.secuhub.domain.evidence.service.ScriptExecutionService;
import com.secuhub.domain.evidence.dto.EvidenceFileDto;
import org.junit.jupiter.api.*;
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

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 2 후속 — 스크립트 실행 + 다운로드 API 테스트")
class ScriptExecutionTest {

    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlRepository controlRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;
    @Autowired private CollectionJobRepository collectionJobRepository;
    @Autowired private JobExecutionRepository jobExecutionRepository;
    @Autowired private ScriptExecutionService scriptExecutionService;
    @Autowired private EvidenceFileService evidenceFileService;

    @Value("${app.storage.path:./storage}")
    private String storagePath;

    @Value("${app.scripts.base-dir:./scripts}")
    private String scriptsBaseDir;

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
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("SC-01").name("스크립트 테스트 항목").build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl).name("스크립트 증빙").build());

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
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("SC-02").name("스크립트 테스트 항목2").build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl).name("스크립트 증빙2").build());

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
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("SC-03").name("성공 스크립트 항목").build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl).name("서버 점검 결과").build());

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

        // flush로 영속성 컨텍스트 동기화
        evidenceFileRepository.flush();
        List<EvidenceFile> files = evidenceFileRepository.findByEvidenceTypeIdOrderByVersionDesc(et.getId());

        // 전체 evidence_files 테이블도 확인
        List<EvidenceFile> allFiles = evidenceFileRepository.findAll();
        System.out.println("   DB 전체 파일 수: " + allFiles.size());
        allFiles.forEach(f -> System.out.println("     -> id=" + f.getId()
                + " etId=" + f.getEvidenceType().getId()
                + " method=" + f.getCollectionMethod()
                + " name=" + f.getFileName()));
        System.out.println("   ET " + et.getId() + " 파일 수: " + files.size());

        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFileName()).contains("collect_result");
        assertThat(files.get(0).getCollectionMethod()).isEqualTo(CollectionMethod.auto);
        assertThat(files.get(0).getVersion()).isEqualTo(1);
        assertThat(files.get(0).getExecution()).isNotNull();

        System.out.println("✅ [Script] 성공 스크립트 실행 + 파일 자동 수집 정상");
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
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("SC-04").name("실패 스크립트 항목").build());

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
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("DL-01").name("다운로드 테스트 항목").build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl).name("다운로드 증빙").build());

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
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("DL-02").name("미싱 테스트 항목").build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl).name("미싱 증빙").build());

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
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("CT-01").name("CT 항목").build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl).name("CT 증빙").build());

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
}
