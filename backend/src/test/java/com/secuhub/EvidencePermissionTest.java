package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 5-2 — 증빙 접근 권한 검증 테스트
 *
 * <h3>검증 매트릭스</h3>
 * <pre>
 *                              | admin | 담당자(소유·permEv=T) | 담당자(소유·permEv=F) | 담당자(타인) |
 * POST /upload                 |  ✅auto |        ✅pending        |          ❌403         |     ❌403    |
 * GET  /by-type/{etId}         |  ✅   |          ✅            |          ❌403         |     ❌403    |
 * GET  /{fileId}/download      |  ✅   |          ✅            |          ❌403         |     ❌403    |
 * DELETE /{fileId}             |  ✅   |         ❌403          |          ❌403         |     ❌403    |
 * GET  /evidence-files (전체) |  ✅   |         ❌403          |          ❌403         |     ❌403    |
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-2 — 증빙 접근 권한 (EvidenceAuthService)")
class EvidencePermissionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlRepository controlRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;

    // 테스트 고정값
    private User admin;
    private User ownerWithPerm;       // 담당자, permission_evidence=true, EvidenceType 소유자
    private User ownerWithoutPerm;    // 담당자, permission_evidence=false, 소유자 지정되어 있어도 권한 없음
    private User outsider;            // 담당자, permission_evidence=true, 하지만 EvidenceType 의 소유자가 아님
    private EvidenceType ownedType;   // ownerWithPerm 이 소유
    private EvidenceFile existingFile; // ownedType 에 올라가 있는 기존 파일 (download/delete 테스트용)

    private String adminToken;
    private String ownerWithPermToken;
    private String ownerWithoutPermToken;
    private String outsiderToken;

    // ------------------------------------------------------------------
    // 공통 픽스처 — @BeforeEach 로 매번 초기화 (@DirtiesContext 대신)
    // ------------------------------------------------------------------

    @BeforeEach
    @Transactional
    void setUp() {
        // 기존 데이터 정리 (FK 역순)
        evidenceFileRepository.deleteAll();
        evidenceTypeRepository.deleteAll();
        controlRepository.deleteAll();
        frameworkRepository.deleteAll();
        userRepository.deleteAll();

        // 사용자 4명
        admin = userRepository.save(User.builder()
                .email("perm-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin).permissionEvidence(true).permissionVuln(true)
                .build());

        ownerWithPerm = userRepository.save(User.builder()
                .email("perm-owner@test.com").name("인사팀 홍길동")
                .hashedPassword(passwordEncoder.encode("pw"))
                .team("인사팀").role(UserRole.developer)
                .permissionEvidence(true).permissionVuln(true)
                .build());

        ownerWithoutPerm = userRepository.save(User.builder()
                .email("perm-noev@test.com").name("권한없는사람")
                .hashedPassword(passwordEncoder.encode("pw"))
                .team("인사팀").role(UserRole.developer)
                .permissionEvidence(false)   // ❌ 증빙 수집 권한 없음
                .permissionVuln(true)
                .build());

        outsider = userRepository.save(User.builder()
                .email("perm-outsider@test.com").name("타팀 담당자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .team("법무팀").role(UserRole.developer)
                .permissionEvidence(true).permissionVuln(true)
                .build());

        // Framework → Control → EvidenceType (owner = ownerWithPerm)
        Framework fw = frameworkRepository.save(Framework.builder()
                .name("ISMS-P 2026 perm-test").build());
        Control ctrl = controlRepository.save(Control.builder()
                .framework(fw).code("2.2.1").name("임직원 교육").build());
        ownedType = evidenceTypeRepository.save(EvidenceType.builder()
                .control(ctrl).name("보안 교육 수료증")
                .ownerUser(ownerWithPerm)     // 👉 소유자
                .build());

        // 기존 업로드된 파일 1개 (download/delete 테스트용)
        existingFile = evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(ownedType)
                .fileName("기존파일.pdf")
                .filePath("/tmp/nonexistent.pdf")
                .fileSize(100L)
                .version(1)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .uploadedBy(admin)
                .reviewStatus(ReviewStatus.auto_approved)
                .build());

        // JWT 토큰 4개
        adminToken = jwtTokenProvider.createToken(admin.getId(), admin.getEmail(), "admin");
        ownerWithPermToken = jwtTokenProvider.createToken(
                ownerWithPerm.getId(), ownerWithPerm.getEmail(), "developer");
        ownerWithoutPermToken = jwtTokenProvider.createToken(
                ownerWithoutPerm.getId(), ownerWithoutPerm.getEmail(), "developer");
        outsiderToken = jwtTokenProvider.createToken(
                outsider.getId(), outsider.getEmail(), "developer");
    }

    // ==================================================================
    // 1. 업로드 권한 (POST /api/v1/evidence-files/upload)
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("[Upload] admin 업로드 → 200 + review_status=auto_approved")
    void testAdminUpload_autoApproved() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "admin_upload.pdf", "application/pdf", "dummy".getBytes());

        mockMvc.perform(multipart("/api/v1/evidence-files/upload")
                        .file(file)
                        .param("evidenceTypeId", ownedType.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // DB 확인: 가장 최근 파일의 reviewStatus 가 auto_approved
        EvidenceFile saved = evidenceFileRepository
                .findByEvidenceTypeIdOrderByVersionDesc(ownedType.getId()).get(0);
        assertThat(saved.getReviewStatus()).isEqualTo(ReviewStatus.auto_approved);
        assertThat(saved.getUploadedBy().getId()).isEqualTo(admin.getId());

        System.out.println("✅ [Upload] admin → auto_approved 정상");
    }

    @Test
    @Order(2)
    @DisplayName("[Upload] 담당자(소유·permission_evidence=true) → 200 + review_status=pending")
    void testOwnerUpload_pending() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "owner_upload.pdf", "application/pdf", "dummy".getBytes());

        mockMvc.perform(multipart("/api/v1/evidence-files/upload")
                        .file(file)
                        .param("evidenceTypeId", ownedType.getId().toString())
                        .param("submitNote", "분기 교육 수료증 제출합니다")
                        .header("Authorization", "Bearer " + ownerWithPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        EvidenceFile saved = evidenceFileRepository
                .findByEvidenceTypeIdOrderByVersionDesc(ownedType.getId()).get(0);
        assertThat(saved.getReviewStatus()).isEqualTo(ReviewStatus.pending);
        assertThat(saved.getUploadedBy().getId()).isEqualTo(ownerWithPerm.getId());
        assertThat(saved.getSubmitNote()).contains("교육");

        System.out.println("✅ [Upload] 담당자(소유) → pending 정상 + submit_note 기록");
    }

    @Test
    @Order(3)
    @DisplayName("[Upload] 담당자(소유지만 permission_evidence=false) → 403")
    void testOwnerWithoutPermission_forbidden() throws Exception {
        // ownerWithoutPerm 을 ownedType 의 소유자로 재지정 (극단 케이스: 소유자이지만 플래그 OFF)
        ownedType.assignOwner(ownerWithoutPerm);
        evidenceTypeRepository.save(ownedType);

        MockMultipartFile file = new MockMultipartFile(
                "file", "noperm.pdf", "application/pdf", "dummy".getBytes());

        mockMvc.perform(multipart("/api/v1/evidence-files/upload")
                        .file(file)
                        .param("evidenceTypeId", ownedType.getId().toString())
                        .header("Authorization", "Bearer " + ownerWithoutPermToken))
                .andExpect(status().isForbidden());

        System.out.println("✅ [Upload] permission_evidence=false → 403 정상");
    }

    @Test
    @Order(4)
    @DisplayName("[Upload] 담당자(타인 증빙) → 403")
    void testOutsiderUpload_forbidden() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "outsider.pdf", "application/pdf", "dummy".getBytes());

        mockMvc.perform(multipart("/api/v1/evidence-files/upload")
                        .file(file)
                        .param("evidenceTypeId", ownedType.getId().toString())
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        System.out.println("✅ [Upload] 담당자(타인 증빙) → 403 정상");
    }

    // ==================================================================
    // 2. 조회 권한 (GET /by-type/{evidenceTypeId})
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("[List] 담당자(소유) → 본인 증빙 파일 이력 조회 허용")
    void testOwnerCanListOwnFiles() throws Exception {
        mockMvc.perform(get("/api/v1/evidence-files/by-type/" + ownedType.getId())
                        .header("Authorization", "Bearer " + ownerWithPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].fileName").value("기존파일.pdf"));

        System.out.println("✅ [List] 담당자 본인 증빙 조회 정상");
    }

    @Test
    @Order(6)
    @DisplayName("[List] 담당자(타인 증빙) → 403")
    void testOutsiderCannotList() throws Exception {
        mockMvc.perform(get("/api/v1/evidence-files/by-type/" + ownedType.getId())
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        System.out.println("✅ [List] 담당자(타인) → 403 정상");
    }

    // ==================================================================
    // 3. 관리자 전용 엔드포인트 (담당자 진입 거부)
    // ==================================================================

    @Test
    @Order(7)
    @DisplayName("[Admin-Only] 담당자가 전체 목록(GET /evidence-files) → 403")
    void testDeveloperCannotListAll() throws Exception {
        mockMvc.perform(get("/api/v1/evidence-files")
                        .header("Authorization", "Bearer " + ownerWithPermToken))
                .andExpect(status().isForbidden());

        System.out.println("✅ [Admin-Only] 담당자 전체 목록 → 403 정상");
    }

    @Test
    @Order(8)
    @DisplayName("[Admin-Only] 담당자가 파일 삭제(DELETE /evidence-files/{id}) → 403 (본인 증빙이어도)")
    void testDeveloperCannotDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/evidence-files/" + existingFile.getId())
                        .header("Authorization", "Bearer " + ownerWithPermToken))
                .andExpect(status().isForbidden());

        // admin 은 허용됨
        mockMvc.perform(delete("/api/v1/evidence-files/" + existingFile.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        System.out.println("✅ [Admin-Only] 담당자 삭제 → 403, admin 삭제 → 200 정상");
    }

    // ==================================================================
    // 4. Framework API 는 여전히 admin 전용 (회귀 방지)
    // ==================================================================

    @Test
    @Order(9)
    @DisplayName("[Regression] 담당자(permission_evidence=true) 도 /api/v1/frameworks 는 차단")
    void testFrameworksStillAdminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/frameworks")
                        .header("Authorization", "Bearer " + ownerWithPermToken))
                .andExpect(status().isForbidden());

        System.out.println("✅ [Regression] /api/v1/frameworks admin 전용 유지 정상");
    }
}