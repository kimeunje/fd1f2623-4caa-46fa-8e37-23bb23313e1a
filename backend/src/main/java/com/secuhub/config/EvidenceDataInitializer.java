package com.secuhub.config;

import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * dev 프로필 데모 데이터 — 증빙 수집 관련.
 *
 * <p>DataInitializer(계정) 이후에 실행됩니다 (@Order(2)).</p>
 *
 * <h3>발표용 실데이터 시드 (ISMS-2025)</h3>
 *
 * <p><b>시연 시나리오</b>: ISMS-2025 를 복제하여 ISMS-2026 을 만들고, 복제본의
 * "2.6.1 네트워크 접근 > 네트워크 구성도" 에 대해 발표자가 직접 수집 스크립트를
 * 작성·실행해 자동 수집을 라이브로 시연한다. 따라서 본 시드에서 네트워크 구성도는
 * 의도적으로 비워 둔다(증적 없음 = 라이브 작성 타겟).</p>
 *
 * <p><b>범위 제한</b>: 검토(승인/반려) 워크플로우와 담당자 계정은 미구현이므로
 * 시드에서 제외. 모든 증빙 유형의 담당자(owner)/업로더는 admin 단일 계정으로 통일하고,
 * 모든 EvidenceFile 은 중립 상태(auto_approved)로 시드한다. 사전 시드된 자동 수집
 * (성공/실패+진단) 데이터도 제거 — 자동 수집은 위 시나리오대로 라이브로 시연한다.</p>
 *
 * <h3>구조</h3>
 * <pre>
 * ISMS-2025 (Framework 1)
 * ├── 1. 관리체계 수립 및 운영 (cat depth=1)
 * │   └── 1.1 관리체계 기반 마련 (cat depth=2)
 * │       ├── 1.1.1 경영진의 참여 (control depth=3)
 * │       │   ├── 경영진 참여 증적   [수동 업로드]
 * │       │   └── 개최 결과 보고     [수동 업로드]
 * │       └── 1.1.2 최고책임자의 지정 (control depth=3)
 * │           └── CISO·CPO 지정      [수동 업로드]
 * └── 2. 보호대책 요구사항 (cat depth=1)
 *     ├── 2.6 접근통제 (cat depth=2)
 *     │   └── 2.6.1 네트워크 접근 (control depth=3)
 *     │       └── 네트워크 구성도    [증적 없음 — 라이브 시연 타겟]
 *     └── 2.10 시스템 및 서비스 보안관리 (cat depth=2)
 *         └── 2.10.8 패치관리 (control depth=3)
 *             └── 패치 관리 내역     [수동 업로드]
 * </pre>
 *
 * <p>spec §3.3.1.1 결정 #16 (v18 갱신) 정합 — view 모드 렌더링이 depth 기반.
 * depth 1-2 = 카테고리 스타일(자식 ControlNode 표시), depth 3+ = leaf 스타일
 * (EvidenceType 표시). 통제를 depth=3 에 두어야 ControlsView 에서 EvidenceType
 * (화면 용어 "관리 항목") 이 정상 렌더링됨.</p>
 *
 * <h3>재실행 정책</h3>
 * <p>{@code frameworkRepository.count() > 0} 시 skip. 시드 갱신은 DB drop 수동.</p>
 */
@Slf4j
@Component
@Profile("dev")
@Order(2)
@RequiredArgsConstructor
public class EvidenceDataInitializer implements CommandLineRunner {

    private final FrameworkRepository frameworkRepository;
    private final ControlNodeRepository controlNodeRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final EvidenceFileRepository evidenceFileRepository;
    private final EvidenceAssetRepository evidenceAssetRepository;
    private final UserRepository userRepository;

    @Value("${app.storage.path:./storage}")
    private String storagePath;

    // ====================================================================
    // 메인 — run
    // ====================================================================

