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
 * dev 프로필 데모 데이터 — 증빙 수집 관련
 * DataInitializer(계정) 이후에 실행됩니다.
 */
@Slf4j
@Component
@Profile("dev")
@Order(2)
@RequiredArgsConstructor
public class EvidenceDataInitializer implements CommandLineRunner {

    private final FrameworkRepository frameworkRepository;
    private final ControlRepository controlRepository;
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

        // 통제항목 + 증빙 유형
        Control c111 = createControl(ismsp, "1.1.1", "관리체계 수립", "정보보호 정책 수립",
                "정보보호 정책서", "개인정보 처리방침", "정보보호 조직도");

        Control c112 = createControl(ismsp, "1.1.2", "관리체계 수립", "최고책임자의 지정",
                "CISO 임명장", "정보보호위원회 회의록");

        Control c121 = createControl(ismsp, "1.2.1", "보호대책 요구사항", "정보자산 식별",
                "정보자산 목록", "자산 분류 기준서");

        Control c131 = createControl(ismsp, "1.3.1", "관리체계 운영", "보호대책 구현",
                "접근통제 정책서", "접근권한 현황");

        Control c211 = createControl(ismsp, "2.1.1", "인적 보안", "보안 서약",
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

    private Control createControl(Framework fw, String code, String domain, String name, String... evidenceTypeNames) {
        Control control = controlRepository.save(Control.builder()
                .framework(fw)
                .code(code)
                .domain(domain)
                .name(name)
                .build());

        for (String etName : evidenceTypeNames) {
            evidenceTypeRepository.save(EvidenceType.builder()
                    .control(control)
                    .name(etName)
                    .build());
        }

        return control;
    }

    private void addDemoFile(Control control, String etName, String fileName, long fileSize,
                             int version, CollectionMethod method, LocalDateTime collectedAt) {
        EvidenceType et = findEvidenceType(control, etName);
        if (et == null) return;

        evidenceFileRepository.save(EvidenceFile.builder()
                .evidenceType(et)
                .fileName(fileName)
                .filePath("/storage/evidence/" + control.getCode() + "/" + fileName)
                .fileSize(fileSize)
                .version(version)
                .collectionMethod(method)
                .collectedAt(collectedAt)
                .build());
    }

    private EvidenceType findEvidenceType(Control control, String name) {
        return evidenceTypeRepository.findByControlId(control.getId()).stream()
                .filter(et -> et.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
}
