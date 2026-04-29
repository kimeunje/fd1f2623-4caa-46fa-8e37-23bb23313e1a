package com.secuhub;

import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5-14g (β) — leaf collectedCount 집계 검증.
 *
 * <p>5-14f 의 두 카운트 ({@code evidenceTypeCount}, {@code pendingReviewCount}) 옆에
 * 신규 {@code collectedCount} 가 GET /tree 응답의 leaf NodeSummary 에 함께 노출되는지
 * 확인. ControlsView 트리 본문의 6컬럼 진행바 ({@code N/M}) 와 "완료/진행중/미수집"
 * 상태 derive 의 기반 데이터.</p>
 *
 * <p>패턴 A — 평면 leaf depth=1 (5-14f 회귀 픽스 표준) 사용. setUp 단순.</p>
 *
 * <h3>케이스 (3)</h3>
 * <ol>
 *   <li><b>fully collected leaf</b> — 3 ET 모두 file 1개 이상. collectedCount=3,
 *       evidenceTypeCount=3</li>
 *   <li><b>partially collected leaf</b> — 3 ET 중 1개만 file 있음. collectedCount=1,
 *       evidenceTypeCount=3 (DISTINCT 검증 — 같은 ET 의 파일 2개여도 1로 카운트)</li>
 *   <li><b>uncollected leaf</b> — 1 ET, file 0개. collectedCount=0
 *       (Map miss → defaultIfMissing)</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase514gBackendTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private PasswordEncoder passwordEncoder;

    private String adminToken;

    @BeforeEach
    void setUp() {
        evidenceFileRepository.deleteAllInBatch();
        evidenceTypeRepository.deleteAllInBatch();
        controlNodeRepository.deleteAllInBatch();
        frameworkRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        User admin = userRepository.save(User.builder()
                .email("admin-5-14g@test.com")
                .name("관리자")
                .hashedPassword(passwordEncoder.encode("password"))
                .role(UserRole.admin)
                .permissionEvidence(true)
                .build());
        adminToken = jwtTokenProvider.createToken(
                admin.getId(), admin.getEmail(), admin.getRole().name());
    }

    /** 패턴 A 헬퍼 — 평면 leaf (depth=1). 5-14f 회귀 픽스 표준 그대로. */
    private ControlNode createLeaf(Framework fw, String code, String name) {
        return controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code(code).name(name).displayOrder(0).depth(1).build());
    }

    private EvidenceType createType(ControlNode leaf, String name) {
        return evidenceTypeRepository.save(EvidenceType.builder()
                .control(leaf).name(name).build());
    }

    /**
     * EvidenceFile 빌더 — entity 의 nullable=false 필드 모두 채움.
     *
     * <ul>
     *   <li>{@code collectionMethod} — {@link CollectionMethod} enum (String 아님)</li>
     *   <li>{@code collectedAt} — {@code @Column(nullable = false)} 라 명시 필수
     *       (Builder.Default 없음)</li>
     *   <li>{@code reviewStatus} — Builder.Default 가 {@code auto_approved} 라 미지정
     *       시 자연 처리. 본 테스트는 collected 카운트만 검증, pending 검증 안 함</li>
     * </ul>
     */
    private void attachFile(EvidenceType et, String fileName) {
        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName(fileName)
                .filePath("/tmp/" + fileName)
                .fileSize(1024L)
                .version(1)
                .collectionMethod(CollectionMethod.manual)
                .collectedAt(LocalDateTime.now())
                .reviewStatus(ReviewStatus.auto_approved)
                .build());
    }

    // ========================================================================
    // 1. fully collected leaf — 3 ET 모두 file 1+, collectedCount=3
    // ========================================================================
    @Test
    @Order(1)
    @DisplayName("[GetTree-collected] 모든 ET 가 수집된 leaf — collectedCount = evidenceTypeCount")
    void testFullyCollectedLeaf() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW collected full").build());
        ControlNode leaf = createLeaf(fw, "1.1.1", "정책 수립");

        EvidenceType et1 = createType(leaf, "정책 문서");
        EvidenceType et2 = createType(leaf, "조직도");
        EvidenceType et3 = createType(leaf, "회의록");
        attachFile(et1, "policy.pdf");
        attachFile(et2, "org.pdf");
        attachFile(et3, "minutes.pdf");

        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nodes[0].nodeType").value("control"))
                .andExpect(jsonPath("$.data.nodes[0].evidenceTypeCount").value(3))
                .andExpect(jsonPath("$.data.nodes[0].collectedCount").value(3))
                .andExpect(jsonPath("$.data.nodes[0].pendingReviewCount").value(0));

        System.out.println("✅ [GetTree-collected] 모두 수집 — collectedCount=3 = evidenceTypeCount=3");
    }

    // ========================================================================
    // 2. partially collected — 3 ET 중 1개만 file, collectedCount=1
    //    DISTINCT 검증: 같은 ET 의 파일 2개여도 1로 카운트
    // ========================================================================
    @Test
    @Order(2)
    @DisplayName("[GetTree-collected] 일부 ET 만 수집된 leaf — collectedCount < evidenceTypeCount, DISTINCT 검증")
    void testPartiallyCollectedLeaf() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW collected partial").build());
        ControlNode leaf = createLeaf(fw, "1.1.2", "역할 정의");

        EvidenceType et1 = createType(leaf, "RACI 문서");
        createType(leaf, "직무기술서");      // 파일 없음
        createType(leaf, "권한 매트릭스");    // 파일 없음
        attachFile(et1, "raci.pdf");
        attachFile(et1, "raci-v2.pdf");      // 같은 ET 의 두 번째 파일 — DISTINCT 검증

        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].evidenceTypeCount").value(3))
                // DISTINCT et.id 라 같은 ET 의 파일 2개여도 1로 카운트
                .andExpect(jsonPath("$.data.nodes[0].collectedCount").value(1));

        System.out.println("✅ [GetTree-collected] 부분 수집 — collectedCount=1 (DISTINCT et.id, 같은 ET 의 파일 2개 → 1)");
    }

    // ========================================================================
    // 3. uncollected — 파일 0개, collectedCount=0 (Map miss → 호출측 default)
    // ========================================================================
    @Test
    @Order(3)
    @DisplayName("[GetTree-collected] 파일 0개 leaf — collectedCount=0 (Map miss → default 0)")
    void testUncollectedLeaf() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder().name("FW uncollected").build());
        ControlNode leaf = createLeaf(fw, "1.1.3", "감사 계획");

        createType(leaf, "감사 계획서");   // 파일 없음

        // collectedCount 는 leaf 에 항상 노출 (5-14g β: TreeService.toNodeSummary 가
        // leaf 면 항상 collectedCount 채움, Map miss 시 defaultIfMissing=0). category 만 omit.
        mockMvc.perform(get("/api/v1/frameworks/{id}/tree", fw.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].evidenceTypeCount").value(1))
                .andExpect(jsonPath("$.data.nodes[0].collectedCount").value(0))
                .andExpect(jsonPath("$.data.nodes[0].pendingReviewCount").value(0));

        System.out.println("✅ [GetTree-collected] 파일 0개 — collectedCount=0 (Map miss → default 0)");
    }
}