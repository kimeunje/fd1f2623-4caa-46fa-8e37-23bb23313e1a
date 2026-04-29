package com.secuhub.config;

import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * dev 프로필 데모 데이터 — 증빙 수집 관련.
 *
 * <p>DataInitializer(계정) 이후에 실행됩니다.</p>
 *
 * <h3>v14 Phase 5-14f — control_nodes 트리 모델로 전환</h3>
 *
 * <p>5-14f 에서 {@link EvidenceType#getControl()} 의 타입이 {@link Control} →
 * {@link ControlNode} 로 변경됨. dev 데모 데이터도 V6 마이그레이션의 결과를
 * 모방하여 {@code domain} 텍스트별 depth=1 category + 통제 depth=2 leaf 의
 * 2단 트리로 생성한다.</p>
 *
 * <p>spec §6.4 의 V6 마이그레이션 SQL 정합:</p>
 * <ul>
 *   <li>category 의 {@code code} / {@code name} = domain 텍스트 그대로</li>
 *   <li>category 의 {@code displayOrder} = framework 안 domain 첫 등장 순서</li>
 *   <li>leaf 의 {@code parent} = 해당 domain 의 category</li>
 *   <li>leaf 의 {@code displayOrder} = 같은 category 안 leaf 등장 순서</li>
 * </ul>
 *
 * <p>{@link ControlRepository} 는 5-14f 에서 더 이상 사용 안 함 ({@code Control} 신규
 * INSERT 는 5-14b 부터 410 Gone 차단됨). 의존성에서 제거.</p>
 */
@Slf4j
@Component
@Profile("dev")
@Order(2)
@RequiredArgsConstructor
public class EvidenceDataInitializer implements CommandLineRunner {

    private final FrameworkRepository frameworkRepository;
    // v14 Phase 5-14f: ControlRepository 제거 — controls 테이블 INSERT 차단됨
    private final ControlNodeRepository controlNodeRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final EvidenceFileRepository evidenceFileRepository;
    private final CollectionJobRepository collectionJobRepository;
    private final JobExecutionRepository jobExecutionRepository;

    @Override
    public void run(String... args) {
        if (frameworkRepository.count() > 0) {
            log.info("증빙 데모 데이터가 이미 존재합니다. 초기화를 건너뜁니다.");
            return;
        }

        log.info("증빙 데모 데이터 초기화 시작...");

        // 프레임워크
        Framework ismsp = frameworkRepository.save(Framework.builder()
                .name("ISMS-P")
                .description("정보보호 및 개인정보보호 관리체계 인증")
                .build());

        // v14 Phase 5-14f — depth=1 category 노드 (domain 별, V6 마이그레이션 패턴 모방)
        // displayOrder 는 domain 의 첫 등장 순서대로 0, 1, 2, 3
        ControlNode catManagement = saveCategory(ismsp, "관리체계 수립", 0);
        ControlNode catProtection = saveCategory(ismsp, "보호대책 요구사항", 1);
        ControlNode catOperation = saveCategory(ismsp, "관리체계 운영", 2);
        ControlNode catPersonnel = saveCategory(ismsp, "인적 보안", 3);

        // depth=2 leaf (통제) + 증빙 유형
        ControlNode c111 = createLeaf(ismsp, catManagement, "1.1.1", "정보보호 정책 수립", 0,
                "정보보호 정책서", "개인정보 처리방침", "정보보호 조직도");

        ControlNode c112 = createLeaf(ismsp, catManagement, "1.1.2", "최고책임자의 지정", 1,
                "CISO 임명장", "정보보호위원회 회의록");

        ControlNode c121 = createLeaf(ismsp, catProtection, "1.2.1", "정보자산 식별", 0,
                "정보자산 목록", "자산 분류 기준서");

        ControlNode c131 = createLeaf(ismsp, catOperation, "1.3.1", "보호대책 구현", 0,
                "접근통제 정책서", "접근권한 현황");

        ControlNode c211 = createLeaf(ismsp, catPersonnel, "2.1.1", "보안 서약", 0,
                "비밀유지 서약서 수집 현황");

        // 증빙 파일 (일부 데모)
        addDemoFile(c111, "정보보호 정책서", "정보보호_정책서_v1.pdf", 1800000L, 1,
                CollectionMethod.manual, LocalDateTime.of(2024, 7, 1, 10, 0));
        addDemoFile(c111, "정보보호 정책서", "정보보호_정책서_v2.pdf", 2400000L, 2,
                CollectionMethod.auto, LocalDateTime.of(2025, 1, 15, 10, 0));
        addDemoFile(c111, "개인정보 처리방침", "개인정보_처리방침_v1.pdf", 1200000L, 1,
                CollectionMethod.manual, LocalDateTime.of(2025, 1, 10, 14, 0));

        addDemoFile(c112, "CISO 임명장", "CISO_임명장.pdf", 500000L, 1,
                CollectionMethod.manual, LocalDateTime.of(2024, 3, 1, 9, 0));

        addDemoFile(c121, "정보자산 목록", "정보자산_목록_2025Q1.xlsx", 3500000L, 1,
                CollectionMethod.auto, LocalDateTime.of(2025, 2, 1, 18, 0));

        // 수집 작업
        EvidenceType accessRightsEt = findEvidenceType(c131, "접근권한 현황");
        if (accessRightsEt != null) {
            CollectionJob job1 = collectionJobRepository.save(CollectionJob.builder()
                    .name("접근권한 현황 추출")
                    .description("보안 시스템에서 접근권한 목록을 자동 추출합니다.")
                    .jobType(JobType.excel_extract)
                    .scriptPath("/scripts/access_rights.py")
                    .evidenceType(accessRightsEt)
                    .scheduleCron("0 0 18 * * ?")
                    .build());

            // 실행 이력
            JobExecution exec1 = jobExecutionRepository.save(JobExecution.builder()
                    .job(job1)
                    .status(ExecutionStatus.success)
                    .startedAt(LocalDateTime.of(2025, 3, 1, 18, 0))
                    .finishedAt(LocalDateTime.of(2025, 3, 1, 18, 2))
                    .build());
        }

        EvidenceType assetListEt = findEvidenceType(c121, "정보자산 목록");
        if (assetListEt != null) {
            collectionJobRepository.save(CollectionJob.builder()
                    .name("정보자산 목록 웹 스크래핑")
                    .description("자산관리 시스템에서 정보자산 목록을 수집합니다.")
                    .jobType(JobType.web_scraping)
                    .scriptPath("/scripts/asset_scraper.py")
                    .evidenceType(assetListEt)
                    .scheduleCron("0 0 6 1 * ?")
                    .build());
        }

        collectionJobRepository.save(CollectionJob.builder()
                .name("서버 접근 로그 추출")
                .description("서버 접근 로그를 월별로 추출합니다.")
                .jobType(JobType.log_extract)
                .scriptPath("/scripts/server_logs.sh")
                .scheduleCron("0 0 1 1 * ?")
                .build());

        log.info("증빙 데모 데이터 초기화 완료");
    }

    // ====================================================================
    // v14 Phase 5-14f — control_nodes 트리 헬퍼
    // ====================================================================

    /**
     * depth=1 category 노드 생성 (V6 마이그레이션의 distinct domain → category 패턴).
     *
     * <p>code 와 name 모두 {@code domain} 텍스트 그대로 (V6 SQL 정합).</p>
     */
    private ControlNode saveCategory(Framework fw, String domain, int displayOrder) {
        return controlNodeRepository.save(ControlNode.builder()
                .framework(fw)
                .parent(null)
                .nodeType(NodeType.category)
                .code(domain)
                .name(domain)
                .displayOrder(displayOrder)
                .depth(1)
                .build());
    }

    /**
     * depth=2 leaf (통제) 노드 생성 + 증빙 유형 매달기.
     *
     * <p>{@link EvidenceType#getControl()} 의 타입이 {@link ControlNode} 로 변경됨에 따라
     * builder 의 {@code .control(leaf)} 호출이 자연 매칭.</p>
     */
    private ControlNode createLeaf(Framework fw, ControlNode parent, String code, String name,
                                   int displayOrder, String... evidenceTypeNames) {
        ControlNode leaf = controlNodeRepository.save(ControlNode.builder()
                .framework(fw)
                .parent(parent)
                .nodeType(NodeType.control)
                .code(code)
                .name(name)
                .displayOrder(displayOrder)
                .depth(2)
                .build());

        for (String etName : evidenceTypeNames) {
            evidenceTypeRepository.save(EvidenceType.builder()
                    .control(leaf)            // v14 Phase 5-14f: ControlNode 직접 전달
                    .name(etName)
                    .build());
        }

        return leaf;
    }

    /**
     * leaf 에 증빙 파일 데모 추가.
     *
     * <p>v14 Phase 5-14f: 시그니처 {@link Control} → {@link ControlNode} 변경.
     * file_path 의 prefix 는 leaf 의 {@code code} 그대로 사용 (기존 패턴 보존).</p>
     */
    private void addDemoFile(ControlNode leaf, String etName, String fileName, long fileSize,
                             int version, CollectionMethod method, LocalDateTime collectedAt) {
        EvidenceType et = findEvidenceType(leaf, etName);
        if (et == null) return;

        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName(fileName)
                .filePath("/storage/evidence/" + leaf.getCode() + "/" + fileName)
                .fileSize(fileSize)
                .version(version)
                .collectionMethod(method)
                .collectedAt(collectedAt)
                .build());
    }

    /**
     * leaf 의 evidence_types 중 이름 매칭으로 1개 조회.
     *
     * <p>v14 Phase 5-14f: 시그니처 {@link Control} → {@link ControlNode} 변경.
     * {@link EvidenceTypeRepository#findByControlId} 의 시그니처는 그대로 (Long 받음) —
     * 5-14f 후 ControlNode.id 자연 매칭.</p>
     */
    private EvidenceType findEvidenceType(ControlNode leaf, String name) {
        return evidenceTypeRepository.findByControlId(leaf.getId()).stream()
                .filter(et -> et.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
}