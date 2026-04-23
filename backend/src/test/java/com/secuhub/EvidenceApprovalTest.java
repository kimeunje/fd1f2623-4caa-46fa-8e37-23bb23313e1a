package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.dto.EvidenceFileDto;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 5-4 — 증빙 파일 승인/반려 API 테스트
 *
 * <h3>검증 항목</h3>
 * <ul>
 *   <li>승인 (pending → approved) + 코멘트 기록</li>
 *   <li>반려 (pending → rejected) + 사유 필수 검증</li>
 *   <li>상태 전이 규칙: auto_approved / approved / rejected 재처리 금지</li>
 *   <li>권한: 관리자 전용 (담당자 403)</li>
 *   <li>승인 대기 목록 조회 + 승인 후 목록에서 제외</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-4 — 증빙 파일 승인/반려 API")
class EvidenceApprovalTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlRepository controlRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;

    private User admin;
    private User owner;
    private EvidenceType ownedType;

    private String adminToken;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        evidenceFileRepository.deleteAll();
        evidenceTypeRepository.deleteAll();
        controlRepository.deleteAll();
        frameworkRepository.deleteAll();
        userRepository.deleteAll();

        admin = userRepository.save(User.builder()
                .email("approval-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin).permissionEvidence(true).permissionVuln(true)
                .build());

        owner = userRepository.save(User.builder()
                .email("approval-owner@test.com").name("인사팀 담당자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .team("인사팀").role(UserRole.developer)
                .permissionEvidence(true).permissionVuln(true)
                .build());

        Framework fw = frameworkRepository.save(Framework.builder()
                .name("ISMS-P 2026 approval-test").build());
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("2.2.1").name("임직원 교육").build());
        ownedType = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl).name("보안 교육 수료증").ownerUser(owner).build());

        adminToken = jwtTokenProvider.createToken(admin.getId(), admin.getEmail(), "admin");
        ownerToken = jwtTokenProvider.createToken(owner.getId(), owner.getEmail(), "developer");
    }

    /** pending 상태의 파일을 하나 만들어 반환 */
    private EvidenceFile newPendingFile(String name) {
        return evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(ownedType)
                .fileName(name)
                .filePath("/tmp/" + name)
                .fileSize(100L)
                .version(1)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .uploadedBy(owner)
                .submitNote("담당자 제출")
                .reviewStatus(ReviewStatus.pending)
                .build());
    }

    // ==================================================================
    // 1. 승인 (approve)
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("[Approve] pending 파일 승인 → 200 + review_status=approved + reviewer 기록")
    void testApprovePending() throws Exception {
        EvidenceFile file = newPendingFile("approve_ok.pdf");

        EvidenceFileDto.ApproveRequest req = new EvidenceFileDto.ApproveRequest("잘 작성됨");

        mockMvc.perform(post("/api/v1/evidence-files/" + file.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reviewStatus").value("approved"))
                .andExpect(jsonPath("$.data.reviewedByName").value("관리자"))
                .andExpect(jsonPath("$.data.reviewNote").value("잘 작성됨"));

        EvidenceFile reloaded = evidenceFileRepository.findById(file.getId()).orElseThrow();
        assertThat(reloaded.getReviewStatus()).isEqualTo(ReviewStatus.approved);
        assertThat(reloaded.getReviewedBy().getId()).isEqualTo(admin.getId());
        assertThat(reloaded.getReviewedAt()).isNotNull();

        System.out.println("✅ [Approve] 승인 정상 (pending → approved)");
    }

    @Test
    @Order(2)
    @DisplayName("[Approve] body 없는 승인 (reviewNote 생략) → 200")
    void testApproveWithoutBody() throws Exception {
        EvidenceFile file = newPendingFile("approve_nobody.pdf");

        mockMvc.perform(post("/api/v1/evidence-files/" + file.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("approved"));

        System.out.println("✅ [Approve] reviewNote 생략 가능 확인");
    }

    // ==================================================================
    // 2. 반려 (reject) + 사유 필수 검증
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("[Reject] pending 파일 반려 → 200 + review_status=rejected + 사유 기록")
    void testRejectPending() throws Exception {
        EvidenceFile file = newPendingFile("reject_ok.pdf");

        EvidenceFileDto.RejectRequest req = new EvidenceFileDto.RejectRequest(
                "형식이 맞지 않습니다. PDF로 다시 업로드해주세요.");

        mockMvc.perform(post("/api/v1/evidence-files/" + file.getId() + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("rejected"))
                .andExpect(jsonPath("$.data.reviewNote").value(
                        "형식이 맞지 않습니다. PDF로 다시 업로드해주세요."));

        System.out.println("✅ [Reject] 반려 정상 (pending → rejected)");
    }

    @Test
    @Order(4)
    @DisplayName("[Reject] 반려 사유 누락 → 400 Validation Error")
    void testRejectWithoutReviewNote() throws Exception {
        EvidenceFile file = newPendingFile("reject_noreason.pdf");

        // reviewNote 필드 자체가 없음
        mockMvc.perform(post("/api/v1/evidence-files/" + file.getId() + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        // DB 는 여전히 pending
        EvidenceFile reloaded = evidenceFileRepository.findById(file.getId()).orElseThrow();
        assertThat(reloaded.getReviewStatus()).isEqualTo(ReviewStatus.pending);

        System.out.println("✅ [Reject] 사유 누락 → 400 정상");
    }

    @Test
    @Order(5)
    @DisplayName("[Reject] 반려 사유 공백 문자열 → 400 Validation Error")
    void testRejectWithBlankReviewNote() throws Exception {
        EvidenceFile file = newPendingFile("reject_blank.pdf");

        EvidenceFileDto.RejectRequest req = new EvidenceFileDto.RejectRequest("   ");

        mockMvc.perform(post("/api/v1/evidence-files/" + file.getId() + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("반려 사유")));

        System.out.println("✅ [Reject] 공백 문자열 → 400 정상 (@NotBlank)");
    }

    // ==================================================================
    // 3. 상태 전이 규칙 (비-pending 재처리 차단)
    // ==================================================================

    @Test
    @Order(6)
    @DisplayName("[Transition] 이미 approved 된 파일 재승인 → 400")
    void testRejectAlreadyApproved() throws Exception {
        EvidenceFile file = newPendingFile("double_process.pdf");
        file.approve(admin, "선승인");
        evidenceFileRepository.save(file);

        EvidenceFileDto.ApproveRequest req = new EvidenceFileDto.ApproveRequest("재승인 시도");

        mockMvc.perform(post("/api/v1/evidence-files/" + file.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("검토 대기 상태에서만")));

        System.out.println("✅ [Transition] approved 재승인 → 400 정상");
    }

    @Test
    @Order(7)
    @DisplayName("[Transition] auto_approved 파일 반려 시도 → 400")
    void testRejectAutoApproved() throws Exception {
        // admin 이 직접 업로드한 auto_approved 파일
        EvidenceFile autoFile = evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(ownedType)
                .fileName("auto.pdf")
                .filePath("/tmp/auto.pdf")
                .fileSize(100L)
                .version(2)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .uploadedBy(admin)
                .reviewStatus(ReviewStatus.auto_approved)
                .build());

        EvidenceFileDto.RejectRequest req = new EvidenceFileDto.RejectRequest("반려하겠음");

        mockMvc.perform(post("/api/v1/evidence-files/" + autoFile.getId() + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("검토 대기 상태에서만")));

        System.out.println("✅ [Transition] auto_approved 반려 시도 → 400 정상");
    }

    // ==================================================================
    // 4. 권한 (관리자 전용)
    // ==================================================================

    @Test
    @Order(8)
    @DisplayName("[Auth] 담당자가 승인 시도 → 403")
    void testOwnerCannotApprove() throws Exception {
        EvidenceFile file = newPendingFile("owner_try_approve.pdf");

        mockMvc.perform(post("/api/v1/evidence-files/" + file.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());

        // DB 는 여전히 pending (담당자 처리 차단됨)
        EvidenceFile reloaded = evidenceFileRepository.findById(file.getId()).orElseThrow();
        assertThat(reloaded.getReviewStatus()).isEqualTo(ReviewStatus.pending);

        System.out.println("✅ [Auth] 담당자 승인 시도 → 403 정상");
    }

    @Test
    @Order(9)
    @DisplayName("[Auth] 담당자가 승인 대기 목록 조회 시도 → 403")
    void testOwnerCannotListPending() throws Exception {
        newPendingFile("listing_1.pdf");
        newPendingFile("listing_2.pdf");

        // 담당자는 거부
        mockMvc.perform(get("/api/v1/evidence-files/pending")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());

        // 관리자는 허용 + 2건 응답
        mockMvc.perform(get("/api/v1/evidence-files/pending")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(2));

        System.out.println("✅ [Auth] 담당자 /pending 조회 → 403, admin → 200 정상");
    }

    // ==================================================================
    // 5. 승인 후 /pending 에서 제외되는지 확인 (통합 시나리오)
    // ==================================================================

    @Test
    @Order(10)
    @DisplayName("[Integration] 승인 후 /pending 목록에서 제외")
    void testPendingListUpdatesAfterApproval() throws Exception {
        EvidenceFile a = newPendingFile("integration_a.pdf");
        EvidenceFile b = newPendingFile("integration_b.pdf");

        // 초기: 2건
        mockMvc.perform(get("/api/v1/evidence-files/pending")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        // a 승인
        mockMvc.perform(post("/api/v1/evidence-files/" + a.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 승인 후: 1건만 남아야 함 (b)
        mockMvc.perform(get("/api/v1/evidence-files/pending")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].fileName").value("integration_b.pdf"));

        System.out.println("✅ [Integration] 승인 후 /pending 제외 정상");
    }
}