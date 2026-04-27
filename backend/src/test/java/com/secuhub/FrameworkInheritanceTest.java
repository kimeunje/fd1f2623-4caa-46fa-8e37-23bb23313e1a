package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.dto.FrameworkDto;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
    @Autowired private ControlRepository controlRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;
    @Autowired private CollectionJobRepository collectionJobRepository;

    private String adminToken;
    private String developerToken;
    private User ownerUser;

    @BeforeEach
    void setUp() {
        evidenceFileRepository.deleteAll();
        collectionJobRepository.deleteAll();
        evidenceTypeRepository.deleteAll();
        controlRepository.deleteAll();
        frameworkRepository.deleteAll();
        userRepository.deleteAll();

        User admin = userRepository.save(User.builder()
                .email("inherit-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin).permissionEvidence(true).permissionVuln(true).build());
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

        Control c1 = controlRepository.save(Control.builder()
                .framework(source).code("1.1.1").domain("관리체계").name("정책 수립").build());
        Control c2 = controlRepository.save(Control.builder()
                .framework(source).code("1.2.1").domain("보호대책").name("접근통제").build());

        LocalDate dueDate = LocalDate.of(2026, 6, 30);
        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(c1).name("정보보호 정책서").description("경영진 승인")
                .ownerUser(ownerUser).dueDate(dueDate).build());
        EvidenceType et2 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(c1).name("처리방침").ownerUser(ownerUser).build());
        EvidenceType et3 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(c2).name("접근권한 목록").dueDate(LocalDate.of(2026, 9, 30)).build());

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

        List<Control> newControls = controlRepository.findByFrameworkIdOrderByCodeAsc(newFw.getId());
        assertThat(newControls).hasSize(2);
        assertThat(newControls.get(0).getCode()).isEqualTo("1.1.1");
        assertThat(newControls.get(0).getDomain()).isEqualTo("관리체계");
        assertThat(newControls.get(0).getId()).isNotEqualTo(c1.getId());

        List<EvidenceType> newTypesC1 = evidenceTypeRepository.findByControlId(newControls.get(0).getId());
        assertThat(newTypesC1).hasSize(2);
        EvidenceType copiedEt1 = newTypesC1.stream()
                .filter(e -> "정보보호 정책서".equals(e.getName())).findFirst().orElseThrow();
        assertThat(copiedEt1.getOwnerUser().getId()).isEqualTo(ownerUser.getId());
        assertThat(copiedEt1.getDueDate()).isEqualTo(dueDate);
        assertThat(copiedEt1.getDescription()).isEqualTo("경영진 승인");

        List<CollectionJob> allJobs = collectionJobRepository.findAll();
        List<CollectionJob> newJobs = allJobs.stream()
                .filter(j -> j.getEvidenceType() != null
                        && j.getEvidenceType().getControl().getFramework().getId().equals(newFw.getId()))
                .toList();
        assertThat(newJobs).hasSize(2);
        long originalJobs = allJobs.stream()
                .filter(j -> j.getEvidenceType() != null
                        && j.getEvidenceType().getControl().getFramework().getId().equals(source.getId()))
                .count();
        assertThat(originalJobs).isEqualTo(2);

        long newFileCount = evidenceFileRepository.findAll().stream()
                .filter(f -> f.getEvidenceType().getControl().getFramework().getId().equals(newFw.getId()))
                .count();
        assertThat(newFileCount).isZero();

        System.out.println("✅ [Basic] 통제·증빙유형·수집작업 복제 정확 / 파일 제외 / parent 기록");
    }

    @Test
    @Order(2)
    @DisplayName("[Isolation] 상속 후 원본 Framework 의 통제/증빙유형/작업 수 그대로")
    void testSourceIsolation() throws Exception {
        Framework source = frameworkRepository.save(Framework.builder().name("원본 FW").build());
        Control c1 = controlRepository.save(Control.builder()
                .framework(source).code("S-1").name("통제 A").build());
        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(c1).name("증빙 A").build());
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

        List<Control> sourceControlsAfter = controlRepository.findByFrameworkIdOrderByCodeAsc(source.getId());
        assertThat(sourceControlsAfter).hasSize(1);
        assertThat(sourceControlsAfter.get(0).getId()).isEqualTo(c1.getId());

        List<EvidenceType> sourceTypesAfter = evidenceTypeRepository.findByControlId(c1.getId());
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
        Control c = controlRepository.save(Control.builder()
                .framework(source).code("G-1").name("통제").build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(c).name("증빙").build());

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
}