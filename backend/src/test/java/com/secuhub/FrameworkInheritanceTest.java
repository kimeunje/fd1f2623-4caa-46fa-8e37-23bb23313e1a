package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.dto.FrameworkDto;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 5-6 — Framework 상속 (스냅샷 복제) 검증
 *
 * <h3>검증 매트릭스</h3>
 * <ul>
 *   <li>통제 항목 / 증빙 유형 / 수집 작업이 정확히 복제되는가</li>
 *   <li>증빙 유형의 owner_user_id, due_date 유지 여부</li>
 *   <li>파일·실행이력은 복제되지 않는가</li>
 *   <li>상속 관계 (parent_framework_id) 기록 여부</li>
 *   <li>원본 Framework 는 상속 후에도 변경되지 않는가 (격리)</li>
 *   <li>evidence_type 이 NULL 인 전역 작업은 복제 제외</li>
 *   <li>권한: admin 만 상속 가능</li>
 *   <li>검증 에러: sourceFrameworkId null / name blank → 400</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-6 — Framework 상속")
class FrameworkInheritanceTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;   // v14 Phase 5-14f
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;
    @Autowired private CollectionJobRepository collectionJobRepository;
    @Autowired private ScriptRepository scriptRepository;                 // v19.19 — 스크립트 복제 검증
    @Autowired private ScriptVersionRepository scriptVersionRepository;   // v19.19 — 정리용
    @PersistenceContext private EntityManager entityManager;              // v19.19 — setUp 삭제 flush

    /**
     * v19.19 — 스크립트 복제는 실제 파일시스템(app.scripts.base-dir)에 .py 를 쓴다.
     * 테스트가 운영/기본 scripts 디렉토리를 건드리지 않도록 임시 디렉토리로 오버라이드.
     * static 초기화 블록에서 생성 → @DynamicPropertySource 보다 먼저 확정(컨텍스트 기동 전).
     */
    private static final Path TEMP_SCRIPTS_DIR;
    static {
        try {
            TEMP_SCRIPTS_DIR = Files.createTempDirectory("secuhub-inherit-test-scripts");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void overrideScriptsBaseDir(DynamicPropertyRegistry registry) {
        registry.add("app.scripts.base-dir", TEMP_SCRIPTS_DIR::toString);
    }

    private String adminToken;
    private String developerToken;
    private User ownerUser;

    @BeforeEach
    void setUp() {
        // v19.19 — @Transactional 테스트(setUp 이 tx 안에서 실행)에서만 deleteAll 이 큐잉되어,
        //   IDENTITY save 의 즉시 INSERT 가 아직 flush 안 된 기존 row 와 unique 충돌할 수 있다.
        //   해당 경우에만 각 단계 flush 로 child→parent 즉시 삭제 순서를 보장한다(FK 안전).
        //   비-트랜잭션 테스트는 deleteAll 이 즉시 커밋되고 활성 tx 가 없어 flush 호출 시
        //   TransactionRequiredException → isActualTransactionActive() 가드로 분기.
        boolean txActive = TransactionSynchronizationManager.isActualTransactionActive();

        evidenceFileRepository.deleteAll();      if (txActive) entityManager.flush();
        collectionJobRepository.deleteAll();     if (txActive) entityManager.flush();
        evidenceTypeRepository.deleteAll();      if (txActive) entityManager.flush();
        controlNodeRepository.deleteAll();       if (txActive) entityManager.flush();   // v14 Phase 5-14f
        frameworkRepository.deleteAll();         if (txActive) entityManager.flush();
        userRepository.deleteAll();              if (txActive) entityManager.flush();
        // 스크립트 정리는 마지막 + 격리(jobs 삭제 후라 FK 안전, 실패해도 핵심 정리 무영향).
        try {
            scriptVersionRepository.deleteAll();
            scriptRepository.deleteAll();
            if (txActive) entityManager.flush();
        } catch (Exception e) {
            System.out.println("[setUp] 스크립트 정리 경고(무시): " + e.getMessage());
        }

        User admin = userRepository.save(User.builder()
                .email("inherit-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin).permissionEvidence(true).build());
        adminToken = jwtTokenProvider.createToken(admin.getId(), admin.getEmail(), "admin");

        User developer = userRepository.save(User.builder()
                .email("inherit-dev@test.com").name("개발자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.developer).permissionEvidence(true).build());
        developerToken = jwtTokenProvider.createToken(developer.getId(), developer.getEmail(), "developer");

        ownerUser = userRepository.save(User.builder()
                .email("inherit-owner@test.com").name("담당자 이영희")
                .hashedPassword(passwordEncoder.encode("pw"))
                .team("인사팀").role(UserRole.developer).permissionEvidence(true).build());
    }

    @Test
    @Order(1)
    @DisplayName("[Basic] 통제 2/증빙 3/작업 2 가 정확히 복제되고 parent 기록됨")
    @Transactional   // ← v14 fix-2 추가: lazy chain 접근 보장
    void testBasicInheritance() throws Exception {
        Framework source = frameworkRepository.save(Framework.builder()
                .name("ISMS-P 2025").description("기존 감사 주기").build());

        // v14 Phase 5-14f: 패턴 A — 평면 leaf depth=1
        ControlNode c1 = controlNodeRepository.save(ControlNode.builder()
                .framework(source).parent(null).nodeType(NodeType.control)
                .code("1.1.1").name("정책 수립")
                .displayOrder(0).depth(1).build());
        ControlNode c2 = controlNodeRepository.save(ControlNode.builder()
                .framework(source).parent(null).nodeType(NodeType.control)
                .code("1.2.1").name("접근통제")
                .displayOrder(1).depth(1).build());

        LocalDate dueDate = LocalDate.of(2026, 6, 30);
        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(c1).name("정보보호 정책서").description("경영진 승인")
                .ownerUser(ownerUser).dueDate(dueDate).build());
        EvidenceType et2 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(c1).name("처리방침").ownerUser(ownerUser).build());
        EvidenceType et3 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(c2).name("접근권한 목록").dueDate(LocalDate.of(2026, 9, 30)).build());

        collectionJobRepository.save(CollectionJob.builder()
                .name("접근권한 추출").jobType(JobType.excel_extract)
                .scriptPath("/scripts/access.py").evidenceType(et3)
                .scheduleCron("0 0 18 * * ?").isActive(true).build());
        collectionJobRepository.save(CollectionJob.builder()
                .name("정책 점검").jobType(JobType.web_scraping)
                .scriptPath("/scripts/policy.py").evidenceType(et1)
                .isActive(false).build());

        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et1).fileName("old.pdf").filePath("/tmp/old.pdf")
                .fileSize(1L).version(1).collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now()).build());

        var body = objectMapper.writeValueAsString(new FrameworkDto.InheritRequest(
                source.getId(), "ISMS-P 2026", "새 감사 주기"));

        mockMvc.perform(post("/api/v1/frameworks/inherit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("ISMS-P 2026"))
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.parentFrameworkId").value(source.getId()))
                .andExpect(jsonPath("$.data.parentFrameworkName").value("ISMS-P 2025"))
                .andExpect(jsonPath("$.data.controlCount").value(2))
                .andExpect(jsonPath("$.data.evidenceTypeCount").value(3))
                .andExpect(jsonPath("$.data.jobCount").value(2))
                .andExpect(jsonPath("$.data.pendingReviewCount").value(0));

        Framework newFw = frameworkRepository.findAll().stream()
                .filter(f -> !f.getId().equals(source.getId()))
                .findFirst().orElseThrow();

        // v14 Phase 5-14f: legacy controls 0건 (5-14b INSERT 차단), control_nodes leaf 검증
        List<ControlNode> newControls = controlNodeRepository
                .findByFrameworkIdAndNodeTypeOrderByDisplayOrderAsc(newFw.getId(), NodeType.control);
        assertThat(newControls).hasSize(2);
        // displayOrder ASC 정렬이라 c1 (displayOrder=0) 이 첫 번째
        assertThat(newControls.get(0).getCode()).isEqualTo("1.1.1");
        // domain 검증 — 5-14f 후 leaf 의 depth=1 ancestor name (평면 leaf 라 ancestor 없음 → null)
        // 검증 의도 보존: 새 leaf id 가 source 의 c1.id 와 다름
        assertThat(newControls.get(0).getId()).isNotEqualTo(c1.getId());

        List<EvidenceType> newTypesC1 = evidenceTypeRepository.findByControlNodeId(newControls.get(0).getId());
        assertThat(newTypesC1).hasSize(2);
        EvidenceType copiedEt1 = newTypesC1.stream()
                .filter(e -> "정보보호 정책서".equals(e.getName())).findFirst().orElseThrow();
        assertThat(copiedEt1.getOwnerUser().getId()).isEqualTo(ownerUser.getId());
        assertThat(copiedEt1.getDueDate()).isEqualTo(dueDate);
        assertThat(copiedEt1.getDescription()).isEqualTo("경영진 승인");

        List<CollectionJob> allJobs = collectionJobRepository.findAll();
        List<CollectionJob> newJobs = allJobs.stream()
                .filter(j -> j.getEvidenceType() != null
                        && j.getEvidenceType().getControlNode().getFramework().getId().equals(newFw.getId()))
                .toList();
        assertThat(newJobs).hasSize(2);
        long originalJobs = allJobs.stream()
                .filter(j -> j.getEvidenceType() != null
                        && j.getEvidenceType().getControlNode().getFramework().getId().equals(source.getId()))
                .count();
        assertThat(originalJobs).isEqualTo(2);

        long newFileCount = evidenceFileRepository.findAll().stream()
                .filter(f -> f.getEvidenceType().getControlNode().getFramework().getId().equals(newFw.getId()))
                .count();
        assertThat(newFileCount).isZero();

        System.out.println("✅ [Basic] 통제·증빙유형·수집작업 복제 정확 / 파일 제외 / parent 기록");
    }

    @Test
    @Order(2)
    @DisplayName("[Isolation] 상속 후 원본 Framework 의 통제/증빙유형/작업 수 그대로")
    void testSourceIsolation() throws Exception {
        Framework source = frameworkRepository.save(Framework.builder().name("원본 FW").build());
        // v14 Phase 5-14f: 패턴 A
        ControlNode c1 = controlNodeRepository.save(ControlNode.builder()
                .framework(source).parent(null).nodeType(NodeType.control)
                .code("S-1").name("통제 A")
                .displayOrder(0).depth(1).build());
        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(c1).name("증빙 A").build());
        collectionJobRepository.save(CollectionJob.builder()
                .name("작업 A").jobType(JobType.log_extract)
                .scriptPath("/s.sh").evidenceType(et1).build());

        var body = objectMapper.writeValueAsString(new FrameworkDto.InheritRequest(
                source.getId(), "복제본 FW", null));
        mockMvc.perform(post("/api/v1/frameworks/inherit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // v14 Phase 5-14f: ControlNode 위임으로 변경
        List<ControlNode> sourceControlsAfter = controlNodeRepository
                .findByFrameworkIdAndNodeTypeOrderByDisplayOrderAsc(source.getId(), NodeType.control);
        assertThat(sourceControlsAfter).hasSize(1);
        assertThat(sourceControlsAfter.get(0).getId()).isEqualTo(c1.getId());

        List<EvidenceType> sourceTypesAfter = evidenceTypeRepository.findByControlNodeId(c1.getId());
        assertThat(sourceTypesAfter).hasSize(1);
        assertThat(sourceTypesAfter.get(0).getId()).isEqualTo(et1.getId());

        assertThat(frameworkRepository.count()).isEqualTo(2);

        System.out.println("✅ [Isolation] 원본 Framework 데이터 변경 없음");
    }

    @Test
    @Order(3)
    @DisplayName("[Global Job] evidence_type=null 전역 작업은 복제되지 않음")
    void testGlobalJobsNotCopied() throws Exception {
        Framework source = frameworkRepository.save(Framework.builder().name("원본").build());
        // v14 Phase 5-14f: 패턴 A
        ControlNode c = controlNodeRepository.save(ControlNode.builder()
                .framework(source).parent(null).nodeType(NodeType.control)
                .code("G-1").name("통제")
                .displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(c).name("증빙").build());

        collectionJobRepository.save(CollectionJob.builder()
                .name("연결 작업").jobType(JobType.excel_extract)
                .scriptPath("/linked.py").evidenceType(et).build());
        collectionJobRepository.save(CollectionJob.builder()
                .name("전역 작업").jobType(JobType.log_extract)
                .scriptPath("/global.sh")
                .build());

        var body = objectMapper.writeValueAsString(new FrameworkDto.InheritRequest(
                source.getId(), "복제본", null));
        mockMvc.perform(post("/api/v1/frameworks/inherit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobCount").value(1));

        assertThat(collectionJobRepository.count()).isEqualTo(3);
        long globalJobs = collectionJobRepository.findAll().stream()
                .filter(j -> j.getEvidenceType() == null).count();
        assertThat(globalJobs).isEqualTo(1);

        System.out.println("✅ [Global Job] 전역 작업 복제 제외");
    }

    @Test
    @Order(4)
    @DisplayName("[Archived Source] archived 원본 상속 → 결과 Framework 는 active")
    void testInheritFromArchived() throws Exception {
        Framework source = frameworkRepository.save(Framework.builder().name("종료된 FW").build());
        source.archive();
        frameworkRepository.save(source);

        var body = objectMapper.writeValueAsString(new FrameworkDto.InheritRequest(
                source.getId(), "재개 FW", null));
        mockMvc.perform(post("/api/v1/frameworks/inherit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.parentFrameworkName").value("종료된 FW"));

        System.out.println("✅ [Archived Source] archived 에서 상속 가능 / 결과 active");
    }

    @Test
    @Order(5)
    @DisplayName("[Not Found] 존재하지 않는 sourceFrameworkId → 404")
    void testSourceNotFound() throws Exception {
        var body = objectMapper.writeValueAsString(new FrameworkDto.InheritRequest(
                9999L, "실패 FW", null));
        mockMvc.perform(post("/api/v1/frameworks/inherit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        System.out.println("✅ [Not Found] 존재하지 않는 원본 → 404");
    }

    @Test
    @Order(6)
    @DisplayName("[Validation] name blank → 400")
    void testValidationNameBlank() throws Exception {
        Framework source = frameworkRepository.save(Framework.builder().name("원본").build());
        var body = objectMapper.writeValueAsString(new FrameworkDto.InheritRequest(
                source.getId(), "", null));
        mockMvc.perform(post("/api/v1/frameworks/inherit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        System.out.println("✅ [Validation] name blank → 400");
    }

    @Test
    @Order(7)
    @DisplayName("[Auth] developer → 403 (admin 전용)")
    void testDeveloperForbidden() throws Exception {
        Framework source = frameworkRepository.save(Framework.builder().name("원본").build());
        var body = objectMapper.writeValueAsString(new FrameworkDto.InheritRequest(
                source.getId(), "시도", null));
        mockMvc.perform(post("/api/v1/frameworks/inherit")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        System.out.println("✅ [Auth] developer 403 / admin 전용 유지");
    }

    // ------------------------------------------------------------------
    // v19.19 — 스크립트 복제 (회귀 가드)
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("[Script Clone] script FK 작업 복제 시 새 Script/새 .py 독립 생성")
    @Transactional   // lazy script 접근 + 같은 트랜잭션 내 검증
    void testScriptClonedIndependently() throws Exception {
        Framework source = frameworkRepository.save(Framework.builder().name("스크립트 원본").build());
        ControlNode c = controlNodeRepository.save(ControlNode.builder()
                .framework(source).parent(null).nodeType(NodeType.control)
                .code("SC-1").name("통제").displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(c).name("증빙").build());

        // 원본 스크립트 — 실제 .py 파일 + Script entity (신규 방식)
        String content = "print('hello secuhub')\n";
        String srcFilename = UUID.randomUUID() + ".py";
        Files.writeString(TEMP_SCRIPTS_DIR.resolve(srcFilename), content, StandardCharsets.UTF_8);
        Script srcScript = scriptRepository.save(Script.builder()
                .filePath(srcFilename)
                .contentSize((long) content.getBytes(StandardCharsets.UTF_8).length)
                .build());

        collectionJobRepository.save(CollectionJob.builder()
                .name("스크립트 작업").jobType(JobType.web_scraping)
                .script(srcScript).evidenceType(et).build());

        var body = objectMapper.writeValueAsString(new FrameworkDto.InheritRequest(
                source.getId(), "스크립트 복제본", null));
        mockMvc.perform(post("/api/v1/frameworks/inherit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobCount").value(1));

        Framework newFw = frameworkRepository.findAll().stream()
                .filter(f -> !f.getId().equals(source.getId()))
                .findFirst().orElseThrow();
        CollectionJob clonedJob = collectionJobRepository.findAll().stream()
                .filter(j -> j.getEvidenceType() != null
                        && j.getEvidenceType().getControlNode().getFramework().getId().equals(newFw.getId()))
                .findFirst().orElseThrow();

        // 1) script FK 가 복제됨(null 아님) + 원본과 다른 Script
        assertThat(clonedJob.getScript()).isNotNull();
        assertThat(clonedJob.getScript().getId()).isNotEqualTo(srcScript.getId());
        // 2) legacy scriptPath 는 사용 안 함
        assertThat(clonedJob.getScriptPath()).isNull();
        // 3) 새 .py 파일이 실제로 생성됨 + 파일명(uuid) 도 원본과 다름 + 내용 동일
        String clonedFilename = clonedJob.getScript().getFilePath();
        assertThat(clonedFilename).isNotEqualTo(srcFilename);
        Path clonedFile = TEMP_SCRIPTS_DIR.resolve(clonedFilename);
        assertThat(Files.exists(clonedFile)).isTrue();
        assertThat(Files.readString(clonedFile, StandardCharsets.UTF_8)).isEqualTo(content);
        // 4) 원본 파일/Script 는 그대로 (격리)
        assertThat(Files.exists(TEMP_SCRIPTS_DIR.resolve(srcFilename))).isTrue();

        System.out.println("✅ [Script Clone] script FK 독립 복제 / 새 uuid.py 생성 / 내용 동일");
    }

    @Test
    @Order(9)
    @DisplayName("[Legacy Script] scriptPath-only 작업은 경로 유지 / script=null")
    @Transactional
    void testLegacyScriptPathRetained() throws Exception {
        Framework source = frameworkRepository.save(Framework.builder().name("legacy 원본").build());
        ControlNode c = controlNodeRepository.save(ControlNode.builder()
                .framework(source).parent(null).nodeType(NodeType.control)
                .code("LG-1").name("통제").displayOrder(0).depth(1).build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(c).name("증빙").build());
        collectionJobRepository.save(CollectionJob.builder()
                .name("legacy 작업").jobType(JobType.excel_extract)
                .scriptPath("/scripts/legacy.py").evidenceType(et).build());

        var body = objectMapper.writeValueAsString(new FrameworkDto.InheritRequest(
                source.getId(), "legacy 복제본", null));
        mockMvc.perform(post("/api/v1/frameworks/inherit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobCount").value(1));

        Framework newFw = frameworkRepository.findAll().stream()
                .filter(f -> !f.getId().equals(source.getId()))
                .findFirst().orElseThrow();
        CollectionJob clonedJob = collectionJobRepository.findAll().stream()
                .filter(j -> j.getEvidenceType() != null
                        && j.getEvidenceType().getControlNode().getFramework().getId().equals(newFw.getId()))
                .findFirst().orElseThrow();

        // legacy 작업은 script=null 이고 scriptPath 문자열만 그대로 유지
        assertThat(clonedJob.getScript()).isNull();
        assertThat(clonedJob.getScriptPath()).isEqualTo("/scripts/legacy.py");

        System.out.println("✅ [Legacy Script] scriptPath 유지 / script=null");
    }
}