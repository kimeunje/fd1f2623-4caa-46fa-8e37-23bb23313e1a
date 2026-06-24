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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 증빙 결재/승인 단계 OFF (app.approval.enabled=false) 동작 검증.
 *
 * <ul>
 *   <li>approve / reject → 400 + "증빙 승인 기능이 비활성화되어 있습니다."</li>
 *   <li>findPending(GET /pending) → DB 에 pending 이 있어도 빈 페이지(total=0)</li>
 * </ul>
 *
 * <p>upload → auto_approved 경로는 multipart/asset 인프라가 필요해 여기서 다루지 않는다
 * (필요 시 업로드 인프라를 갖춘 테스트에 OFF 케이스를 추가).</p>
 *
 * <p>setUp / newPendingFile 은 {@link EvidenceApprovalTest} 와 동일 패턴 — 직접 repo 시드.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.approval.enabled=false") // 승인 OFF 고정
@AutoConfigureMockMvc
@DisplayName("증빙 승인 OFF — 게이트 동작")
class EvidenceApprovalDisabledTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FrameworkRepository frameworkRepository;
    @Autowired private ControlNodeRepository controlNodeRepository;
    @Autowired private EvidenceTypeRepository evidenceTypeRepository;
    @Autowired private EvidenceFileRepository evidenceFileRepository;

    private User admin;
    private User owner;
    private EvidenceType ownedType;
    private String adminToken;

    @BeforeEach
    void setUp() {
        evidenceFileRepository.deleteAll();
        evidenceTypeRepository.deleteAll();
        controlNodeRepository.deleteAll();
        frameworkRepository.deleteAll();
        userRepository.deleteAll();

        admin = userRepository.save(User.builder()
                .email("off-admin@test.com").name("관리자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .role(UserRole.admin).permissionEvidence(true)
                .build());

        owner = userRepository.save(User.builder()
                .email("off-owner@test.com").name("담당자")
                .hashedPassword(passwordEncoder.encode("pw"))
                .team("인사팀").role(UserRole.developer)
                .permissionEvidence(true)
                .build());

        Framework fw = frameworkRepository.save(Framework.builder()
                .name("ISMS-P off-test").build());
        ControlNode ctrl = controlNodeRepository.save(ControlNode.builder()
                .framework(fw).parent(null).nodeType(NodeType.control)
                .code("2.2.1").name("임직원 교육")
                .displayOrder(0).depth(1).build());
        ownedType = evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl).name("보안 교육 수료증").ownerUser(owner).build());

        adminToken = jwtTokenProvider.createToken(admin.getId(), admin.getEmail(), "admin");
    }

    /** pending 상태의 파일을 하나 만들어 반환 (직접 시드 — OFF 라도 DB 값은 강제 가능) */
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

    @Test
    @DisplayName("[OFF] 승인 호출 → 400 + 비활성화 메시지")
    void approveBlockedWhenDisabled() throws Exception {
        EvidenceFile file = newPendingFile("off_approve.pdf");
        EvidenceFileDto.ApproveRequest req = new EvidenceFileDto.ApproveRequest("ok");

        mockMvc.perform(post("/api/v1/evidence-files/" + file.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("증빙 승인 기능이 비활성화되어 있습니다."));
    }

    @Test
    @DisplayName("[OFF] 반려 호출(유효 사유) → 400 + 비활성화 메시지")
    void rejectBlockedWhenDisabled() throws Exception {
        EvidenceFile file = newPendingFile("off_reject.pdf");
        // 반려는 @Valid 라 빈 사유면 검증 400 이 먼저 — 가드 400 을 보기 위해 유효 사유 전송
        EvidenceFileDto.RejectRequest req = new EvidenceFileDto.RejectRequest("형식 불일치");

        mockMvc.perform(post("/api/v1/evidence-files/" + file.getId() + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("증빙 승인 기능이 비활성화되어 있습니다."));
    }

    @Test
    @DisplayName("[OFF] 승인 대기 목록 → DB 에 pending 이 있어도 빈 페이지(total=0)")
    void pendingListEmptyWhenDisabled() throws Exception {
        newPendingFile("off_p1.pdf");
        newPendingFile("off_p2.pdf");

        mockMvc.perform(get("/api/v1/evidence-files/pending")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(0));
    }
}