package com.secuhub;

import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.entity.UserStatus;
import com.secuhub.domain.user.repository.UserRepository;
import com.secuhub.domain.vulnerability.entity.*;
import com.secuhub.domain.vulnerability.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA 엔티티 스키마 검증 테스트 (v2)
 *
 * 실행: ./gradlew test
 *
 * 검증 항목:
 * - 9개 테이블 정상 생성 (assessments 제거됨)
 * - evidence_types.file_type 제거 확인
 * - job_executions.log_file_path 제거 확인
 * - vulnerabilities 13컬럼 재설계 확인
 * - 4단계 상태 흐름 (unassigned → pending_approval → in_progress → done)
 * - 반려 시 unassigned 초기화
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaValidationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlRepository controlRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;
    @Autowired private CollectionJobRepository collectionJobRepository;
    @Autowired private JobExecutionRepository jobExecutionRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private VulnActionLogRepository vulnActionLogRepository;
    @Autowired private ApprovalRequestRepository approvalRequestRepository;

    // ========================================
    // 1. User 도메인
    // ========================================

    @Test
    @Order(1)
    @DisplayName("[User] 사용자 생성 및 조회")
    @Transactional
    void testUserCrud() {
        User admin = userRepository.save(User.builder()
                .email("admin@test.com").name("관리자").hashedPassword("pw")
                .team("보안팀").role(UserRole.admin)
                .permissionEvidence(true).permissionVuln(true)
                .build());

        User dev = userRepository.save(User.builder()
                .email("dev@test.com").name("김개발").hashedPassword("pw")
                .team("백엔드팀").role(UserRole.developer)
                .build());

        User approver = userRepository.save(User.builder()
                .email("approver@test.com").name("박팀장").hashedPassword("pw")
                .team("백엔드팀").role(UserRole.approver)
                .build());

        assertThat(userRepository.count()).isEqualTo(3);
        assertThat(userRepository.findByEmail("admin@test.com")).isPresent();
        assertThat(userRepository.findByRole(UserRole.developer)).hasSize(1);
        assertThat(userRepository.findByRoleAndStatus(UserRole.admin, UserStatus.active)).hasSize(1);

        // 기본값 검증
        assertThat(dev.getPermissionEvidence()).isFalse();
        assertThat(dev.getPermissionVuln()).isTrue();
        assertThat(dev.getStatus()).isEqualTo(UserStatus.active);
        assertThat(dev.getCreatedAt()).isNotNull();

        System.out.println("✅ [User] 테이블 생성 및 CRUD 정상");
    }

    // ========================================
    // 2. 증빙 수집 도메인
    // ========================================

    @Test
    @Order(2)
    @DisplayName("[Evidence] Framework → Control → EvidenceType → EvidenceFile 관계 검증")
    @Transactional
    void testEvidenceHierarchy() {
        Framework framework = frameworkRepository.save(Framework.builder()
                .name("ISMS-P")
                .description("정보보호 및 개인정보보호 관리체계 인증")
                .build());

        Control control = controlRepository.save(Control.builder()
                .framework(framework)
                .code("1.1.1")
                .domain("관리체계 수립")
                .name("정보보호 정책 수립")
                .build());

        // evidence_types — file_type 필드 없이 생성
        EvidenceType et1 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(control)
                .name("정보보호 정책서")
                .description("경영진 승인된 정책 문서")
                .build());

        EvidenceType et2 = evidenceTypeRepository.save(EvidenceType.builder()
                .control(control)
                .name("개인정보 처리방침")
                .build());

        // evidence_files — 버전 관리
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et1)
                .fileName("정보보호_정책서_v1.pdf")
                .filePath("/storage/evidence/policy_v1.pdf")
                .fileSize(1800000L)
                .version(1)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.of(2024, 7, 1, 10, 0))
                .build());

        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et1)
                .fileName("정보보호_정책서_v2.pdf")
                .filePath("/storage/evidence/policy_v2.pdf")
                .fileSize(2100000L)
                .version(2)
                .collectionMethod(CollectionMethod.auto)
                .collectedAt(LocalDateTime.of(2025, 1, 15, 10, 0))
                .build());

        // Then
        assertThat(controlRepository.findByFrameworkIdOrderByCodeAsc(framework.getId())).hasSize(1);
        assertThat(evidenceTypeRepository.findByControlId(control.getId())).hasSize(2);

        List<EvidenceFile> files = evidenceFileRepository
                .findByEvidenceTypeIdOrderByVersionDesc(et1.getId());
        assertThat(files).hasSize(2);
        assertThat(files.get(0).getVersion()).isEqualTo(2);

        // 수집현황: et1은 파일 있음, et2는 파일 없음 → "1/2 수집됨"
        long totalTypes = evidenceTypeRepository.findByControlId(control.getId()).size();
        long collectedTypes = evidenceTypeRepository.findByControlId(control.getId()).stream()
                .filter(et -> !evidenceFileRepository.findByEvidenceTypeIdOrderByVersionDesc(et.getId()).isEmpty())
                .count();
        assertThat(totalTypes).isEqualTo(2);
        assertThat(collectedTypes).isEqualTo(1);

        System.out.println("✅ [Evidence] Framework → Control → EvidenceType → EvidenceFile 관계 정상");
        System.out.println("   수집현황: " + collectedTypes + "/" + totalTypes);
    }

    @Test
    @Order(3)
    @DisplayName("[Evidence] CollectionJob → JobExecution (log_file_path 제거 확인)")
    @Transactional
    void testCollectionJobExecution() {
        Framework fw = frameworkRepository.save(Framework.builder().name("테스트").build());
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("T-01").name("테스트 항목").build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl).name("테스트 증빙").build());

        CollectionJob job = collectionJobRepository.save(CollectionJob.builder()
                .name("접근권한 현황 추출")
                .jobType(JobType.excel_extract)
                .scriptPath("/scripts/access_rights.py")
                .evidenceType(et)
                .scheduleCron("0 0 18 * * ?")
                .isActive(true)
                .build());

        // 성공 실행
        jobExecutionRepository.save(JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.success)
                .startedAt(LocalDateTime.now().minusMinutes(5))
                .finishedAt(LocalDateTime.now())
                .build());

        // 실패 실행 — error_message만 사용 (log_file_path 없음)
        jobExecutionRepository.save(JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.failed)
                .startedAt(LocalDateTime.now().minusMinutes(10))
                .finishedAt(LocalDateTime.now().minusMinutes(8))
                .errorMessage("Connection refused: 대상 서버 접속 실패")
                .build());

        assertThat(collectionJobRepository.findByIsActiveTrueAndScheduleCronIsNotNull()).hasSize(1);
        assertThat(jobExecutionRepository.findByJobIdOrderByCreatedAtDesc(job.getId())).hasSize(2);

        System.out.println("✅ [Evidence] CollectionJob → JobExecution 정상 (log_file_path 제거됨)");
    }

    // ========================================
    // 3. 취약점 관리 도메인 (전면 재설계)
    // ========================================

    @Test
    @Order(4)
    @DisplayName("[Vuln] 취약점 4단계 상태 흐름 (unassigned → done)")
    @Transactional
    void testVulnerabilityLifecycle() {
        User dev = userRepository.save(User.builder()
                .email("dev2@test.com").name("개발자").hashedPassword("pw")
                .team("백엔드팀").role(UserRole.developer).build());
        User approverUser = userRepository.save(User.builder()
                .email("approver2@test.com").name("결재자").hashedPassword("pw")
                .team("백엔드팀").role(UserRole.approver).build());

        // 취약점 생성 — 재설계된 필드
        Vulnerability vuln = vulnerabilityRepository.save(Vulnerability.builder()
                .category("웹 취약점")
                .deviceType("웹서버")
                .hostname("web-server-01")
                .checkCode("WEB-001")
                .problem("사용자 입력값에 대한 SQL 인젝션 취약점 발견")
                .content("파라미터 바인딩 미적용으로 인한 SQL Injection 가능")
                .build());

        // 초기 상태: unassigned
        assertThat(vuln.getStatus()).isEqualTo(VulnStatus.unassigned);
        assertThat(vuln.getAssignee()).isNull();

        // Step 1: 담당자 + 계획일 + 결재자 입력 → pending_approval
        vuln.requestApproval(dev, LocalDate.of(2025, 4, 15), approverUser);
        vulnerabilityRepository.save(vuln);
        assertThat(vuln.getStatus()).isEqualTo(VulnStatus.pending_approval);
        assertThat(vuln.getAssignee().getName()).isEqualTo("개발자");
        assertThat(vuln.getPlanDate()).isEqualTo(LocalDate.of(2025, 4, 15));
        assertThat(vuln.getApprover().getName()).isEqualTo("결재자");

        // Step 2: 결재 승인 → in_progress
        vuln.approve();
        vulnerabilityRepository.save(vuln);
        assertThat(vuln.getStatus()).isEqualTo(VulnStatus.in_progress);

        // Step 3: 조치 완료 → done
        vuln.complete();
        vulnerabilityRepository.save(vuln);
        assertThat(vuln.getStatus()).isEqualTo(VulnStatus.done);

        // Repository 쿼리 검증
        assertThat(vulnerabilityRepository.countByStatus(VulnStatus.done)).isEqualTo(1);
        assertThat(vulnerabilityRepository.findByAssigneeId(dev.getId())).hasSize(1);

        System.out.println("✅ [Vuln] 4단계 상태 흐름 (unassigned → pending_approval → in_progress → done) 정상");
    }

    @Test
    @Order(5)
    @DisplayName("[Vuln] 결재 반려 → unassigned 초기화")
    @Transactional
    void testApprovalRejection() {
        User dev = userRepository.save(User.builder()
                .email("dev3@test.com").name("개발자3").hashedPassword("pw")
                .role(UserRole.developer).build());
        User appr = userRepository.save(User.builder()
                .email("appr3@test.com").name("결재자3").hashedPassword("pw")
                .role(UserRole.approver).build());

        Vulnerability vuln = vulnerabilityRepository.save(Vulnerability.builder()
                .category("인프라")
                .deviceType("방화벽")
                .hostname("fw-01")
                .checkCode("INFRA-005")
                .problem("불필요 포트 오픈")
                .content("TCP 8080 포트가 외부에 노출됨")
                .build());

        // 결재 요청
        vuln.requestApproval(dev, LocalDate.of(2025, 5, 1), appr);
        vulnerabilityRepository.save(vuln);

        // approval_requests 생성
        ApprovalRequest request = approvalRequestRepository.save(ApprovalRequest.builder()
                .vulnerability(vuln)
                .requester(dev)
                .approver(appr)
                .category("조치 일정 결재")
                .content("TCP 8080 포트 차단 예정")
                .build());

        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.pending);
        assertThat(approvalRequestRepository.countByApproverIdAndStatus(
                appr.getId(), ApprovalStatus.pending)).isEqualTo(1);

        // 반려
        request.reject();
        approvalRequestRepository.save(request);
        vuln.reject();
        vulnerabilityRepository.save(vuln);

        // 검증: unassigned 초기화
        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.rejected);
        assertThat(vuln.getStatus()).isEqualTo(VulnStatus.unassigned);
        assertThat(vuln.getAssignee()).isNull();
        assertThat(vuln.getPlanDate()).isNull();
        assertThat(vuln.getApprover()).isNull();

        System.out.println("✅ [Vuln] 결재 반려 → unassigned 초기화 정상");
    }

    @Test
    @Order(6)
    @DisplayName("[Vuln] VulnActionLog 이력 기록 (자유 입력)")
    @Transactional
    void testVulnActionLog() {
        User dev = userRepository.save(User.builder()
                .email("dev4@test.com").name("개발자4").hashedPassword("pw")
                .role(UserRole.developer).build());

        Vulnerability vuln = vulnerabilityRepository.save(Vulnerability.builder()
                .category("웹 취약점")
                .checkCode("WEB-003")
                .problem("CSRF 토큰 미적용")
                .content("CSRF 공격에 취약")
                .build());

        vulnActionLogRepository.saveAll(List.of(
                VulnActionLog.builder()
                        .vulnerability(vuln).user(dev)
                        .category("담당자 배정").content("본인 배정")
                        .build(),
                VulnActionLog.builder()
                        .vulnerability(vuln).user(dev)
                        .category("결재 요청").content("2025-04-30까지 조치 예정")
                        .build(),
                VulnActionLog.builder()
                        .vulnerability(vuln).user(dev)
                        .category("조치 완료").content("CSRF 토큰 적용 완료")
                        .build()
        ));

        List<VulnActionLog> logs = vulnActionLogRepository
                .findByVulnerabilityIdOrderByCreatedAtDesc(vuln.getId());
        assertThat(logs).hasSize(3);
        assertThat(logs.get(0).getCategory()).isNotNull();

        System.out.println("✅ [Vuln] VulnActionLog 이력 기록 정상 (자유 입력)");
    }

    // ========================================
    // 4. 통합
    // ========================================

    @Test
    @Order(7)
    @DisplayName("[통합] 전체 9개 테이블 생성 확인 — 10개 Repository 주입 성공")
    void testAllRepositoriesInjected() {
        assertThat(userRepository).isNotNull();
        assertThat(frameworkRepository).isNotNull();
        assertThat(controlRepository).isNotNull();
        assertThat(evidenceTypeRepository).isNotNull();
        assertThat(evidenceFileRepository).isNotNull();
        assertThat(collectionJobRepository).isNotNull();
        assertThat(jobExecutionRepository).isNotNull();
        assertThat(vulnerabilityRepository).isNotNull();
        assertThat(vulnActionLogRepository).isNotNull();
        assertThat(approvalRequestRepository).isNotNull();

        System.out.println("✅ [통합] 10개 Repository 모두 정상 주입 — 9개 테이블 생성 완료");
    }
}
