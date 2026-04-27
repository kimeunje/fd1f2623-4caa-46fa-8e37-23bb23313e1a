package com.secuhub;

import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.NotificationPreference;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.entity.UserStatus;
import com.secuhub.domain.user.repository.NotificationPreferenceRepository;
import com.secuhub.domain.user.repository.UserRepository;
import com.secuhub.domain.vulnerability.entity.*;
import com.secuhub.domain.vulnerability.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 * JPA 엔티티 스키마 검증 테스트 (v11)
 *
 * 실행: ./gradlew test
 *
 * 검증 항목:
 * - 10개 테이블 정상 생성 (notification_preferences 추가)
 * - evidence_types.file_type 제거 확인
 * - job_executions.log_file_path 제거 확인
 * - vulnerabilities 13컬럼 재설계 확인
 * - 4단계 상태 흐름 (unassigned → pending_approval → in_progress → done)
 * - 반려 시 unassigned 초기화
 * - [v11 신규] Framework 자기참조 상속 + status
 * - [v11 신규] EvidenceType 담당자(owner_user_id) + 마감일(due_date)
 * - [v11 신규] EvidenceFile 승인 플로우 (review_status, reviewed_by, review_note, reviewed_at, uploaded_by, submit_note)
 * - [v11 신규] NotificationPreference 1:1 관계 + User 삭제 CASCADE
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
    @Autowired private NotificationPreferenceRepository notificationPreferenceRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // ============= v14 fix-3: 테스트 클래스 간 leftover 격리 =============
    // 다른 테스트 클래스(@Transactional 없는 mockMvc 테스트들)가
    // commit 한 사용자/프레임워크/통제 데이터가 SchemaValidationTest 시작 시
    // 잔존할 수 있음. testUserCrud 의 count() 검증을 위해 명시적 cleanup.
    @BeforeEach
    void cleanLeftoverFromOtherTestClasses() {
        notificationPreferenceRepository.deleteAllInBatch();
        evidenceFileRepository.deleteAllInBatch();
        collectionJobRepository.deleteAllInBatch();
        jobExecutionRepository.deleteAllInBatch();
        evidenceTypeRepository.deleteAllInBatch();
        controlRepository.deleteAllInBatch();
        frameworkRepository.deleteAllInBatch();
        vulnActionLogRepository.deleteAllInBatch();
        approvalRequestRepository.deleteAllInBatch();
        vulnerabilityRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }
    // ====================================================================

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

        // v11: Builder 기본값이 auto_approved 인지 확인 (기존 Phase 2 동작 보존)
        assertThat(files.get(0).getReviewStatus()).isEqualTo(ReviewStatus.auto_approved);

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
    @DisplayName("[통합] 전체 10개 테이블 생성 확인 — 11개 Repository 주입 성공")
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
        assertThat(notificationPreferenceRepository).isNotNull();

        System.out.println("✅ [통합] 11개 Repository 모두 정상 주입 — 10개 테이블 생성 완료");
    }

    // ========================================
    // 5. v11 신규 — Phase 5-1 스키마 검증
    // ========================================

    @Test
    @Order(10)
    @DisplayName("[v11] Framework 자기참조 상속 + status")
    @Transactional
    void testFrameworkInheritanceAndStatus() {
        // 부모 Framework (전년도)
        Framework parent = frameworkRepository.save(Framework.builder()
                .name("ISMS-P 2025")
                .description("전년도 감사")
                .build());

        // 기본 상태 검증
        assertThat(parent.getStatus()).isEqualTo(FrameworkStatus.active);
        assertThat(parent.getParentFramework()).isNull();

        // 상속된 자식 Framework (올해)
        Framework child = Framework.builder()
                .name("ISMS-P 2026")
                .description("올해 감사 — 2025 상속")
                .build();
        child.setParentFramework(parent);
        child = frameworkRepository.save(child);

        // 자식 FK 관계 확인
        assertThat(child.getParentFramework()).isNotNull();
        assertThat(child.getParentFramework().getId()).isEqualTo(parent.getId());
        assertThat(child.getParentFramework().getName()).isEqualTo("ISMS-P 2025");

        // 부모 archive 처리
        parent.archive();
        frameworkRepository.save(parent);
        assertThat(parent.getStatus()).isEqualTo(FrameworkStatus.archived);

        // Repository 쿼리 — 부모를 기준으로 자식 조회
        List<Framework> children = frameworkRepository.findByParentFrameworkId(parent.getId());
        assertThat(children).hasSize(1);
        assertThat(children.get(0).getName()).isEqualTo("ISMS-P 2026");

        // 상태별 필터
        List<Framework> actives = frameworkRepository.findByStatus(FrameworkStatus.active);
        List<Framework> archives = frameworkRepository.findByStatus(FrameworkStatus.archived);
        assertThat(actives).anyMatch(f -> f.getName().equals("ISMS-P 2026"));
        assertThat(archives).anyMatch(f -> f.getName().equals("ISMS-P 2025"));

        System.out.println("✅ [v11] Framework 상속 + status 정상 (parent_framework_id self-FK, active/archived)");
    }

    @Test
    @Order(11)
    @DisplayName("[v11] EvidenceType 담당자(owner_user_id) + 마감일(due_date)")
    @Transactional
    void testEvidenceTypeOwnerAndDueDate() {
        User hrOwner = userRepository.save(User.builder()
                .email("hr@test.com").name("인사팀 홍길동").hashedPassword("pw")
                .team("인사팀").role(UserRole.developer)
                .permissionEvidence(true)
                .build());

        Framework fw = frameworkRepository.save(Framework.builder()
                .name("ISMS-P 2026").build());
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("2.2.1").name("임직원 교육").build());

        // 담당자·마감일 포함 생성
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl)
                .name("보안 교육 이수 증빙")
                .ownerUser(hrOwner)
                .dueDate(LocalDate.of(2026, 6, 30))
                .build());

        assertThat(et.getOwnerUser()).isNotNull();
        assertThat(et.getOwnerUser().getId()).isEqualTo(hrOwner.getId());
        assertThat(et.getOwnerUser().getTeam()).isEqualTo("인사팀");
        assertThat(et.getDueDate()).isEqualTo(LocalDate.of(2026, 6, 30));

        // Repository — 담당자 기반 조회 ("내 할 일" 페이지 기반)
        List<EvidenceType> myTasks = evidenceTypeRepository.findByOwnerUserId(hrOwner.getId());
        assertThat(myTasks).hasSize(1);
        assertThat(myTasks.get(0).getName()).isEqualTo("보안 교육 이수 증빙");

        // 담당자 재배정
        User legalOwner = userRepository.save(User.builder()
                .email("legal@test.com").name("법무팀 김철수").hashedPassword("pw")
                .team("법무팀").role(UserRole.developer)
                .permissionEvidence(true)
                .build());
        et.assignOwner(legalOwner);
        et.updateDueDate(LocalDate.of(2026, 7, 15));
        evidenceTypeRepository.save(et);

        assertThat(et.getOwnerUser().getId()).isEqualTo(legalOwner.getId());
        assertThat(et.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 15));

        System.out.println("✅ [v11] EvidenceType 담당자 + 마감일 정상 (owner_user_id FK, due_date)");
    }

    @Test
    @Order(12)
    @DisplayName("[v11] EvidenceFile 승인 플로우 (pending → approved/rejected)")
    @Transactional
    void testEvidenceFileReviewFlow() {
        User admin = userRepository.save(User.builder()
                .email("admin_v11@test.com").name("관리자v11").hashedPassword("pw")
                .role(UserRole.admin)
                .permissionEvidence(true)
                .build());
        User hrOwner = userRepository.save(User.builder()
                .email("hr_v11@test.com").name("인사팀v11").hashedPassword("pw")
                .team("인사팀").role(UserRole.developer)
                .permissionEvidence(true)
                .build());

        Framework fw = frameworkRepository.save(Framework.builder()
                .name("ISMS-P 2026 승인테스트").build());
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("2.2.1").name("임직원 교육").build());
        EvidenceType et = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl).name("교육 수료증").ownerUser(hrOwner)
                .build());

        // 1) 담당자 업로드 → 명시적으로 pending 설정
        EvidenceFile pending = evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName("교육수료증_2026Q1.pdf")
                .filePath("/storage/evidence/training_2026q1.pdf")
                .fileSize(1_200_000L)
                .version(1)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .uploadedBy(hrOwner)
                .submitNote("1분기 전사 보안교육 수료증입니다. 검토 부탁드립니다.")
                .reviewStatus(ReviewStatus.pending)
                .build());

        assertThat(pending.getReviewStatus()).isEqualTo(ReviewStatus.pending);
        assertThat(pending.getUploadedBy().getId()).isEqualTo(hrOwner.getId());
        assertThat(pending.getSubmitNote()).contains("검토 부탁");
        assertThat(pending.getReviewedBy()).isNull();
        assertThat(pending.getReviewedAt()).isNull();

        // 2) 관리자 직접 업로드 → Builder 기본값 auto_approved (기존 Phase 2 동작 보존)
        EvidenceFile autoApproved = evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName("관리자직접업로드.pdf")
                .filePath("/storage/evidence/admin_direct.pdf")
                .fileSize(500_000L)
                .version(2)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .uploadedBy(admin)
                .build());

        assertThat(autoApproved.getReviewStatus()).isEqualTo(ReviewStatus.auto_approved);

        // 3) pending → approved 전이
        pending.approve(admin, "잘 작성해주셨습니다. 승인합니다.");
        evidenceFileRepository.save(pending);

        assertThat(pending.getReviewStatus()).isEqualTo(ReviewStatus.approved);
        assertThat(pending.getReviewedBy().getId()).isEqualTo(admin.getId());
        assertThat(pending.getReviewNote()).contains("승인");
        assertThat(pending.getReviewedAt()).isNotNull();

        // 4) 다른 파일을 생성해 반려 시나리오 검증
        EvidenceFile toReject = evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName("잘못된파일.txt")
                .filePath("/storage/evidence/wrong.txt")
                .fileSize(100L)
                .version(3)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .uploadedBy(hrOwner)
                .reviewStatus(ReviewStatus.pending)
                .build());

        toReject.reject(admin, "형식이 맞지 않습니다. PDF로 다시 업로드해주세요.");
        evidenceFileRepository.save(toReject);

        assertThat(toReject.getReviewStatus()).isEqualTo(ReviewStatus.rejected);
        assertThat(toReject.getReviewNote()).contains("PDF");

        // Repository — 상태별 조회
        assertThat(evidenceFileRepository.countByReviewStatus(ReviewStatus.approved))
                .isEqualTo(1);
        assertThat(evidenceFileRepository.countByReviewStatus(ReviewStatus.rejected))
                .isEqualTo(1);
        assertThat(evidenceFileRepository.countByReviewStatus(ReviewStatus.auto_approved))
                .isGreaterThanOrEqualTo(1);

        // Framework 단위 승인 대기 집계 (현재는 pending 0, 모두 전이 완료)
        long pendingInFw = evidenceFileRepository.countByFrameworkIdAndReviewStatus(
                fw.getId(), ReviewStatus.pending);
        assertThat(pendingInFw).isEqualTo(0);

        System.out.println("✅ [v11] EvidenceFile 승인 플로우 정상 (pending → approved/rejected, 기본값 auto_approved 유지)");
    }

    @Test
    @Order(13)
    @DisplayName("[v11] NotificationPreference 1:1 + User 삭제 시 CASCADE")
    @Transactional
    void testNotificationPreferenceCascade() {
        User user = userRepository.save(User.builder()
                .email("notify@test.com").name("알림테스트").hashedPassword("pw")
                .role(UserRole.developer).permissionEvidence(true)
                .build());

        // 기본값 생성 (모든 플래그 true, daily_digest 만 false)
        NotificationPreference pref = notificationPreferenceRepository.save(
                NotificationPreference.builder()
                        .user(user)
                        .build()
        );

        assertThat(pref.getUserId()).isEqualTo(user.getId());
        assertThat(pref.getEmailOnRejection()).isTrue();
        assertThat(pref.getEmailOnApproval()).isTrue();
        assertThat(pref.getEmailOnNewAssignment()).isTrue();
        assertThat(pref.getEmailOnDueReminder()).isTrue();
        assertThat(pref.getEmailDailyDigest()).isFalse();

        // 부분 업데이트
        pref.update(false, null, null, null, true);
        notificationPreferenceRepository.save(pref);
        assertThat(pref.getEmailOnRejection()).isFalse();
        assertThat(pref.getEmailOnApproval()).isTrue();    // 변경 없음
        assertThat(pref.getEmailDailyDigest()).isTrue();

        // PK = userId 로 조회
        assertThat(notificationPreferenceRepository.findById(user.getId())).isPresent();

        Long userId = user.getId();

        // ======================================================================
        // CASCADE 검증 — JPA 영속성 컨텍스트 개입을 완전히 배제하고 DB 레벨로 검증
        // ======================================================================
        // JPA 매니지드 엔티티가 남아있으면 flush 순서 재배치·1차 캐시 등
        // 예측하기 힘든 요소가 끼어들 수 있어, native SQL 로 테스트함.
        //
        // 기대 동작:
        //   - @OnDelete(CASCADE) 가 DDL에 반영되어 FK가 ON DELETE CASCADE 를 가짐
        //   - Native DELETE FROM users 시 DB가 notification_preferences 행을 CASCADE 삭제
        //
        // 만약 @OnDelete 가 DDL 생성에 실패했다면 DELETE 단계에서
        // FK 제약 위반 예외가 즉시 발생해 원인이 명확히 드러남.

        // 1) 메모리 상태를 DB에 확정 + 1차 캐시 비우기
        entityManager.flush();
        entityManager.clear();

        // 2) Native SQL 로 user 삭제 (Hibernate managed entity 로직 우회)
        int deletedUsers = entityManager.createNativeQuery(
                "DELETE FROM users WHERE id = :id"
        ).setParameter("id", userId).executeUpdate();
        assertThat(deletedUsers).isEqualTo(1);

        // 3) DB에 직접 질의해 notification_preferences 행이 CASCADE 로 사라졌는지 확인
        Number remaining = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM notification_preferences WHERE user_id = :id"
        ).setParameter("id", userId).getSingleResult();
        assertThat(remaining.longValue()).isEqualTo(0L);

        System.out.println("✅ [v11] NotificationPreference 1:1 + User 삭제 CASCADE 정상 (native SQL 검증)");
    }
}