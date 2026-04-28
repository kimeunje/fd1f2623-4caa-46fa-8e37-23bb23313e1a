package com.secuhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.config.jwt.JwtTokenProvider;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 5-14e — Framework export (트리 → 엑셀) API 검증.
 *
 * <h3>검증 항목</h3>
 * <ul>
 *   <li>{@code GET /api/v1/frameworks/{id}/export} 정상 다운로드 (xlsx Content-Type)</li>
 *   <li>헤더 6 컬럼 — 코드 / 영역 / 항목명 / 설명 / 필요 증빙 / 계층 경로</li>
 *   <li>leaf ({@code node_type='control'}) 만 행으로 — category 행 미포함</li>
 *   <li>5단 mixed-depth 트리에서 depth=N leaf 가 한 행</li>
 *   <li>계층 경로 = 모든 ancestors[].code + leaf.code 를 {@code " > "} 로 연결</li>
 *   <li>영역 = depth=1 ancestor 의 name. depth=1 leaf 는 빈 문자열</li>
 *   <li>빈 트리 (leaf 없음) 도 헤더만 있는 시트 정상 export (Import 템플릿 용도)</li>
 * </ul>
 *
 * <p>spec §3.3.1.4 / §6.4 정합. v14.5 신규 (Phase 5-14e).</p>
 *
 * <p>{@link com.secuhub.domain.evidence.service.FrameworkExportService} 가 leaf 의 evidence_types
 * 매칭을 시도 ({@code evidenceTypeRepository.findByControlId(leaf.getId())}). 본 테스트는
 * dev/test 환경 (V6 미실행) 이라 ControlNode.id 와 Control.id 가 별개 sequence — 매칭 안 됨,
 * "필요 증빙" 컬럼은 빈 문자열 자연 (5-14e impact-summary 와 같은 패턴, 5-14f 매핑 이주 후 정상).
 * 본 테스트는 leaf 의 코드 / 영역 / 계층 경로 검증이 핵심.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 5-14e — Framework export API")
class FrameworkExportTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;
    @Autowired private ControlRepository controlRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;
    @Autowired private CollectionJobRepository collectionJobRepository;

    private String adminToken;

    @BeforeEach
    void setUp() {
        evidenceFileRepository.deleteAll();
        collectionJobRepository.deleteAll();
        evidenceTypeRepository.deleteAll();
        controlNodeRepository.deleteAll();   // 자기참조 self-FK + framework FK CASCADE
        controlRepository.deleteAll();
        frameworkRepository.deleteAll();
        userRepository.deleteAll();

        User admin = userRepository.save(User.builder()
                .email("export-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin)
                .permissionEvidence(true).permissionVuln(true)
                .build());
        adminToken = jwtTokenProvider.createToken(admin.getId(), admin.getEmail(), "admin");
    }

    // ==================================================================
    // 1. 5단 mixed-depth 트리 export + 빈 트리 export 통합 검증
    // ==================================================================

    /**
     * 5단 mixed-depth 트리:
     * <pre>
     * "1" (depth=1, category, "대분류 A")
     *  ├── "1.1" (depth=2, category, "중분류")
     *  │    ├── "1.1.1" (depth=3, control, "leaf 통제 A")  ★LEAF
     *  │    ├── "1.1.2" (depth=3, category, "소분류")
     *  │    │    └── "1.1.2.1" (depth=4, category, "세부분류")
     *  │    │         └── "1.1.2.1.1" (depth=5, control, "leaf 통제 B")  ★LEAF
     *  │    └── "1.1.3" (depth=3, control, "leaf 통제 C")  ★LEAF
     *  └── "1.2" (depth=2, control, "leaf 통제 D")  ★LEAF (mixed-depth sibling)
     * "2" (depth=1, control, "leaf 통제 E")  ★LEAF (depth=1, no ancestor)
     * </pre>
     *
     * 코드 ASC 정렬 후 5 leaf 행:
     *   1.1.1 / 1.1.2.1.1 / 1.1.3 / 1.2 / 2
     */
    @Test
    @Order(1)
    @DisplayName("[Export] 5단 mixed-depth 트리 → 5 leaf 행 + 계층 경로 정확")
    void testExportMixedDepth() throws Exception {
        Framework fw = frameworkRepository.save(Framework.builder()
                .name("ISMS-P 2026 Test").build());

        // depth=1
        ControlNode root1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.category)
                .code("1").name("대분류 A").displayOrder(0).depth(1).build());
        ControlNode root2 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("2").name("leaf 통제 E").description("depth=1 leaf").displayOrder(1).depth(1).build());

        // depth=2
        ControlNode lev2_1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(root1).nodeType(NodeType.category)
                .code("1.1").name("중분류").displayOrder(0).depth(2).build());
        ControlNode lev2_2 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(root1).nodeType(NodeType.control)
                .code("1.2").name("leaf 통제 D").description("depth=2 leaf, mixed-depth").displayOrder(1).depth(2).build());

        // depth=3
        ControlNode lev3_1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(lev2_1).nodeType(NodeType.control)
                .code("1.1.1").name("leaf 통제 A").description("정책 수립").displayOrder(0).depth(3).build());
        ControlNode lev3_2 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(lev2_1).nodeType(NodeType.category)
                .code("1.1.2").name("소분류").displayOrder(1).depth(3).build());
        ControlNode lev3_3 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(lev2_1).nodeType(NodeType.control)
                .code("1.1.3").name("leaf 통제 C").displayOrder(2).depth(3).build());

        // depth=4
        ControlNode lev4_1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(lev3_2).nodeType(NodeType.category)
                .code("1.1.2.1").name("세부분류").displayOrder(0).depth(4).build());

        // depth=5
        ControlNode lev5_1 = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(lev4_1).nodeType(NodeType.control)
                .code("1.1.2.1.1").name("leaf 통제 B").description("5단 leaf").displayOrder(0).depth(5).build());

        // ── 다운로드
        MvcResult result = mockMvc.perform(get("/api/v1/frameworks/{id}/export", fw.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                // Spring 이 ResponseEntity.contentType(...) 사용 시 charset=UTF-8 자동 부가하는 케이스
                // 가 있어 정확 일치 (header().string) 대신 contentTypeCompatibleWith — charset 파라미터
                // 무시하고 MIME type 만 비교 (Spring MockMvc 표준 패턴, xlsx 같은 바이너리에 적합)
                .andExpect(content().contentTypeCompatibleWith(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();

        byte[] xlsxBytes = result.getResponse().getContentAsByteArray();
        assertThat(xlsxBytes).isNotEmpty();

        // ── XLSX 파싱
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("통제 항목");

            // 헤더 행
            Row header = sheet.getRow(0);
            assertThat(header).isNotNull();
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("코드");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("영역");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("항목명");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("설명");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("필요 증빙");
            assertThat(header.getCell(5).getStringCellValue()).isEqualTo("계층 경로");

            // leaf 5개 (코드 ASC) — 1.1.1, 1.1.2.1.1, 1.1.3, 1.2, 2
            assertThat(sheet.getLastRowNum()).isEqualTo(5);  // 0 헤더 + 5 leaf

            // 행별 검증 — code → expected (영역, 항목명, 계층 경로)
            Map<String, String[]> expected = new HashMap<>();
            expected.put("1.1.1", new String[]{"대분류 A", "leaf 통제 A", "1 > 1.1 > 1.1.1"});
            expected.put("1.1.2.1.1", new String[]{"대분류 A", "leaf 통제 B", "1 > 1.1 > 1.1.2 > 1.1.2.1 > 1.1.2.1.1"});
            expected.put("1.1.3", new String[]{"대분류 A", "leaf 통제 C", "1 > 1.1 > 1.1.3"});
            expected.put("1.2", new String[]{"대분류 A", "leaf 통제 D", "1 > 1.2"});
            expected.put("2", new String[]{"", "leaf 통제 E", "2"});  // depth=1 leaf, 영역 빈 문자열

            for (int i = 1; i <= 5; i++) {
                Row row = sheet.getRow(i);
                assertThat(row).as("행 " + i + " 존재").isNotNull();
                String code = row.getCell(0).getStringCellValue();
                String[] exp = expected.get(code);
                assertThat(exp).as("예상치 못한 코드 행: " + code).isNotNull();
                assertThat(row.getCell(1).getStringCellValue()).as("영역(" + code + ")").isEqualTo(exp[0]);
                assertThat(row.getCell(2).getStringCellValue()).as("항목명(" + code + ")").isEqualTo(exp[1]);
                assertThat(row.getCell(5).getStringCellValue()).as("계층 경로(" + code + ")").isEqualTo(exp[2]);
            }
        }

        System.out.println("✅ [Export] 5단 mixed-depth 트리 → 5 leaf 행, 계층 경로 정확");

        // ── 빈 트리 export (다른 framework, leaf 0 개) — 헤더만 있는 시트 정상 다운로드
        Framework empty = frameworkRepository.save(Framework.builder().name("FW-Empty").build());
        MvcResult emptyResult = mockMvc.perform(get("/api/v1/frameworks/{id}/export", empty.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        byte[] emptyBytes = emptyResult.getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(emptyBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0)).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("코드");
            assertThat(sheet.getLastRowNum()).isEqualTo(0);  // 헤더만, leaf 0행
        }
        System.out.println("✅ [Export] 빈 트리 → 헤더만 있는 시트 정상 다운로드");
    }
}