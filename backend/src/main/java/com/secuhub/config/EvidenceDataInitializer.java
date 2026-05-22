package com.secuhub.config;

import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * dev 프로필 데모 데이터 — 증빙 수집 관련.
 *
 * <p>DataInitializer(계정) 이후에 실행됩니다 (@Order(2)).</p>
 *
 * <h3>v18.8.8 — 진짜 최소 테스트 시드</h3>
 *
 * <p><b>본 의제 본질</b>: UI/UX 변경 시 "수동 업로드 / 자동 수집 두 채널 차이"
 * 화면에서 시연. 9가지 상태 매트릭스 시연은 needs 아님 — L_OVER_ENGINEER_DETECT
 * 자가 적발 후 단순화 (사용자 표면 = "딱 테스트 하기 편하게").</p>
 *
 * <h3>구조</h3>
 * <pre>
 * ISMS-P 2026 (Framework 1)
 * └── 1. 관리체계 (cat depth=1)
 *     └── 1.1 정책 (cat depth=2)
 *         ├── 1.1.1 정보보호 정책 수립 (control depth=3)
 *         │   ├── 정보보호 정책서        [수동 업로드 — approved]
 *         │   └── 정보보호 조직도        [자동 수집 — 성공]
 *         └── 1.1.2 보호대책 요구사항 (control depth=3)
 *             ├── 접근통제 정책서        [수동 업로드 — pending 검토 대기]
 *             ├── 정보자산 목록          [자동 수집 — 성공]
 *             └── 출입 기록              [자동 수집 — 실패 + 진단]
 * </pre>
 *
 * <p>spec §3.3.1.1 결정 #16 (v18 갱신) 정합 — view 모드 렌더링이 depth 기반.
 * depth 1-2 = 카테고리 스타일 (자식 ControlNode 표시), depth 3+ = leaf 스타일
 * (EvidenceType 표시). 통제를 depth=3 에 두어야 ControlsView 에서 EvidenceType
 * (화면 용어 "관리 항목") 이 정상 렌더링됨.</p>
 *
 * <h3>채널 정합</h3>
 * <ul>
 *   <li><b>수동 업로드 채널</b> = EvidenceFile.collectionMethod=manual + asset link.
 *       EvidenceTypeDetailView 의 "수동 업로드" 탭에 표시</li>
 *   <li><b>자동 수집 채널</b> = CollectionJob + JobExecution + EvidenceFile.collectionMethod=auto
 *       + execution_id + asset link. EvidenceTypeDetailView 의 "자동 수집" 탭에
 *       작업 + 실행 이력 + 결과 파일 표시</li>
 *   <li>실패 진단 = JobExecution.errorDiagnosis (JSON) + output 디렉토리의
 *       _diag_screenshot.png + _diag_page_source.html. FailureDiagnosisPanel 정상 동작</li>
 * </ul>
 *
 * <h3>재실행 정책</h3>
 * <p>{@code frameworkRepository.count() > 0} 시 skip. 시드 갱신은 DB drop 수동.</p>
 */
@Slf4j
@Component
@Profile("dev")
@Order(2)
@RequiredArgsConstructor
public class EvidenceDataInitializer implements CommandLineRunner {

    private final FrameworkRepository frameworkRepository;
    private final ControlNodeRepository controlNodeRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final EvidenceFileRepository evidenceFileRepository;
    private final EvidenceAssetRepository evidenceAssetRepository;
    private final CollectionJobRepository collectionJobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final ScriptRepository scriptRepository;
    private final UserRepository userRepository;

    @Value("${app.storage.path:./storage}")
    private String storagePath;

    @Value("${app.scripts.base-dir:./scripts}")
    private String scriptsBaseDir;

    // ====================================================================
    // 상수 — 1x1 transparent PNG (진단 스크린샷)
    // ====================================================================