    @Override
    public void run(String... args) {
        if (frameworkRepository.count() > 0) {
            log.info("증빙 데모 데이터가 이미 존재합니다. 초기화를 건너뜁니다.");
            return;
        }

        log.info("ISMS-2025 증빙 데모 데이터 초기화 시작 (발표용 실데이터)...");

        // 발표용 단일 계정 — admin 만 존재. 모든 owner/업로더는 admin 으로 통일.
        User admin = userRepository.findByEmail("admin@company.com").orElse(null);

        LocalDate today = LocalDate.now();

        // ====================================================================
        // Framework
        // ====================================================================
        Framework ismsp = frameworkRepository.save(Framework.builder()
                .name("ISMS-2025")
                .description("정보보호 및 개인정보보호 관리체계 인증 (2025)")
                .status(FrameworkStatus.active)
                .build());

        // ====================================================================
        // 1. 관리체계 수립 및 운영
        // ====================================================================
        ControlNode cat1 = saveCat(ismsp, null, "1", "관리체계 수립 및 운영", 0, 1);
        ControlNode cat11 = saveCat(ismsp, cat1, "1.1", "관리체계 기반 마련", 0, 2);

        // ── 1.1.1 경영진의 참여 — 증빙 유형 2개 (각 1 파일, 수동 업로드)
        ControlNode c111 = saveCtrl(ismsp, cat11, "1.1.1", "경영진의 참여", 0, 3);

        EvidenceType et_mgmtPart = saveEt(c111, "경영진 참여 증적", admin, today.plusDays(60));
        saveLinkText(et_mgmtPart, "경영진 참여 증적.txt", mgmtParticipationContent(),
                1, CollectionMethod.manual, LocalDateTime.now().minusMonths(2), admin);

        EvidenceType et_mtgReport = saveEt(c111, "개최 결과 보고", admin, today.plusDays(60));
        saveLinkText(et_mtgReport, "개최 결과 보고.txt", meetingReportContent(),
                1, CollectionMethod.manual, LocalDateTime.now().minusMonths(2), admin);

        // ── 1.1.2 최고책임자의 지정 — 증빙 유형 1개 (수동 업로드)
        ControlNode c112 = saveCtrl(ismsp, cat11, "1.1.2", "최고책임자의 지정", 1, 3);

        EvidenceType et_ciso = saveEt(c112, "CISO·CPO 지정", admin, today.plusDays(90));
        saveLinkText(et_ciso, "CISO 및 CPO의 주요활동은 1.1.1 증적자료 참고.txt", cisoCpoContent(),
                1, CollectionMethod.manual, LocalDateTime.now().minusMonths(3), admin);

        // ====================================================================
        // 2. 보호대책 요구사항
        // ====================================================================
        ControlNode cat2 = saveCat(ismsp, null, "2", "보호대책 요구사항", 1, 1);

        // ── 2.6 접근통제 > 2.6.1 네트워크 접근
        ControlNode cat26 = saveCat(ismsp, cat2, "2.6", "접근통제", 0, 2);
        ControlNode c261 = saveCtrl(ismsp, cat26, "2.6.1", "네트워크 접근", 0, 3);

        // 네트워크 구성도 — 증적 없음. ISMS-2026 복제본에서 발표자가 직접 스크립트를
        // 작성하여 자동 수집을 라이브로 시연하는 타겟. (Job/Script/파일 시드 없음)
        saveEt(c261, "네트워크 구성도", admin, today.plusDays(30));

        // ── 2.10 시스템 및 서비스 보안관리 > 2.10.8 패치관리
        ControlNode cat210 = saveCat(ismsp, cat2, "2.10", "시스템 및 서비스 보안관리", 1, 2);
        ControlNode c2108 = saveCtrl(ismsp, cat210, "2.10.8", "패치관리", 0, 3);

        EvidenceType et_patch = saveEt(c2108, "패치 관리 내역", admin, today.plusDays(45));
        saveLinkText(et_patch, "패치 관리 내역.txt", patchMgmtContent(),
                1, CollectionMethod.manual, LocalDateTime.now().minusDays(7), admin);

        log.info("ISMS-2025 증빙 데모 데이터 초기화 완료 — Framework: {}, 통제 노드: {}, " +
                        "EvidenceType: {}, EvidenceFile: {}, Asset: {}",
                ismsp.getName(),
                controlNodeRepository.count(),
                evidenceTypeRepository.count(),
                evidenceFileRepository.count(),
                evidenceAssetRepository.count());
    }

    // ====================================================================
    // 헬퍼 — ControlNode / EvidenceType
    // ====================================================================

