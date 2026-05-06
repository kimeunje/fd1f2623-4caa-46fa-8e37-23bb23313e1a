package com.secuhub.config;

import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * dev 프로필 데모 데이터 — 증빙 수집 관련.
 *
 * <p>DataInitializer(계정) 이후에 실행됩니다 (@Order(2)).</p>
 *
 * <h3>v17 리팩터링 — ISMS-P 실제 구조 기반 3~4단 트리</h3>
 * <ul>
 *   <li>depth=1 대분류 (category) — 1. 관리체계 수립, 2. 보호대책 요구사항, 3. 개인정보 처리</li>
 *   <li>depth=2 중분류 (category) — 1.1 관리체계 기반 마련, 1.2 위험 관리, ...</li>
 *   <li>depth=3 통제항목 (control) — 1.1.1 정보보호 정책 수립, ...</li>
 *   <li>depth=4 하위통제 (hybrid) — 1.1.4.1 테스트 항목 (자식+증빙 동시 보유)</li>
 *   <li>다양한 증빙 상태: 완료 / 진행중 / 미수집 / 검토 대기</li>
 *   <li>담당자 배정 + 마감일 + 검토 플로우 데모</li>
 * </ul>
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
    private final CollectionJobRepository collectionJobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final UserRepository userRepository;

    @Override
    public void run(String... args) {
        if (frameworkRepository.count() > 0) {
            log.info("증빙 데모 데이터가 이미 존재합니다. 초기화를 건너뜁니다.");
            return;
        }

        log.info("증빙 데모 데이터 초기화 시작...");

        // 사용자 조회 (DataInitializer 에서 생성)
        User admin = userRepository.findByEmail("admin@company.com").orElse(null);
        User parkTl = userRepository.findByEmail("park_tl@company.com").orElse(null);
        User kimDev = userRepository.findByEmail("kim@company.com").orElse(null);
        User leeDev = userRepository.findByEmail("lee@company.com").orElse(null);

        // ====================================================================
        // Framework
        // ====================================================================
        Framework ismsp = frameworkRepository.save(Framework.builder()
                .name("ISMS-P 2026")
                .description("정보보호 및 개인정보보호 관리체계 인증")
                .build());

        // ====================================================================
        // depth=1 대분류 (category)
        // ====================================================================
        ControlNode cat1 = saveCat(ismsp, null, "1", "관리체계 수립", 0, 1);
        ControlNode cat2 = saveCat(ismsp, null, "2", "보호대책 요구사항", 1, 1);
        ControlNode cat3 = saveCat(ismsp, null, "3", "개인정보 처리", 2, 1);

        // ====================================================================
        // depth=2 중분류 (category) — 1.x
        // ====================================================================
        ControlNode cat11 = saveCat(ismsp, cat1, "1.1", "관리체계 기반 마련", 0, 2);
        ControlNode cat12 = saveCat(ismsp, cat1, "1.2", "위험 관리", 1, 2);
        ControlNode cat13 = saveCat(ismsp, cat1, "1.3", "관리체계 운영", 2, 2);

        // depth=2 중분류 — 2.x
        ControlNode cat21 = saveCat(ismsp, cat2, "2.1", "인적 보안", 0, 2);
        ControlNode cat22 = saveCat(ismsp, cat2, "2.2", "물리적 보안", 1, 2);
        ControlNode cat23 = saveCat(ismsp, cat2, "2.3", "접근통제", 2, 2);

        // depth=2 중분류 — 3.x
        ControlNode cat31 = saveCat(ismsp, cat3, "3.1", "개인정보 수집", 0, 2);
        ControlNode cat32 = saveCat(ismsp, cat3, "3.2", "개인정보 보유·이용", 1, 2);

        // ====================================================================
        // depth=3 통제 (control) — 1.1.x 관리체계 기반 마련
        // ====================================================================

        // ── 1.1.1 정보보호 정책 수립 → 완료 (3증빙, 3수집)
        ControlNode c111 = saveCtrl(ismsp, cat11, "1.1.1", "정보보호 정책 수립", 0, 3);
        EvidenceType et_policy = saveEt(c111, "정보보호 정책서", admin, LocalDate.of(2026, 6, 30));
        EvidenceType et_privacy = saveEt(c111, "개인정보 처리방침", parkTl, LocalDate.of(2026, 6, 30));
        EvidenceType et_orgchart = saveEt(c111, "정보보호 조직도", admin, null);
        // 파일: 모두 수집 완료
        saveFile(et_policy, "정보보호_정책서_v2.pdf", 2400000L, 2, CollectionMethod.manual,
                LocalDateTime.of(2026, 1, 15, 10, 0), ReviewStatus.approved, admin);
        saveFile(et_privacy, "개인정보_처리방침_v1.pdf", 1200000L, 1, CollectionMethod.manual,
                LocalDateTime.of(2026, 1, 10, 14, 0), ReviewStatus.approved, admin);
        saveFile(et_orgchart, "조직도_2026.pdf", 800000L, 1, CollectionMethod.auto,
                LocalDateTime.of(2026, 2, 1, 9, 0), ReviewStatus.auto_approved, null);

        // ── 1.1.2 최고책임자의 지정 → 진행중 (2증빙, 1수집)
        ControlNode c112 = saveCtrl(ismsp, cat11, "1.1.2", "최고책임자의 지정", 1, 3);
        EvidenceType et_ciso = saveEt(c112, "CISO 임명장", admin, LocalDate.of(2026, 3, 31));
        EvidenceType et_minutes = saveEt(c112, "정보보호위원회 회의록", parkTl, LocalDate.of(2026, 7, 31));
        saveFile(et_ciso, "CISO_임명장.pdf", 500000L, 1, CollectionMethod.manual,
                LocalDateTime.of(2026, 3, 1, 9, 0), ReviewStatus.approved, admin);
        // et_minutes 미수집

        // ── 1.1.3 조직 구성 → 완료 (2증빙, 2수집)
        ControlNode c113 = saveCtrl(ismsp, cat11, "1.1.3", "조직 구성", 2, 3);
        EvidenceType et_committee = saveEt(c113, "정보보호 위원회 운영 규정", leeDev, null);
        EvidenceType et_org = saveEt(c113, "정보보호 조직도", admin, null);
        saveFile(et_committee, "위원회_운영규정_v3.pdf", 1500000L, 3, CollectionMethod.auto,
                LocalDateTime.of(2026, 2, 15, 10, 0), ReviewStatus.auto_approved, null);
        saveFile(et_org, "조직도_v2.pdf", 900000L, 2, CollectionMethod.manual,
                LocalDateTime.of(2026, 3, 10, 11, 0), ReviewStatus.auto_approved, null);

        // ── 1.1.4 범위 설정 → 진행중 (3증빙, 2수집) + hybrid (자식 보유)
        ControlNode c114 = saveCtrl(ismsp, cat11, "1.1.4", "범위 설정", 3, 3);
        EvidenceType et_scope = saveEt(c114, "정보보호 범위 정의서", admin, LocalDate.of(2026, 5, 31));
        EvidenceType et_asset_cls = saveEt(c114, "자산 분류 기준 문서", leeDev, null);
        EvidenceType et_scope_hist = saveEt(c114, "범위 변경 이력", parkTl, LocalDate.of(2026, 8, 31));
        saveFile(et_scope, "범위정의서_v2.pdf", 1100000L, 2, CollectionMethod.manual,
                LocalDateTime.of(2026, 1, 20, 14, 0), ReviewStatus.approved, admin);
        saveFile(et_asset_cls, "자산분류기준_v1.pdf", 700000L, 1, CollectionMethod.manual,
                LocalDateTime.of(2026, 2, 5, 10, 0), ReviewStatus.auto_approved, null);
        // et_scope_hist 미수집

        // ── 1.1.4 하위 — depth=4 (hybrid 자식)
        ControlNode c1141 = saveCtrl(ismsp, c114, "1.1.4.1", "외부 서비스 범위", 0, 4);
        EvidenceType et_ext_svc = saveEt(c1141, "외부 서비스 현황표", kimDev, LocalDate.of(2026, 6, 30));
        saveFile(et_ext_svc, "외부서비스_현황_v1.xlsx", 450000L, 1, CollectionMethod.auto,
                LocalDateTime.of(2026, 3, 1, 18, 0), ReviewStatus.auto_approved, null);

        ControlNode c1142 = saveCtrl(ismsp, c114, "1.1.4.2", "내부 서비스 범위", 1, 4);
        EvidenceType et_int_svc = saveEt(c1142, "내부 서비스 목록", kimDev, null);
        // 미수집

        // ── 1.1.5 정책 수립 → 미수집 (1증빙, 0수집)
        ControlNode c115 = saveCtrl(ismsp, cat11, "1.1.5", "정책 수립", 4, 3);
        saveEt(c115, "정책 관리 대장", parkTl, LocalDate.of(2026, 9, 30));

        // ── 1.1.6 자원 배정 → 검토 대기 (2증빙, 1수집 + 1 pending)
        ControlNode c116 = saveCtrl(ismsp, cat11, "1.1.6", "자원 배정", 5, 3);
        EvidenceType et_budget = saveEt(c116, "정보보호 예산 편성 계획", admin, LocalDate.of(2026, 4, 30));
        EvidenceType et_hr = saveEt(c116, "전담 인력 현황", parkTl, LocalDate.of(2026, 5, 31));
        saveFile(et_budget, "예산편성_2026_v1.pdf", 2100000L, 1, CollectionMethod.manual,
                LocalDateTime.of(2026, 4, 1, 10, 0), ReviewStatus.approved, admin);
        // et_hr: pending review
        saveFile(et_hr, "전담인력_현황_v1.xlsx", 350000L, 1, CollectionMethod.manual,
                LocalDateTime.of(2026, 4, 10, 15, 0), ReviewStatus.pending, null);

        // ====================================================================
        // depth=3 통제 — 1.2.x 위험 관리
        // ====================================================================
        ControlNode c121 = saveCtrl(ismsp, cat12, "1.2.1", "정보자산 식별", 0, 3);
        EvidenceType et_asset_list = saveEt(c121, "정보자산 목록", kimDev, LocalDate.of(2026, 6, 30));
        EvidenceType et_asset_std = saveEt(c121, "자산 분류 기준서", admin, null);
        saveFile(et_asset_list, "정보자산_목록_2026Q1.xlsx", 3500000L, 1, CollectionMethod.auto,
                LocalDateTime.of(2026, 2, 1, 18, 0), ReviewStatus.auto_approved, null);
        saveFile(et_asset_std, "자산분류기준서_v1.pdf", 1800000L, 1, CollectionMethod.manual,
                LocalDateTime.of(2026, 1, 25, 9, 0), ReviewStatus.approved, admin);

        ControlNode c122 = saveCtrl(ismsp, cat12, "1.2.2", "현황 및 흐름 분석", 1, 3);
        saveEt(c122, "개인정보 흐름도", leeDev, LocalDate.of(2026, 7, 31));
        saveEt(c122, "정보시스템 현황표", kimDev, null);

        ControlNode c123 = saveCtrl(ismsp, cat12, "1.2.3", "위험 평가", 2, 3);
        EvidenceType et_risk = saveEt(c123, "위험 평가 보고서", admin, LocalDate.of(2026, 8, 31));
        saveFile(et_risk, "위험평가_2026_v1.pdf", 4200000L, 1, CollectionMethod.manual,
                LocalDateTime.of(2026, 3, 15, 14, 0), ReviewStatus.pending, null);

        // ====================================================================
        // depth=3 통제 — 1.3.x 관리체계 운영
        // ====================================================================
        ControlNode c131 = saveCtrl(ismsp, cat13, "1.3.1", "보호대책 구현", 0, 3);
        EvidenceType et_access_pol = saveEt(c131, "접근통제 정책서", admin, null);
        EvidenceType et_access_cur = saveEt(c131, "접근권한 현황", kimDev, LocalDate.of(2026, 6, 30));
        saveFile(et_access_pol, "접근통제_정책서_v2.pdf", 1600000L, 2, CollectionMethod.manual,
                LocalDateTime.of(2025, 12, 20, 10, 0), ReviewStatus.approved, admin);

        ControlNode c132 = saveCtrl(ismsp, cat13, "1.3.2", "운영현황 관리", 1, 3);
        saveEt(c132, "정보보호 운영 현황 보고서", parkTl, LocalDate.of(2026, 12, 31));

        // ====================================================================
        // depth=3 통제 — 2.1.x 인적 보안
        // ====================================================================
        ControlNode c211 = saveCtrl(ismsp, cat21, "2.1.1", "보안 서약", 0, 3);
        EvidenceType et_oath = saveEt(c211, "비밀유지 서약서 수집 현황", parkTl, LocalDate.of(2026, 3, 31));
        saveFile(et_oath, "서약서_현황_2026.xlsx", 280000L, 1, CollectionMethod.manual,
                LocalDateTime.of(2026, 3, 5, 11, 0), ReviewStatus.approved, admin);

        ControlNode c212 = saveCtrl(ismsp, cat21, "2.1.2", "보안 교육", 1, 3);
        saveEt(c212, "보안 교육 이수 현황", leeDev, LocalDate.of(2026, 6, 30));
        saveEt(c212, "교육 자료 (최신본)", admin, null);

        // ====================================================================
        // depth=3 통제 — 2.2.x 물리적 보안
        // ====================================================================
        ControlNode c221 = saveCtrl(ismsp, cat22, "2.2.1", "출입 통제", 0, 3);
        EvidenceType et_entry = saveEt(c221, "출입 기록 월간 보고서", kimDev, LocalDate.of(2026, 5, 31));
        saveFile(et_entry, "출입기록_2026_03.pdf", 1900000L, 1, CollectionMethod.auto,
                LocalDateTime.of(2026, 4, 1, 6, 0), ReviewStatus.auto_approved, null);

        // ====================================================================
        // depth=3 통제 — 2.3.x 접근통제
        // ====================================================================
        ControlNode c231 = saveCtrl(ismsp, cat23, "2.3.1", "사용자 인증", 0, 3);
        saveEt(c231, "인증 정책 문서", admin, null);
        saveEt(c231, "2FA 적용 현황", kimDev, LocalDate.of(2026, 7, 31));

        ControlNode c232 = saveCtrl(ismsp, cat23, "2.3.2", "접근권한 관리", 1, 3);
        EvidenceType et_perm_review = saveEt(c232, "접근권한 정기 검토 결과", admin, LocalDate.of(2026, 6, 30));
        saveFile(et_perm_review, "접근권한_검토_2026Q1.pdf", 2800000L, 1, CollectionMethod.manual,
                LocalDateTime.of(2026, 4, 5, 15, 0), ReviewStatus.approved, admin);

        // ====================================================================
        // depth=3 통제 — 3.1.x 개인정보 수집
        // ====================================================================
        ControlNode c311 = saveCtrl(ismsp, cat31, "3.1.1", "수집·이용 동의", 0, 3);
        saveEt(c311, "개인정보 수집·이용 동의서 양식", parkTl, null);
        saveEt(c311, "동의 절차 스크린샷", leeDev, LocalDate.of(2026, 5, 31));

        // ====================================================================
        // depth=3 통제 — 3.2.x 개인정보 보유·이용
        // ====================================================================
        ControlNode c321 = saveCtrl(ismsp, cat32, "3.2.1", "목적 외 이용 제한", 0, 3);
        saveEt(c321, "개인정보 접근 로그", kimDev, LocalDate.of(2026, 12, 31));

        // ====================================================================
        // 수집 작업 (일부 데모)
        // ====================================================================
        if (et_access_cur != null) {
            CollectionJob job1 = collectionJobRepository.save(CollectionJob.builder()
                    .name("접근권한 현황 추출")
                    .description("보안 시스템에서 접근권한 목록을 자동 추출합니다.")
                    .jobType(JobType.excel_extract)
                    .scriptPath("/scripts/access_rights.py")
                    .evidenceType(et_access_cur)
                    .scheduleCron("0 0 18 * * ?")
                    .build());

            jobExecutionRepository.save(JobExecution.builder()
                    .job(job1)
                    .status(ExecutionStatus.success)
                    .startedAt(LocalDateTime.of(2026, 3, 1, 18, 0))
                    .finishedAt(LocalDateTime.of(2026, 3, 1, 18, 2))
                    .build());
        }

        if (et_asset_list != null) {
            collectionJobRepository.save(CollectionJob.builder()
                    .name("정보자산 목록 웹 스크래핑")
                    .description("자산관리 시스템에서 정보자산 목록을 수집합니다.")
                    .jobType(JobType.web_scraping)
                    .scriptPath("/scripts/asset_scraper.py")
                    .evidenceType(et_asset_list)
                    .scheduleCron("0 0 6 1 * ?")
                    .build());
        }

        log.info("증빙 데모 데이터 초기화 완료 — Framework: {}, 통제 노드: {}개",
                ismsp.getName(), controlNodeRepository.count());
    }

    // ====================================================================
    // 헬퍼
    // ====================================================================

    /** 카테고리 노드 생성 */
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

    /** 통제(leaf) 노드 생성 */
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

    /** 증빙 유형 생성 (담당자·마감일 포함) */
    private EvidenceType saveEt(ControlNode ctrl, String name, User owner, LocalDate dueDate) {
        return evidenceTypeRepository.save(EvidenceType.builder()
                .controlNode(ctrl)
                .name(name)
                .ownerUser(owner)
                .dueDate(dueDate)
                .build());
    }

    /** 증빙 파일 생성 (검토 상태 포함) */
    private void saveFile(EvidenceType et, String fileName, long fileSize,
                          int version, CollectionMethod method, LocalDateTime collectedAt,
                          ReviewStatus reviewStatus, User reviewer) {
        EvidenceFile.EvidenceFileBuilder b = EvidenceFile.builder()
                .evidenceType(et)
                .fileName(fileName)
                .filePath("/storage/evidence/" + et.getControlNode().getCode() + "/" + fileName)
                .fileSize(fileSize)
                .version(version)
                .collectionMethod(method)
                .collectedAt(collectedAt)
                .reviewStatus(reviewStatus);

        if (reviewer != null) {
            b.reviewedBy(reviewer);
            b.reviewedAt(collectedAt.plusDays(1));
        }
        if (et.getOwnerUser() != null) {
            b.uploadedBy(et.getOwnerUser());
        }

        evidenceFileRepository.save(b.build());
    }
}