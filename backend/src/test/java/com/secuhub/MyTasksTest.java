package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.mytasks.service.MyTasksService;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 5-5 — "내 할 일" API 검증
 *
 * <h3>검증 매트릭스</h3>
 * <ul>
 *   <li>권한: permission_evidence=false → 403, 본인이 owner 가 아닌 증빙은 섹션에 나타나지 않음</li>
 *   <li>분류: 5섹션 (rejected / dueSoon / notSubmitted / inReview / completed) 정확성</li>
 *   <li>반려 사유가 rejected 섹션 Item 에 포함됨</li>
 *   <li>마감 임박 윈도우: D-0, D-7 포함 / D-8 은 미제출 섹션</li>
 *   <li>완료 섹션 10건 제한 + counts.completed 는 전체 카운트</li>
 *   <li>상세 API: 본인이 아닌 담당자의 증빙 → 403</li>
 *   <li>admin 이 permission_evidence 플래그 무관하게 접근 허용 (단 본인 owner 증빙만)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-5 — 내 할 일 API")
class MyTasksTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlRepository controlRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;
    @Autowired private MyTasksService myTasksService;

    // 고정된 테스트 '오늘' — 모든 dueDate 계산은 이 값 기준
    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 4, 23);

    private User ownerWithPerm;       // permission_evidence=true, 담당
    private User ownerWithoutPerm;    // permission_evidence=false → 403
    private User otherOwner;           // 다른 담당자 (격리 확인용)
    private User admin;                // admin 역할
    private User nonOwnerDeveloper;    // developer 이지만 owner 로 지정되지 않음

    private String ownerToken;
    private String ownerWithoutPermToken;
    private String otherOwnerToken;
    private String adminToken;
    private String nonOwnerDeveloperToken;

    private Framework framework;
    private Control control;

    @BeforeEach
    void setUp() {
        evidenceFileRepository.deleteAll();
        evidenceTypeRepository.deleteAll();
        controlRepository.deleteAll();
        frameworkRepository.deleteAll();
        userRepository.deleteAll();

        // 서비스 시간 고정 (테스트 결정성)
        myTasksService.setClock(Clock.fixed(
                FIXED_TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()));

        ownerWithPerm = userRepository.save(User.builder()
                .email("mt-owner@test.com").name("담당자 홍길동")
                .hashedPassword(passwordEncoder.encode("pw"))
                .team("인사팀").role(UserRole.developer)
                .permissionEvidence(true).permissionVuln(true).build());

        ownerWithoutPerm = userRepository.save(User.builder()
                .email("mt-noev@test.com").name("권한없는담당자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .team("인사팀").role(UserRole.developer)
                .permissionEvidence(false).build());

        otherOwner = userRepository.save(User.builder()
                .email("mt-other@test.com").name("다른담당자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .team("개발팀").role(UserRole.developer)
                .permissionEvidence(true).build());

        admin = userRepository.save(User.builder()
                .email("mt-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin).permissionEvidence(true).permissionVuln(true).build());

        nonOwnerDeveloper = userRepository.save(User.builder()
                .email("mt-nonowner@test.com").name("담당배정안된개발자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.developer).permissionEvidence(true).build());

        ownerToken = jwtTokenProvider.createToken(ownerWithPerm.getId(), ownerWithPerm.getEmail(), "developer");
        ownerWithoutPermToken = jwtTokenProvider.createToken(ownerWithoutPerm.getId(), ownerWithoutPerm.getEmail(), "developer");
        otherOwnerToken = jwtTokenProvider.createToken(otherOwner.getId(), otherOwner.getEmail(), "developer");
        adminToken = jwtTokenProvider.createToken(admin.getId(), admin.getEmail(), "admin");
        nonOwnerDeveloperToken = jwtTokenProvider.createToken(nonOwnerDeveloper.getId(), nonOwnerDeveloper.getEmail(), "developer");

        framework = frameworkRepository.save(Framework.builder().name("ISMS-P 2026").build());
        control = controlRepository.save(Control.builder()
                .framework(framework).code("1.1.1").domain("관리체계").name("정보보호 정책 수립").build());
    }

    // ==================================================================
    // 1. 권한: permission_evidence=false → 403
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("[Auth] permission_evidence=false 담당자 → GET /my-tasks 403")
    void testNoPermissionIsForbidden() throws Exception {
        // owner 로 지정돼있어도 permission_evidence=false 면 차단
        EvidenceType et = saveType("권한없는담당_증빙", ownerWithoutPerm, null);

        mockMvc.perform(get("/api/v1/my-tasks")
                        .header("Authorization", "Bearer " + ownerWithoutPermToken))
                .andExpect(status().isForbidden());

        System.out.println("✅ [Auth] permission_evidence=false → 403");
    }

    // ==================================================================
    // 2. 소유 격리: 다른 담당자의 증빙은 내 응답에 포함되지 않음
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("[Isolation] 다른 담당자 소유 증빙은 내 응답에 없음")
    void testOnlyOwnedEvidenceTypesAppear() throws Exception {
        saveType("내_증빙_A", ownerWithPerm, null);
        saveType("내_증빙_B", ownerWithPerm, null);
        saveType("남의_증빙", otherOwner, null);

        mockMvc.perform(get("/api/v1/my-tasks")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.notSubmitted").value(2))
                .andExpect(jsonPath("$.data.notSubmitted[?(@.evidenceTypeName == '남의_증빙')]")
                        .doesNotExist());

        System.out.println("✅ [Isolation] 본인 소유 증빙만 응답");
    }

    // ==================================================================
    // 3. 비담당자: owner 로 전혀 지정되지 않은 사용자 → 빈 응답
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("[Empty] owner 지정 전혀 없는 developer → 모든 섹션 빈 상태")
    void testNonOwnerDeveloperEmpty() throws Exception {
        saveType("타인_증빙", ownerWithPerm, null);

        mockMvc.perform(get("/api/v1/my-tasks")
                        .header("Authorization", "Bearer " + nonOwnerDeveloperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.rejected").value(0))
                .andExpect(jsonPath("$.data.counts.dueSoon").value(0))
                .andExpect(jsonPath("$.data.counts.notSubmitted").value(0))
                .andExpect(jsonPath("$.data.counts.inReview").value(0))
                .andExpect(jsonPath("$.data.counts.completed").value(0));

        System.out.println("✅ [Empty] 비담당자 응답 모두 빈 섹션");
    }

    // ==================================================================
    // 4. 섹션 분류 정확성 (5가지 케이스)
    // ==================================================================

    @Test
    @Order(4)
    @DisplayName("[Classification] 5가지 케이스가 각 섹션에 정확히 들어감")
    void testSectionClassification() throws Exception {
        // 1) rejected 케이스
        EvidenceType etRej = saveType("반려_증빙", ownerWithPerm, null);
        saveFile(etRej, "rej_v1.pdf", 1, ReviewStatus.rejected,
                "PDF 형식이 아닙니다. 재업로드 요청", admin);

        // 2) dueSoon 케이스 — 파일 없음 + dueDate = today + 3
        saveType("마감임박_증빙", ownerWithPerm, FIXED_TODAY.plusDays(3));

        // 3) notSubmitted 케이스 — 파일 없음 + dueDate 없음
        saveType("미제출_증빙", ownerWithPerm, null);

        // 4) inReview 케이스
        EvidenceType etPending = saveType("검토중_증빙", ownerWithPerm, null);
        saveFile(etPending, "p_v1.pdf", 1, ReviewStatus.pending, null, null);

        // 5) completed 케이스 (approved)
        EvidenceType etApproved = saveType("완료_증빙", ownerWithPerm, null);
        saveFile(etApproved, "a_v1.pdf", 1, ReviewStatus.approved, null, admin);

        mockMvc.perform(get("/api/v1/my-tasks")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.rejected").value(1))
                .andExpect(jsonPath("$.data.counts.dueSoon").value(1))
                .andExpect(jsonPath("$.data.counts.notSubmitted").value(1))
                .andExpect(jsonPath("$.data.counts.inReview").value(1))
                .andExpect(jsonPath("$.data.counts.completed").value(1))
                // 각 섹션의 이름이 정확한지
                .andExpect(jsonPath("$.data.rejected[0].evidenceTypeName").value("반려_증빙"))
                .andExpect(jsonPath("$.data.dueSoon[0].evidenceTypeName").value("마감임박_증빙"))
                .andExpect(jsonPath("$.data.notSubmitted[0].evidenceTypeName").value("미제출_증빙"))
                .andExpect(jsonPath("$.data.inReview[0].evidenceTypeName").value("검토중_증빙"))
                .andExpect(jsonPath("$.data.completed[0].evidenceTypeName").value("완료_증빙"))
                // 반려 사유 노출 검증
                .andExpect(jsonPath("$.data.rejected[0].rejectReason")
                        .value("PDF 형식이 아닙니다. 재업로드 요청"))
                .andExpect(jsonPath("$.data.rejected[0].rejectedByName").value("관리자"))
                // dueSoon 케이스 daysUntilDue=3 확인
                .andExpect(jsonPath("$.data.dueSoon[0].daysUntilDue").value(3));

        System.out.println("✅ [Classification] 5섹션 분류 + 반려 사유 + D-day 정확");
    }

    // ==================================================================
    // 5. 마감 임박 경계값 — D-0 / D-7 은 임박, D-8 은 미제출
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("[DueSoon Boundary] D-0, D-7 → 임박 / D-8 → 미제출")
    void testDueSoonBoundary() throws Exception {
        saveType("D0", ownerWithPerm, FIXED_TODAY);               // D-0
        saveType("D7", ownerWithPerm, FIXED_TODAY.plusDays(7));   // D-7 (경계)
        saveType("D8", ownerWithPerm, FIXED_TODAY.plusDays(8));   // D-8 → 미제출
        saveType("Dminus1", ownerWithPerm, FIXED_TODAY.minusDays(1)); // 지난 건도 임박 (음수)

        mockMvc.perform(get("/api/v1/my-tasks")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.dueSoon").value(3))   // D-0, D-7, D-1(지남)
                .andExpect(jsonPath("$.data.counts.notSubmitted").value(1))  // D-8 만
                // dueSoon 정렬: 지난 것(-1) 먼저
                .andExpect(jsonPath("$.data.dueSoon[0].evidenceTypeName").value("Dminus1"));

        System.out.println("✅ [DueSoon Boundary] D-0/D-7 임박, D-8 미제출, 음수 포함 정렬");
    }

    // ==================================================================
    // 6. 최신 파일 기준 분류 — 이전 버전이 rejected 여도 최신이 approved 이면 완료
    // ==================================================================

    @Test
    @Order(6)
    @DisplayName("[Latest-Wins] v1=rejected, v2=approved → 완료 섹션으로 분류")
    void testLatestFileDeterminesSection() throws Exception {
        EvidenceType et = saveType("재제출_성공", ownerWithPerm, null);
        saveFile(et, "v1.pdf", 1, ReviewStatus.rejected, "다시 해주세요", admin);
        saveFile(et, "v2.pdf", 2, ReviewStatus.approved, null, admin);

        mockMvc.perform(get("/api/v1/my-tasks")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.rejected").value(0))
                .andExpect(jsonPath("$.data.counts.completed").value(1))
                .andExpect(jsonPath("$.data.completed[0].evidenceTypeName").value("재제출_성공"))
                .andExpect(jsonPath("$.data.completed[0].latestVersion").value(2));

        System.out.println("✅ [Latest-Wins] 최신 파일 상태로 섹션 결정");
    }

    // ==================================================================
    // 7. 완료 섹션 10건 limit + counts.completed 는 전체 카운트
    // ==================================================================

    @Test
    @Order(7)
    @DisplayName("[Completed Limit] 완료 12건 → completed 배열 10개, counts=12")
    void testCompletedSectionLimit() throws Exception {
        for (int i = 1; i <= 12; i++) {
            EvidenceType et = saveType("완료_" + i, ownerWithPerm, null);
            saveFile(et, "done_" + i + ".pdf", 1, ReviewStatus.approved, null, admin);
        }

        mockMvc.perform(get("/api/v1/my-tasks")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed.length()").value(10))
                .andExpect(jsonPath("$.data.counts.completed").value(12));

        System.out.println("✅ [Completed Limit] 배열 10개 / counts 12 정상");
    }

    // ==================================================================
    // 8. 상세 API — 본인 소유
    // ==================================================================

    @Test
    @Order(8)
    @DisplayName("[Detail] 본인 소유 증빙 상세 → 반려 사유 + 이력 포함")
    void testDetailOwned() throws Exception {
        EvidenceType et = saveType("재제출_대상", ownerWithPerm, FIXED_TODAY.plusDays(5));
        saveFile(et, "v1.pdf", 1, ReviewStatus.rejected, "PDF 가 깨졌습니다. 재작업 부탁드립니다.", admin);

        mockMvc.perform(get("/api/v1/my-tasks/" + et.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidenceTypeName").value("재제출_대상"))
                .andExpect(jsonPath("$.data.currentStatus").value("rejected"))
                .andExpect(jsonPath("$.data.rejectReason").value("PDF 가 깨졌습니다. 재작업 부탁드립니다."))
                .andExpect(jsonPath("$.data.rejectedByName").value("관리자"))
                .andExpect(jsonPath("$.data.daysUntilDue").value(5))
                .andExpect(jsonPath("$.data.history.length()").value(1))
                .andExpect(jsonPath("$.data.history[0].reviewStatus").value("rejected"));

        System.out.println("✅ [Detail] 본인 소유 증빙 상세 정상");
    }

    // ==================================================================
    // 9. 상세 API — 타인 소유 접근 → 403
    // ==================================================================

    @Test
    @Order(9)
    @DisplayName("[Detail-403] 타인 소유 증빙 상세 접근 → 403")
    void testDetailOfOtherUserForbidden() throws Exception {
        EvidenceType et = saveType("남의_증빙", otherOwner, null);

        mockMvc.perform(get("/api/v1/my-tasks/" + et.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());

        System.out.println("✅ [Detail-403] 타인 소유 접근 차단");
    }

    // ==================================================================
    // 10. admin 은 permission_evidence 무관하게 접근. 단, owner 증빙만 반환
    // ==================================================================

    @Test
    @Order(10)
    @DisplayName("[Admin] admin 은 permission_evidence 와 무관. 본인 owner 증빙만 반환")
    void testAdminCanAccessOnlyOwnedTypes() throws Exception {
        saveType("admin_오너_증빙", admin, null);
        saveType("다른_증빙", ownerWithPerm, null);

        mockMvc.perform(get("/api/v1/my-tasks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.notSubmitted").value(1))
                .andExpect(jsonPath("$.data.notSubmitted[0].evidenceTypeName").value("admin_오너_증빙"));

        System.out.println("✅ [Admin] admin 접근 허용 + 본인 owner 만 필터");
    }

    // ==================================================================
    // 헬퍼
    // ==================================================================

    private EvidenceType saveType(String name, User owner, LocalDate dueDate) {
        return evidenceTypeRepository.save(EvidenceType.builder()
                .control(control)
                .name(name)
                .ownerUser(owner)
                .dueDate(dueDate)
                .build());
    }

    private EvidenceFile saveFile(EvidenceType et, String name, int version,
                                  ReviewStatus status, String reviewNote, User reviewer) {
        EvidenceFile.EvidenceFileBuilder b = EvidenceFile.builder()
                .evidenceType(et)
                .fileName(name)
                .filePath("/tmp/" + name)
                .fileSize(1024L)
                .version(version)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .uploadedBy(et.getOwnerUser())
                .reviewStatus(status);

        if (status == ReviewStatus.approved || status == ReviewStatus.rejected) {
            b.reviewedBy(reviewer)
                    .reviewNote(reviewNote)
                    .reviewedAt(LocalDateTime.now());
        }
        return evidenceFileRepository.save(b.build());
    }
}