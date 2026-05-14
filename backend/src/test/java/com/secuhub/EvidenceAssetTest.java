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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * v18.6a — Evidence Asset 신규 채널 통합 테스트
 *
 * <h3>검증 범위</h3>
 * <ol>
 *   <li>{@link #testCreateAssetWithSha256} — 신규 upload + sha256 계산 + asset 저장
 *       + 물리 저장 ({@code assets/{id%1000}/{id}}, Q2=c)</li>
 *   <li>{@link #testDuplicateDetectedResponse} — 같은 sha256 재upload →
 *       status="duplicate_detected" + link 미생성 (Q1=b / Q4=a)</li>
 *   <li>{@link #testForceUploadCreatesNewAsset} — {@code forceUpload=true} 시
 *       같은 sha256 의 별도 asset 생성 (Q9)</li>
 *   <li>{@link #testLinkExistingAsset} — POST /link 로 multipart 없이 link 만 생성</li>
 *   <li>{@link #testSearchAssets} — 검색 API + LIKE prefix fallback (dev/test 환경)</li>
 *   <li>{@link #testSearchUsedInCount} — 검색 응답의 {@code usedInCount} (Q11) 정합</li>
 *   <li>{@link #testAssetGcOnLastLinkDelete} — 마지막 EvidenceFile 삭제 시 asset
 *       자동 GC (Q10)</li>
 * </ol>
 *
 * <h3>본 프로젝트 테스트 컨벤션 정합 (L_TEST_MOCKMVC_BUILD_PATTERN)</h3>
 * <p>{@code @SpringBootTest + @ActiveProfiles("test") + @AutoConfigureMockMvc +
 * @Autowired MockMvc} 표준 패턴.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("v18.6a — Evidence Asset 신규 채널 (sha256 + reuse + 검색 + GC)")
class EvidenceAssetTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;
    @Autowired private EvidenceAssetRepository evidenceAssetRepository;

    private User admin;
    private String adminToken;
    private EvidenceType evidenceType1;
    private EvidenceType evidenceType2;

    // 고정 content — sha256 재현성
    private static final byte[] FILE_CONTENT_A = "정보보호 정책서 v2.0 내용".getBytes();

    @BeforeEach
    void setUp() {
        // FK 역순 정리
        evidenceFileRepository.deleteAll();
        evidenceAssetRepository.deleteAll();
        evidenceTypeRepository.deleteAll();
        controlNodeRepository.deleteAll();
        frameworkRepository.deleteAll();
        userRepository.deleteAll();

        admin = userRepository.save(User.builder()
                .email("asset-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin).permissionEvidence(true)
                .build());
        adminToken = jwtTokenProvider.createToken(admin.getId(), admin.getEmail(), "admin");

        // Framework + ControlNode (leaf, depth=1) + EvidenceType 2개 (link 공유 검증용)
        Framework fw = frameworkRepository.save(Framework.builder()
                .name("자산 테스트 FW").build());
        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("ASSET-01").name("자산 테스트 통제")
                .displayOrder(0).depth(1).build());
        evidenceType1 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf).name("자산 테스트 증빙 1").build());
        evidenceType2 = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(leaf).name("자산 테스트 증빙 2").build());
    }

    // ========================================================================
    // 1. 신규 upload — sha256 계산 + asset 생성
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("[Asset] 신규 upload — sha256 계산 + asset 생성 + filePath 정합")
    void testCreateAssetWithSha256() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "정보보호_정책서.pdf", "application/pdf", FILE_CONTENT_A);

        mockMvc.perform(multipart("/api/v1/evidence-files/upload")
                        .file(file)
                        .param("evidenceTypeId", evidenceType1.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("created"))
                .andExpect(jsonPath("$.data.evidenceFile.id").exists())
                .andExpect(jsonPath("$.data.evidenceFile.assetId").exists());

        // DB asset 검증
        List<EvidenceAsset> assets = evidenceAssetRepository.findAll();
        assertThat(assets).hasSize(1);
        EvidenceAsset asset = assets.get(0);
        assertThat(asset.getSha256()).hasSize(64);
        assertThat(asset.getOriginalFileName()).isEqualTo("정보보호_정책서.pdf");
        assertThat(asset.getFileSize()).isEqualTo((long) FILE_CONTENT_A.length);
        // Q2=c — assets/{id%1000}/{id} 패턴
        assertThat(asset.getFilePath()).contains("assets");
        assertThat(asset.getFilePath()).endsWith(String.valueOf(asset.getId()));

        // DB EvidenceFile (link) 검증
        List<EvidenceFile> files = evidenceFileRepository
                .findByEvidenceTypeIdOrderByVersionDesc(evidenceType1.getId());
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getAsset().getId()).isEqualTo(asset.getId());
        assertThat(files.get(0).getReviewStatus()).isEqualTo(ReviewStatus.auto_approved);

        System.out.println("✅ [Asset] 신규 upload — assetId=" + asset.getId()
                + ", sha256=" + asset.getSha256().substring(0, 8) + "...");
    }

    // ========================================================================
    // 2. 중복 감지 — 같은 sha256 재upload → duplicate_detected
    // ========================================================================

    @Test
    @Order(2)
    @DisplayName("[Asset] 중복 감지 — 같은 sha256 재upload → status=duplicate_detected (link 미생성)")
    void testDuplicateDetectedResponse() throws Exception {
        // 1차 upload — created
        MockMultipartFile file1 = new MockMultipartFile(
                "file", "정책서_원본.pdf", "application/pdf", FILE_CONTENT_A);
        mockMvc.perform(multipart("/api/v1/evidence-files/upload")
                        .file(file1)
                        .param("evidenceTypeId", evidenceType1.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("created"));

        // 2차 upload — 같은 content (sha256 같음), 다른 fileName, 다른 evidenceType
        MockMultipartFile file2 = new MockMultipartFile(
                "file", "정책서_사본.pdf", "application/pdf", FILE_CONTENT_A);
        mockMvc.perform(multipart("/api/v1/evidence-files/upload")
                        .file(file2)
                        .param("evidenceTypeId", evidenceType2.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("duplicate_detected"))
                .andExpect(jsonPath("$.data.existingAsset.id").exists())
                .andExpect(jsonPath("$.data.existingAsset.originalFileName").value("정책서_원본.pdf"))
                .andExpect(jsonPath("$.data.existingAsset.usedInCount").value(1))
                .andExpect(jsonPath("$.data.evidenceFile").doesNotExist());

        // DB 검증 — 2차는 link 미생성 (evidenceType2 의 파일 0개)
        assertThat(evidenceAssetRepository.findAll()).hasSize(1);
        assertThat(evidenceFileRepository.findByEvidenceTypeIdOrderByVersionDesc(
                evidenceType1.getId())).hasSize(1);
        assertThat(evidenceFileRepository.findByEvidenceTypeIdOrderByVersionDesc(
                evidenceType2.getId())).isEmpty();

        System.out.println("✅ [Asset] 중복 감지 — 2차 link 미생성 (사용자 confirm 대기)");
    }

    // ========================================================================
    // 3. forceUpload=true — 같은 sha 의 별도 asset 생성 (Q9)
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("[Asset] forceUpload=true — 같은 sha256 의 별도 asset 생성 (Q9)")
    void testForceUploadCreatesNewAsset() throws Exception {
        // 1차 upload
        MockMultipartFile file1 = new MockMultipartFile(
                "file", "원본.pdf", "application/pdf", FILE_CONTENT_A);
        mockMvc.perform(multipart("/api/v1/evidence-files/upload")
                        .file(file1)
                        .param("evidenceTypeId", evidenceType1.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(evidenceAssetRepository.count()).isEqualTo(1);
        EvidenceAsset firstAsset = evidenceAssetRepository.findAll().get(0);

        // 2차 upload — 같은 내용, forceUpload=true → 별도 asset 생성
        MockMultipartFile file2 = new MockMultipartFile(
                "file", "사본_별도.pdf", "application/pdf", FILE_CONTENT_A);
        mockMvc.perform(multipart("/api/v1/evidence-files/upload")
                        .file(file2)
                        .param("evidenceTypeId", evidenceType2.getId().toString())
                        .param("forceUpload", "true")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("created"))
                .andExpect(jsonPath("$.data.evidenceFile.assetId").exists());

        // 2개의 asset (같은 sha256, 다른 id) 존재 검증
        List<EvidenceAsset> assets = evidenceAssetRepository.findAll();
        assertThat(assets).hasSize(2);
        EvidenceAsset other = assets.stream()
                .filter(a -> !a.getId().equals(firstAsset.getId()))
                .findFirst().orElseThrow();
        assertThat(other.getSha256()).isEqualTo(firstAsset.getSha256());
        assertThat(other.getId()).isNotEqualTo(firstAsset.getId());

        System.out.println("✅ [Asset] forceUpload — 같은 sha 의 별도 asset 2개 (Q9 정합)");
    }

    // ========================================================================
    // 4. link only — POST /link, multipart 없음
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("[Asset] POST /link — 기존 asset 에 link 만 (multipart 없음)")
    void testLinkExistingAsset() throws Exception {
        // 사전 — asset 1개 등록
        MockMultipartFile file = new MockMultipartFile(
                "file", "공유_정책서.pdf", "application/pdf", FILE_CONTENT_A);
        mockMvc.perform(multipart("/api/v1/evidence-files/upload")
                        .file(file)
                        .param("evidenceTypeId", evidenceType1.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        EvidenceAsset asset = evidenceAssetRepository.findAll().get(0);

        // POST /link — evidenceType2 에 같은 asset link
        String linkBody = String.format(
                "{\"evidenceTypeId\":%d,\"assetId\":%d,\"fileName\":\"공유_사본.pdf\"," +
                        "\"submitNote\":\"reuse 테스트\"}",
                evidenceType2.getId(), asset.getId());

        mockMvc.perform(post("/api/v1/evidence-files/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkBody)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fileName").value("공유_사본.pdf"))
                .andExpect(jsonPath("$.data.assetId").value(asset.getId().intValue()));

        // DB 검증 — 두 EvidenceFile 이 같은 asset 참조
        List<EvidenceFile> et2Files = evidenceFileRepository
                .findByEvidenceTypeIdOrderByVersionDesc(evidenceType2.getId());
        assertThat(et2Files).hasSize(1);
        assertThat(et2Files.get(0).getAsset().getId()).isEqualTo(asset.getId());
        assertThat(et2Files.get(0).getFileName()).isEqualTo("공유_사본.pdf");

        // asset 의 link 카운트 = 2
        assertThat(evidenceAssetRepository.countLinkedFiles(asset.getId())).isEqualTo(2);

        System.out.println("✅ [Asset] /link — asset reuse, 두 EvidenceFile 이 같은 asset 참조");
    }

    // ========================================================================
    // 5. 검색 — GET /evidence-assets (dev/test LIKE prefix fallback)
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("[Asset] GET /evidence-assets — 검색 (dev/test LIKE prefix)")
    void testSearchAssets() throws Exception {
        // 사전 — asset 3개 등록 (다른 내용 = 다른 sha256)
        uploadFile(evidenceType1, "정보보호 정책서.pdf", "내용 A 임의 텍스트".getBytes());
        uploadFile(evidenceType1, "위험평가 보고서.pdf", "내용 B 임의 텍스트".getBytes());
        uploadFile(evidenceType2, "운영 계획서.pdf", "내용 C 임의 텍스트".getBytes());

        // 전체 검색
        mockMvc.perform(get("/api/v1/evidence-assets")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(3));

        // prefix 검색 — "정보보호" 로 시작하는 파일명만 매칭 (dev/test LIKE prefix)
        mockMvc.perform(get("/api/v1/evidence-assets")
                        .param("q", "정보보호")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].originalFileName").value("정보보호 정책서.pdf"));

        System.out.println("✅ [Asset] 검색 — LIKE prefix fallback 정상 (dev/test 환경)");
    }

    // ========================================================================
    // 6. 검색 응답의 usedInCount (Q11)
    // ========================================================================

    @Test
    @Order(6)
    @DisplayName("[Asset] 검색 응답의 usedInCount (Q11)")
    void testSearchUsedInCount() throws Exception {
        // asset 1개 등록 + 다른 evidenceType 에 link 추가 → usedInCount = 2
        uploadFile(evidenceType1, "공유 정책.pdf", FILE_CONTENT_A);
        EvidenceAsset asset = evidenceAssetRepository.findAll().get(0);

        // link 추가
        String linkBody = String.format(
                "{\"evidenceTypeId\":%d,\"assetId\":%d}",
                evidenceType2.getId(), asset.getId());
        mockMvc.perform(post("/api/v1/evidence-files/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkBody)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 검색 → usedInCount = 2
        mockMvc.perform(get("/api/v1/evidence-assets")
                        .param("q", "공유")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].usedInCount").value(2));

        System.out.println("✅ [Asset] usedInCount=2 (Q11 정합)");
    }

    // ========================================================================
    // 7. GC — 마지막 link 삭제 시 asset + 물리 파일 자동 정리 (Q10)
    // ========================================================================

    @Test
    @Order(7)
    @DisplayName("[Asset] GC — 마지막 link 삭제 시 asset 자동 정리 (Q10)")
    void testAssetGcOnLastLinkDelete() throws Exception {
        // asset 1개 등록 (link 1개)
        uploadFile(evidenceType1, "단일_link.pdf", FILE_CONTENT_A);
        assertThat(evidenceAssetRepository.findAll()).hasSize(1);
        EvidenceFile file = evidenceFileRepository
                .findByEvidenceTypeIdOrderByVersionDesc(evidenceType1.getId()).get(0);

        // EvidenceFile DELETE (admin 권한)
        mockMvc.perform(delete("/api/v1/evidence-files/" + file.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // asset 자동 GC (reference_count = 0 → 삭제)
        assertThat(evidenceAssetRepository.findAll()).isEmpty();

        System.out.println("✅ [Asset] GC — 마지막 link 삭제 후 asset 자동 정리 (Q10 정합)");
    }

    // ========================================================================
    // helper
    // ========================================================================

    private void uploadFile(EvidenceType et, String fileName, byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", fileName, "application/pdf", content);
        mockMvc.perform(multipart("/api/v1/evidence-files/upload")
                        .file(file)
                        .param("evidenceTypeId", et.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}