    private ControlNode saveCat(Framework fw, ControlNode parent, String code, String name,
                                int displayOrder, int depth) {
        return controlNodeRepository.save(ControlNode.builder()
                .framework(fw)
                .parent(parent)
                .nodeType(NodeType.category)
                .code(code)
                .name(name)
                .displayOrder(displayOrder)
                .depth(depth)
                .build());
    }

    private ControlNode saveCtrl(Framework fw, ControlNode parent, String code, String name,
                                  int displayOrder, int depth) {
        return controlNodeRepository.save(ControlNode.builder()
                .framework(fw)
                .parent(parent)
                .nodeType(NodeType.control)
                .code(code)
                .name(name)
                .displayOrder(displayOrder)
                .depth(depth)
                .build());
    }

    private EvidenceType saveEt(ControlNode ctrl, String name, User owner, LocalDate dueDate) {
        return evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl)
                .name(name)
                .ownerUser(owner)
                .dueDate(dueDate)
                .build());
    }

    // ====================================================================
    // 헬퍼 — 수동 업로드 (txt)
    // ====================================================================

    private EvidenceFile saveLinkText(EvidenceType et, String fileName, String content,
                                       int version, CollectionMethod method,
                                       LocalDateTime collectedAt, User uploader) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        EvidenceAsset asset = createAsset(fileName, bytes, uploader);
        return linkEvidenceFile(et, asset, fileName, version, method, collectedAt, uploader);
    }

    private EvidenceAsset createAsset(String originalFileName, byte[] content, User uploader) {
        EvidenceAsset asset = evidenceAssetRepository.save(EvidenceAsset.builder()
                .sha256(sha256(content))
                .filePath("PENDING")
                .fileSize((long) content.length)
                .originalFileName(originalFileName)
                .uploadedBy(uploader)
                .build());

        Path absolutePath = Paths.get(storagePath, EvidenceAsset.buildRelativePath(asset.getId()))
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolutePath.getParent());
            Files.write(absolutePath, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("asset 물리 파일 저장 실패 (dev 시드): id={}, path={}, error={}",
                    asset.getId(), absolutePath, e.getMessage());
        }

        asset.updateFilePath(absolutePath.toString());
        return evidenceAssetRepository.save(asset);
    }

    private EvidenceFile linkEvidenceFile(EvidenceType et, EvidenceAsset asset,
                                           String fileName, int version,
                                           CollectionMethod method, LocalDateTime collectedAt,
                                           User uploader) {
        EvidenceFile.EvidenceFileBuilder b = EvidenceFile.builder()
                .evidenceType(et)
                .asset(asset)
                .fileName(fileName)
                .filePath(asset.getFilePath())
                .fileSize(asset.getFileSize())
                .version(version)
                .collectionMethod(method)
                .collectedAt(collectedAt)
                // 검토(승인/반려) 워크플로우 미구현 — 중립 상태(auto_approved)로 시드.
                .reviewStatus(ReviewStatus.auto_approved);

        if (uploader != null) b.uploadedBy(uploader);
        else if (et.getOwnerUser() != null && method == CollectionMethod.manual) {
            b.uploadedBy(et.getOwnerUser());
        }

        return evidenceFileRepository.save(b.build());
    }

    // ====================================================================
    // 콘텐츠 (한국어, txt)
    // ====================================================================

    private String mgmtParticipationContent() {
        return "정보보호 및 개인정보보호 위원회 회의록\n\n" +
                "■ 회의 개요\n" +
                "  - 회의명 : 2025년 상반기 정보보호 위원회\n" +
                "  - 일시   : 2025-03-14 (금) 14:00 ~ 16:00\n" +
                "  - 장소   : 본사 3층 대회의실\n" +
                "  - 주관   : 정보보호 최고책임자(CISO)\n\n" +
                "■ 참석자\n" +
                "  - 대표이사 (위원장)\n" +
                "  - 정보보호 최고책임자(CISO)\n" +
                "  - 개인정보 보호책임자(CPO)\n" +
                "  - 정보보호팀장 및 각 본부장\n\n" +
                "■ 주요 안건\n" +
                "  1. 2024년 정보보호 활동 실적 보고\n" +
                "  2. 2025년 정보보호 정책 및 투자 계획 심의\n" +
                "  3. 주요 위험 평가 결과 및 대응 방안 검토\n" +
                "  4. ISMS-P 인증 추진 계획 승인\n\n" +
                "■ 의결 사항\n" +
                "  - 2025년 정보보호 예산 승인 (전년 대비 18% 증액)\n" +
                "  - 정보보호 조직 보강 (보안팀 2명 충원) 승인\n" +
                "  - ISMS-P 인증 심사 2025년 4분기 추진 승인\n\n" +
                "경영진이 정보보호 활동에 직접 참여하여 주요 정책과 투자를 심의·의결하였음.\n\n" +
                "---\n" +
                "작성 : 정보보호팀  |  승인 : CISO  |  보고일 : 2025-03-14\n";
    }

    private String meetingReportContent() {
        return "정보보호 위원회 개최 결과 보고\n\n" +
                "수신 : 대표이사\n" +
                "참조 : CPO, 각 본부장\n" +
                "제목 : 2025년 상반기 정보보호 위원회 개최 결과 보고의 건\n\n" +
                "1. 개최 일시 : 2025-03-14 (금) 14:00\n" +
                "2. 경영진 참석 : 대표이사 외 6명 (참석률 100%)\n" +
                "3. 심의 안건 : 총 4건 (전건 의결)\n" +
                "4. 후속 조치\n" +
                "   - 정보보호 예산 집행 계획 수립 (3월 내)\n" +
                "   - 보안팀 충원 공고 게시 (4월)\n" +
                "   - ISMS-P 인증 컨설팅 착수 (2분기)\n\n" +
                "경영진이 정보보호 의사결정에 직접 참여하였으며, 상정된 안건이 전건\n" +
                "원안대로 의결되었음을 보고합니다.\n\n" +
                "---\n" +
                "보고 : 정보보호팀장  |  일자 : 2025-03-17\n";
    }

    private String cisoCpoContent() {
        return "최고책임자(CISO / CPO) 지정 증적\n\n" +
                "■ 지정 현황\n" +
                "  - 정보보호 최고책임자(CISO) : 정보보호본부장\n" +
                "      · 지정일 : 2024-01-02 (대표이사 지정 공문 제2024-001호)\n" +
                "  - 개인정보 보호책임자(CPO)  : 경영지원본부장\n" +
                "      · 지정일 : 2024-01-02 (대표이사 지정 공문 제2024-002호)\n\n" +
                "■ 주요 활동\n" +
                "  CISO 및 CPO 의 주요 활동(위원회 주관·심의·의결)은\n" +
                "  「1.1.1 경영진의 참여」 증적자료(위원회 회의록 및 개최 결과 보고)를\n" +
                "  참고하시기 바랍니다.\n\n" +
                "■ 권한과 책임\n" +
                "  - 정보보호 정책 수립 및 시행 총괄\n" +
                "  - 정보보호 예산 및 인력 운영 책임\n" +
                "  - 침해사고 대응 최종 의사결정\n\n" +
                "---\n" +
                "작성 : 정보보호팀  |  일자 : 2024-01-02\n";
    }

    private String patchMgmtContent() {
        return "패치 관리 내역\n\n" +
                "작성 기준일 : " + LocalDate.now() + "\n" +
                "대상       : 운영 서버 및 보안 장비\n" +
                "================================================\n\n" +
                "[적용 완료]\n" +
                "  2025-05-09 | web-prod-01 | OS 보안 패치 (월 정기)        | 정상\n" +
                "  2025-05-09 | web-prod-02 | OS 보안 패치 (월 정기)        | 정상\n" +
                "  2025-05-10 | db-prod-01  | MariaDB 보안 업데이트         | 정상\n" +
                "  2025-05-12 | api-prod-01 | OpenJDK 21 보안 패치          | 정상\n\n" +
                "[예정]\n" +
                "  2025-06-13 | fw-main     | 방화벽 펌웨어 업데이트         | 예정\n\n" +
                "[패치 정책]\n" +
                "  - 긴급(Critical) 패치 : 공지 후 72시간 이내 적용\n" +
                "  - 정기 패치          : 매월 둘째 주 금요일\n" +
                "  - 적용 전 스테이징 환경 검증 필수\n\n" +
                "---\n" +
                "작성 : 시스템운영팀  |  검토 : 보안팀\n";
    }

    // ====================================================================
    // sha256
    // ====================================================================

    private String sha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm 부재", e);
        }
    }
}