    private static final byte[] TINY_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54,
            0x78, (byte) 0x9C, 0x62, 0x00, 0x01, 0x00, 0x00, 0x05,
            0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00, 0x00,
            0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42,
            0x60, (byte) 0x82
    };

    // ====================================================================
    // 메인 — run
    // ====================================================================

    @Override
    public void run(String... args) {
        if (frameworkRepository.count() > 0) {
            log.info("증빙 데모 데이터가 이미 존재합니다. 초기화를 건너뜁니다.");
            return;
        }

        log.info("v18.8.8 증빙 데모 데이터 초기화 시작 (수동/자동 채널 최소 시드)...");

        User admin = userRepository.findByEmail("admin@company.com").orElse(null);
        User parkTl = userRepository.findByEmail("park_tl@company.com").orElse(null);
        User kimDev = userRepository.findByEmail("kim@company.com").orElse(null);

        LocalDate today = LocalDate.now();

        // ====================================================================
        // Framework + 카테고리 + 통제
        // ====================================================================
        Framework ismsp = frameworkRepository.save(Framework.builder()
                .name("ISMS-P 2026")
                .description("정보보호 및 개인정보보호 관리체계 인증")
                .status(FrameworkStatus.active)
                .build());

        // spec §3.3.1.1 결정 #16 (v18 갱신):
        //   view 모드 렌더링은 depth 기반 분기. depth 1-2 = 카테고리 스타일,
        //   depth 3+ = leaf 스타일 (증빙 정보 + 하위 칩 표시).
        // 따라서 통제 (EvidenceType 매달리는 노드) 는 depth=3 이상에 둬야
        // ControlsView 화면에 증빙 자료가 정상 표시됨.
        ControlNode cat1 = saveCat(ismsp, null, "1", "관리체계", 0, 1);
        ControlNode cat11 = saveCat(ismsp, cat1, "1.1", "정책", 0, 2);
        ControlNode c11 = saveCtrl(ismsp, cat11, "1.1.1", "정보보호 정책 수립", 0, 3);
        ControlNode c12 = saveCtrl(ismsp, cat11, "1.1.2", "보호대책 요구사항", 1, 3);

        // ====================================================================
        // 통제 1.1.1 — 정보보호 정책 수립
        // ====================================================================

        // ── 증빙 1: 정보보호 정책서 (수동 업로드 — approved)
        EvidenceType et_policy = saveEt(c11, "정보보호 정책서", admin, today.plusDays(60));
        saveLinkText(et_policy, "정보보호_정책서_v2.md", policyContent(),
                1, CollectionMethod.manual,
                LocalDateTime.now().minusMonths(2), ReviewStatus.approved,
                admin, admin, null, null);

        // ── 증빙 2: 정보보호 조직도 (자동 수집 — 성공)
        EvidenceType et_org = saveEt(c11, "정보보호 조직도", admin, today.plusDays(90));

        // ====================================================================
        // 통제 1.1.2 — 보호대책 요구사항
        // ====================================================================

        // ── 증빙 1: 접근통제 정책서 (수동 업로드 — pending 검토 대기)
        EvidenceType et_access = saveEt(c12, "접근통제 정책서", parkTl, today.plusDays(30));
        saveLinkText(et_access, "접근통제_정책서.md", accessPolicyContent(),
                1, CollectionMethod.manual,
                LocalDateTime.now().minusDays(2), ReviewStatus.pending,
                parkTl, null, null, "초안입니다. 검토 부탁드립니다.");

        // ── 증빙 2: 정보자산 목록 (자동 수집 — 성공)
        EvidenceType et_asset = saveEt(c12, "정보자산 목록", kimDev, today.plusDays(60));

        // ── 증빙 3: 출입 기록 (자동 수집 — 실패 + 진단)
        EvidenceType et_entry = saveEt(c12, "출입 기록 월간 보고서", kimDev, today.plusDays(30));

        // ====================================================================
        // Script + CollectionJob + JobExecution (자동 수집 채널)
        // ====================================================================
        seedScriptsAndJobs(et_org, et_asset, et_entry);

        log.info("v18.8.8 증빙 데모 데이터 초기화 완료 — Framework: {}, 통제 노드: {}, " +
                        "EvidenceType: {}, EvidenceFile: {}, Asset: {}, Script: {}, Job: {}, Execution: {}",
                ismsp.getName(),
                controlNodeRepository.count(),
                evidenceTypeRepository.count(),
                evidenceFileRepository.count(),
                evidenceAssetRepository.count(),
                scriptRepository.count(),
                collectionJobRepository.count(),
                jobExecutionRepository.count());
    }

    // ====================================================================
    // Script + Job + Execution + 자동 수집 결과
    // ====================================================================

    private void seedScriptsAndJobs(EvidenceType etOrg, EvidenceType etAsset,
                                     EvidenceType etEntry) {
        // ── 스크립트 3개
        Script scriptOrg = saveScript(
                "# 조직도 자동 추출 (데모)\n" +
                "import os\n" +
                "output_dir = os.environ.get('SECUHUB_OUTPUT_DIR', '.')\n" +
                "with open(os.path.join(output_dir, 'orgchart.txt'), 'w', encoding='utf-8') as f:\n" +
                "    f.write('회사 조직도\\n')\n" +
                "print('완료')\n");

        Script scriptAsset = saveScript(
                "# 정보자산 목록 추출 (데모)\n" +
                "import os\n" +
                "output_dir = os.environ.get('SECUHUB_OUTPUT_DIR', '.')\n" +
                "# 자산관리 시스템 API 호출 ...\n" +
                "print('완료')\n");

        Script scriptEntry = saveScript(
                "# 출입 기록 추출 (데모, selenium)\n" +
                "from selenium import webdriver\n" +
                "driver = webdriver.Chrome()\n" +
                "driver.get('https://internal.example.com/access-control')\n" +
                "driver.find_element('css selector', 'button.login-submit').click()\n");

        // ── CollectionJob 3개 (모두 active, cron 있음)
        CollectionJob jobOrg = saveJob("조직도 자동 추출",
                "HR 시스템에서 조직도를 매월 1일 06시 자동 추출합니다.",
                JobType.web_scraping, scriptOrg, etOrg, "0 0 6 1 * ?", true);

        CollectionJob jobAsset = saveJob("정보자산 목록 추출",
                "자산관리 시스템에서 정보자산 목록을 매주 월요일 09시 추출합니다.",
                JobType.web_scraping, scriptAsset, etAsset, "0 0 9 * * MON", true);

        CollectionJob jobEntry = saveJob("출입 기록 월간 추출",
                "출입통제 시스템에서 월간 출입 기록을 매월 1일 07시 추출합니다.",
                JobType.log_extract, scriptEntry, etEntry, "0 0 7 1 * ?", true);

        // ── JobExecution + 자동 수집 결과

        // jobOrg: 성공 1회 → 조직도.txt 자동 수집 완료
        JobExecution execOrg = saveExecution(jobOrg, ExecutionStatus.success,
                LocalDateTime.now().minusDays(20), LocalDateTime.now().minusDays(20).plusMinutes(2),
                null, null);
        saveAutoCollectedFile(jobOrg, execOrg, "조직도_2026.txt", orgChartContent());

        // jobAsset: 성공 1회 → 자산 목록.txt 자동 수집 완료
        JobExecution execAsset = saveExecution(jobAsset, ExecutionStatus.success,
                LocalDateTime.now().minusDays(3), LocalDateTime.now().minusDays(3).plusMinutes(3),
                null, null);
        saveAutoCollectedFile(jobAsset, execAsset, "정보자산_목록.txt", assetListContent());

        // jobEntry: 실패 1회 + 진단 케이스 (selenium selector 깨짐)
        JobExecution execEntry = saveExecution(jobEntry, ExecutionStatus.failed,
                LocalDateTime.now().minusHours(6), LocalDateTime.now().minusHours(6).plusMinutes(2),
                "selenium.common.exceptions.NoSuchElementException: 'button.login-submit'",
                buildDemoDiagnosisJson());
        writeDiagnosisArtifacts(jobEntry.getId(), execEntry.getId());
    }

    // ====================================================================
    // 헬퍼 — ControlNode / EvidenceType
    // ====================================================================

    private ControlNode saveCat(Framework fw, ControlNode parent, String code, String name,
                                int displayOrder, int depth) {
        return controlNodeRepository.save(ControlNode.builder()
                .framework(fw)
                .parent(parent)
                .nodeType(NodeType.category)
                .code(code)
                .name(name)
                .displayOrder(displayOrder)
                .depth(depth)
                .build());
    }

    private ControlNode saveCtrl(Framework fw, ControlNode parent, String code, String name,
                                  int displayOrder, int depth) {
        return controlNodeRepository.save(ControlNode.builder()
                .framework(fw)
                .parent(parent)
                .nodeType(NodeType.control)
                .code(code)
                .name(name)
                .displayOrder(displayOrder)
                .depth(depth)
                .build());
    }

    private EvidenceType saveEt(ControlNode ctrl, String name, User owner, LocalDate dueDate) {
        return evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl)
                .name(name)
                .ownerUser(owner)
                .dueDate(dueDate)
                .build());
    }

    // ====================================================================
    // 헬퍼 — 수동 업로드 (txt/md)
    // ====================================================================

    private EvidenceFile saveLinkText(EvidenceType et, String fileName, String content,
                                       int version, CollectionMethod method,
                                       LocalDateTime collectedAt, ReviewStatus reviewStatus,
                                       User uploader, User reviewer,
                                       String reviewNote, String submitNote) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        EvidenceAsset asset = createAsset(fileName, bytes, uploader);
        return linkEvidenceFile(et, asset, fileName, version, method, collectedAt,
                reviewStatus, uploader, reviewer, reviewNote, submitNote, null);
    }

    /**
     * 자동 수집 결과 시뮬 — output 디렉토리 + asset + EvidenceFile(auto+execution_id).
     */
    private EvidenceFile saveAutoCollectedFile(CollectionJob job, JobExecution execution,
                                                String fileName, String content) {
        EvidenceType et = job.getEvidenceType();
        if (et == null) {
            log.warn("자동 수집 시뮬 skip — evidenceType 미설정 작업: jobId={}", job.getId());
            return null;
        }

        // 1. output 디렉토리 + 결과 파일 (실제 ScriptExecutionService 의 산출 위치)
        Path outputDir = Paths.get(storagePath, "output",
                        String.valueOf(job.getId()), String.valueOf(execution.getId()))
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve(fileName), content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("자동 수집 결과 파일 작성 실패 (dev 시드): jobId={}, executionId={}, error={}",
                    job.getId(), execution.getId(), e.getMessage());
        }

        // 2. EvidenceAsset 생성 (수동 업로드와 같은 채널)
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        EvidenceAsset asset = createAsset(fileName, bytes, null);

        // 3. EvidenceFile (auto + execution_id + asset link)
        int nextVersion = evidenceFileRepository.findMaxVersionByEvidenceTypeId(et.getId())
                .orElse(0) + 1;
        return linkEvidenceFile(et, asset, fileName, nextVersion, CollectionMethod.auto,
                execution.getStartedAt(), ReviewStatus.auto_approved,
                null, null, null, null, execution);
    }

    private EvidenceAsset createAsset(String originalFileName, byte[] content, User uploader) {
        EvidenceAsset asset = evidenceAssetRepository.save(EvidenceAsset.builder()
                .sha256(sha256(content))
                .filePath("PENDING")
                .fileSize((long) content.length)
                .originalFileName(originalFileName)
                .uploadedBy(uploader)
                .build());

        Path absolutePath = Paths.get(storagePath, EvidenceAsset.buildRelativePath(asset.getId()))
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolutePath.getParent());
            Files.write(absolutePath, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("asset 물리 파일 저장 실패 (dev 시드): id={}, path={}, error={}",
                    asset.getId(), absolutePath, e.getMessage());
        }

        asset.updateFilePath(absolutePath.toString());
        return evidenceAssetRepository.save(asset);
    }

    private EvidenceFile linkEvidenceFile(EvidenceType et, EvidenceAsset asset,
                                           String fileName, int version,
                                           CollectionMethod method, LocalDateTime collectedAt,
                                           ReviewStatus reviewStatus,
                                           User uploader, User reviewer,
                                           String reviewNote, String submitNote,
                                           JobExecution execution) {
        EvidenceFile.EvidenceFileBuilder b = EvidenceFile.builder()
                .evidenceType(et)
                .asset(asset)
                .fileName(fileName)
                .filePath(asset.getFilePath())
                .fileSize(asset.getFileSize())
                .version(version)
                .collectionMethod(method)
                .collectedAt(collectedAt)
                .reviewStatus(reviewStatus);

        if (execution != null) b.execution(execution);

        if (uploader != null) b.uploadedBy(uploader);
        else if (et.getOwnerUser() != null && method == CollectionMethod.manual) {
            b.uploadedBy(et.getOwnerUser());
        }

        if (submitNote != null) b.submitNote(submitNote);

        if (reviewer != null) {
            b.reviewedBy(reviewer);
            b.reviewedAt(collectedAt.plusHours(6));
        }
        if (reviewNote != null) b.reviewNote(reviewNote);

        return evidenceFileRepository.save(b.build());
    }

    // ====================================================================
    // 헬퍼 — Script entity + .py 더미
    // ====================================================================

    private Script saveScript(String content) {
        String uuid = UUID.randomUUID().toString();
        String filename = uuid + ".py";

        Script script = scriptRepository.save(Script.builder()
                .filePath(filename)
                .contentSize((long) content.getBytes(StandardCharsets.UTF_8).length)
                .build());

        Path target = Paths.get(scriptsBaseDir, filename).toAbsolutePath().normalize();
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("script 물리 파일 저장 실패 (dev 시드): id={}, path={}, error={}",
                    script.getId(), target, e.getMessage());
        }
        return script;
    }

    // ====================================================================
    // 헬퍼 — Job / Execution
    // ====================================================================

    private CollectionJob saveJob(String name, String description, JobType jobType,
                                   Script script, EvidenceType evidenceType,
                                   String scheduleCron, boolean isActive) {
        CollectionJob.CollectionJobBuilder b = CollectionJob.builder()
                .name(name)
                .description(description)
                .jobType(jobType)
                .scheduleCron(scheduleCron)
                .isActive(isActive);
        if (script != null) b.script(script);
        if (evidenceType != null) b.evidenceType(evidenceType);
        return collectionJobRepository.save(b.build());
    }

    private JobExecution saveExecution(CollectionJob job, ExecutionStatus status,
                                        LocalDateTime startedAt, LocalDateTime finishedAt,
                                        String errorMessage, String errorDiagnosis) {
        JobExecution exec = JobExecution.builder()
                .job(job)
                .status(status)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .errorMessage(errorMessage)
                .errorDiagnosis(errorDiagnosis)
                .build();
        return jobExecutionRepository.save(exec);
    }

    // ====================================================================
    // 헬퍼 — 진단 데이터
    // ====================================================================

    private String buildDemoDiagnosisJson() {
        return "{\n" +
                "  \"schema_version\": \"1.0\",\n" +
                "  \"execution\": {\n" +
                "    \"started_at\": \"" + LocalDateTime.now().minusHours(6) + "\",\n" +
                "    \"finished_at\": \"" + LocalDateTime.now().minusHours(6).plusMinutes(2) + "\",\n" +
                "    \"duration_seconds\": 120,\n" +
                "    \"exit_code\": 1\n" +
                "  },\n" +
                "  \"scenario\": {\n" +
                "    \"name\": \"출입통제 시스템 로그인 + 월간 보고서 다운로드\",\n" +
                "    \"target_url\": \"https://internal.example.com/access-control\",\n" +
                "    \"total_steps\": 5\n" +
                "  },\n" +
                "  \"steps\": [\n" +
                "    {\"index\": 1, \"action\": \"open\", \"target\": \"https://internal.example.com/access-control\", \"status\": \"success\", \"duration_ms\": 1200},\n" +
                "    {\"index\": 2, \"action\": \"type\", \"target\": \"#username\", \"value\": \"svc_secuhub\", \"status\": \"success\", \"duration_ms\": 80},\n" +
                "    {\"index\": 3, \"action\": \"type\", \"target\": \"#password\", \"value\": \"***\", \"status\": \"success\", \"duration_ms\": 75},\n" +
                "    {\"index\": 4, \"action\": \"click\", \"target\": \"button.login-submit\", \"status\": \"failed\", \"duration_ms\": 30000, \"error\": \"NoSuchElementException\"},\n" +
                "    {\"index\": 5, \"action\": \"export\", \"target\": \"#export-btn\", \"status\": \"not_run\"}\n" +
                "  ],\n" +
                "  \"diagnosis\": {\n" +
                "    \"primary_cause\": \"selector_changed\",\n" +
                "    \"failed_step_index\": 4,\n" +
                "    \"failed_selector\": \"button.login-submit\",\n" +
                "    \"korean_summary\": \"로그인 버튼의 selector 가 변경되었습니다. button.login-submit 가 페이지에서 발견되지 않습니다. 사이트 개편 또는 A/B 테스트 가능성을 확인하세요.\",\n" +
                "    \"suggestion\": \"진단 패널의 스크린샷과 page_source 를 확인하고 selector 를 수정한 뒤 [재실행] 버튼으로 검증하세요.\"\n" +
                "  }\n" +
                "}\n";
    }

    private void writeDiagnosisArtifacts(Long jobId, Long executionId) {
        Path dir = Paths.get(storagePath, "output", String.valueOf(jobId), String.valueOf(executionId))
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);

            Files.write(dir.resolve("_diag_screenshot.png"), TINY_PNG,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            String html = "<!DOCTYPE html>\n" +
                    "<html lang=\"ko\">\n" +
                    "<head><meta charset=\"utf-8\"><title>출입통제 시스템 로그인</title></head>\n" +
                    "<body>\n" +
                    "  <h1>출입통제 시스템</h1>\n" +
                    "  <form>\n" +
                    "    <input id=\"username\" name=\"username\" />\n" +
                    "    <input id=\"password\" name=\"password\" type=\"password\" />\n" +
                    "    <!-- button.login-submit 가 button#login-button 으로 변경됨 -->\n" +
                    "    <button id=\"login-button\" type=\"submit\">로그인</button>\n" +
                    "  </form>\n" +
                    "</body>\n" +
                    "</html>\n";
            Files.writeString(dir.resolve("_diag_page_source.html"), html, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.info("진단 더미 파일 작성: jobId={}, executionId={}, dir={}", jobId, executionId, dir);
        } catch (IOException e) {
            log.warn("진단 더미 파일 작성 실패 (dev 시드): jobId={}, executionId={}, error={}",
                    jobId, executionId, e.getMessage());
        }
    }

    // ====================================================================
    // 콘텐츠 (한국어, txt + md)
    // ====================================================================

    private String policyContent() {
        return "# 정보보호 정책서\n\n" +
                "## 1. 목적\n" +
                "본 정책은 회사의 정보자산을 보호하고, 임직원 및 협력사의 정보보호 활동에 대한 기본 원칙을 정의합니다.\n\n" +
                "## 2. 적용 범위\n" +
                "본사의 모든 임직원 / 상주 협력사 / 정보처리시스템 / 클라우드 환경에 적용됩니다.\n\n" +
                "## 3. 정보보호 조직\n" +
                "- 최고책임자(CISO): 정보보호 업무 총괄\n" +
                "- 개인정보보호책임자(CPO): 개인정보 처리 총괄\n" +
                "- 정보보호팀: 일상 운영 및 모니터링\n\n" +
                "## 4. 정보보호 원칙\n" +
                "1. 정보자산은 그 중요도에 따라 분류·관리한다.\n" +
                "2. 접근권한은 업무 필요성에 따라 최소 권한으로 부여한다.\n" +
                "3. 모든 정보 처리 활동은 감사 추적이 가능해야 한다.\n\n" +
                "---\n" +
                "제정일: 2024-01-15 | 시행일: 2024-02-01\n";
    }

    private String accessPolicyContent() {
        return "# 접근통제 정책서 (초안)\n\n" +
                "## 1. 목적\n" +
                "정보자산에 대한 비인가 접근을 차단하고, 인가된 사용자의 접근을 통제하기 위한 기준을 정의합니다.\n\n" +
                "## 2. 접근권한 부여 원칙\n" +
                "- 최소 권한 원칙 (Need-to-Know)\n" +
                "- 직무 분리 (Segregation of Duties)\n" +
                "- 정기 재검토 (분기 1회)\n\n" +
                "## 3. 인증 방식\n" +
                "- 일반 사용자: ID/비밀번호 + 2FA\n" +
                "- 관리자: ID/비밀번호 + 2FA + 접속 IP 제한\n" +
                "- 외부 협력사: 별도 VPN + 2FA\n\n" +
                "## 4. 접근권한 회수\n" +
                "- 퇴직: 즉시\n" +
                "- 부서 이동: 7일 이내\n" +
                "- 장기 휴직: 30일 이상 시 비활성화\n\n" +
                "---\n" +
                "작성일: " + LocalDate.now().minusDays(2) + " | 작성자: 박팀장\n";
    }

    private String orgChartContent() {
        return "회사 조직도 (자동 수집)\n" +
                "================================================\n" +
                "수집 일시: " + LocalDateTime.now().minusDays(20) + "\n" +
                "수집 출처: HR 시스템\n" +
                "================================================\n\n" +
                "대표이사\n" +
                "  ├─ CISO (홍길동)\n" +
                "  │   └─ 보안팀 (5명)\n" +
                "  ├─ CTO\n" +
                "  │   ├─ 백엔드팀 (8명)\n" +
                "  │   ├─ 프론트엔드팀 (6명)\n" +
                "  │   └─ DevOps팀 (3명)\n" +
                "  ├─ CFO\n" +
                "  │   ├─ 회계팀 (4명)\n" +
                "  │   └─ 재무팀 (3명)\n" +
                "  └─ CMO\n" +
                "      ├─ 마케팅팀 (7명)\n" +
                "      └─ 디자인팀 (4명)\n\n" +
                "총 인원: 41명\n";
    }

    private String assetListContent() {
        return "정보자산 목록 (자동 수집)\n" +
                "================================================\n" +
                "수집 일시: " + LocalDateTime.now().minusDays(3) + "\n" +
                "수집 출처: 자산관리 시스템 (CMDB)\n" +
                "================================================\n\n" +
                "[서버]\n" +
                "  web-prod-01     | Rocky 8.9    | 192.168.10.10  | 웹 서비스\n" +
                "  web-prod-02     | Rocky 8.9    | 192.168.10.11  | 웹 서비스 (HA)\n" +
                "  api-prod-01     | Rocky 8.9    | 192.168.10.20  | API 서버\n" +
                "  db-prod-01      | Rocky 8.9    | 192.168.20.10  | MariaDB primary\n" +
                "  db-prod-02      | Rocky 8.9    | 192.168.20.11  | MariaDB replica\n\n" +
                "[네트워크 장비]\n" +
                "  fw-main         | FortiGate    | 192.168.1.1    | 방화벽\n" +
                "  sw-core-01      | Cisco        | 192.168.1.2    | 코어 스위치\n\n" +
                "[클라우드]\n" +
                "  S3 (logs)       | AWS          | seoul          | 로그 보관\n" +
                "  CloudFront      | AWS          | global         | CDN\n\n" +
                "총 자산: 9건\n";
    }

    // ====================================================================
    // sha256
    // ====================================================================

    private String sha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm 부재", e);
        }
    }
}