package com.secuhub.domain.mytasks.dto;

import com.secuhub.domain.evidence.entity.EvidenceFile;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.ReviewStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 담당자 "내 할 일" API 응답 DTO (Phase 5-5).
 *
 * <h3>구조</h3>
 * <p>한 번의 호출로 5개 섹션을 묶음 반환해 라운드트립을 1회로 유지한다.</p>
 * <ul>
 *   <li>rejected — 최신 파일이 {@code rejected} 인 증빙. 즉시 재제출 필요.</li>
 *   <li>dueSoon — 파일이 아직 없고 {@code dueDate} 가 오늘+7일 이내인 증빙.</li>
 *   <li>notSubmitted — 파일이 없고 마감임박도 아닌 증빙.</li>
 *   <li>inReview — 최신 파일이 {@code pending} 인 증빙. 관리자 검토 대기.</li>
 *   <li>completed — 최신 파일이 {@code approved} 또는 {@code auto_approved}. 최대 10건.</li>
 * </ul>
 *
 * <p>한 증빙 유형은 정확히 한 섹션에만 속한다 (이동 규칙은 서비스에서 결정).</p>
 */
public class MyTasksDto {

    @Getter
    @Builder
    public static class Response {
        /** 섹션별 Item 리스트 */
        private List<Item> rejected;
        private List<Item> dueSoon;
        private List<Item> notSubmitted;
        private List<Item> inReview;
        private List<Item> completed;

        /** 상단 5칸 KPI 카드용 카운트 (completed 는 전체 건수; completed.size() 는 최대 10) */
        private Counts counts;
    }

    @Getter
    @Builder
    public static class Counts {
        private int rejected;
        private int dueSoon;
        private int notSubmitted;
        private int inReview;
        private int completed;
    }

    /**
     * 증빙 유형 1개의 할 일 카드 정보.
     *
     * <p>섹션에 따라 일부 필드가 비어있을 수 있다 — 예: notSubmitted 섹션은
     * latestFile* 필드가 모두 null, rejected 섹션은 {@code rejectReason} 가 항상 존재.</p>
     */
    @Getter
    @Builder
    public static class Item {
        // 증빙 유형 정보
        private Long evidenceTypeId;
        private String evidenceTypeName;

        // 경로 정보 (담당자가 어느 통제의 어느 Framework 인지 볼 수 있게)
        private Long controlId;
        private String controlCode;
        private String controlName;
        private Long frameworkId;
        private String frameworkName;

        // 마감일 메타
        private String dueDate;            // ISO yyyy-MM-dd, null 가능
        private Integer daysUntilDue;      // null = dueDate 없음. 음수 = 지남.

        // 최신 파일 메타 (해당 섹션에 따라 존재 여부 다름)
        private Long latestFileId;
        private String latestFileName;
        private Integer latestVersion;
        private String latestReviewStatus; // ReviewStatus.name() 또는 null
        private String submittedAt;        // 최신 파일 collectedAt
        private String reviewedAt;         // null 가능

        // 반려 케이스에만 의미있는 필드
        private String rejectReason;       // rejected 섹션에서 매우 중요
        private String rejectedByName;

        // 완료 케이스에만 의미있는 필드
        private String approvedByName;

        // ---------- 팩토리 메서드 ----------

        /**
         * 공통 필드 (경로/마감) 로 빌더 초기화.
         * 호출측에서 섹션에 맞는 추가 필드를 이어서 세팅한다.
         */
        public static ItemBuilder baseBuilder(EvidenceType et, LocalDate today) {
            Long dueMinusToday = null;
            String dueStr = null;
            if (et.getDueDate() != null) {
                dueStr = et.getDueDate().toString();
                dueMinusToday = ChronoUnit.DAYS.between(today, et.getDueDate());
            }

            return Item.builder()
                    .evidenceTypeId(et.getId())
                    .evidenceTypeName(et.getName())
                    .controlId(et.getControl() != null ? et.getControl().getId() : null)
                    .controlCode(et.getControl() != null ? et.getControl().getCode() : null)
                    .controlName(et.getControl() != null ? et.getControl().getName() : null)
                    .frameworkId(et.getControl() != null && et.getControl().getFramework() != null
                            ? et.getControl().getFramework().getId() : null)
                    .frameworkName(et.getControl() != null && et.getControl().getFramework() != null
                            ? et.getControl().getFramework().getName() : null)
                    .dueDate(dueStr)
                    .daysUntilDue(dueMinusToday != null ? dueMinusToday.intValue() : null);
        }

        /**
         * 최신 파일 메타를 적용한 빌더 체인.
         * rejected / inReview / completed 섹션에서 공통으로 쓴다.
         */
        public static ItemBuilder withLatestFile(ItemBuilder b, EvidenceFile file) {
            b.latestFileId(file.getId())
                    .latestFileName(file.getFileName())
                    .latestVersion(file.getVersion())
                    .latestReviewStatus(file.getReviewStatus() != null ? file.getReviewStatus().name() : null)
                    .submittedAt(file.getCollectedAt() != null ? file.getCollectedAt().toString() : null)
                    .reviewedAt(file.getReviewedAt() != null ? file.getReviewedAt().toString() : null);

            if (file.getReviewStatus() == ReviewStatus.rejected) {
                b.rejectReason(file.getReviewNote())
                        .rejectedByName(file.getReviewedBy() != null ? file.getReviewedBy().getName() : null);
            }
            if (file.getReviewStatus() == ReviewStatus.approved) {
                b.approvedByName(file.getReviewedBy() != null ? file.getReviewedBy().getName() : null);
            }
            return b;
        }
    }

    /**
     * 증빙 재제출 페이지 (MyEvidenceDetailView) 상세 응답.
     * 단일 증빙 유형 + 파일 이력 + 최신 반려 사유.
     */
    @Getter
    @Builder
    public static class DetailResponse {
        private Long evidenceTypeId;
        private String evidenceTypeName;
        private String description;
        private Long controlId;
        private String controlCode;
        private String controlName;
        private Long frameworkId;
        private String frameworkName;
        private String dueDate;
        private Integer daysUntilDue;

        /** 현재 상태 요약 — "rejected" / "pending" / "approved" / "auto_approved" / "not_submitted" */
        private String currentStatus;

        /** 반려인 경우에만 채워지는 하이라이트 정보 */
        private String rejectReason;
        private String rejectedByName;
        private String rejectedAt;

        /** 모든 파일 이력 (version desc). 각 원소는 FileHistoryEntry */
        private List<FileHistoryEntry> history;
    }

    @Getter
    @Builder
    public static class FileHistoryEntry {
        private Long fileId;
        private String fileName;
        private Long fileSize;
        private Integer version;
        private String collectionMethod;     // "auto" | "manual"
        private String collectedAt;
        private String uploadedByName;
        private String submitNote;
        private String reviewStatus;
        private String reviewedByName;
        private String reviewNote;
        private String reviewedAt;

        public static FileHistoryEntry from(EvidenceFile f) {
            return FileHistoryEntry.builder()
                    .fileId(f.getId())
                    .fileName(f.getFileName())
                    .fileSize(f.getFileSize())
                    .version(f.getVersion())
                    .collectionMethod(f.getCollectionMethod() != null ? f.getCollectionMethod().name() : null)
                    .collectedAt(f.getCollectedAt() != null ? f.getCollectedAt().toString() : null)
                    .uploadedByName(f.getUploadedBy() != null ? f.getUploadedBy().getName() : null)
                    .submitNote(f.getSubmitNote())
                    .reviewStatus(f.getReviewStatus() != null ? f.getReviewStatus().name() : null)
                    .reviewedByName(f.getReviewedBy() != null ? f.getReviewedBy().getName() : null)
                    .reviewNote(f.getReviewNote())
                    .reviewedAt(f.getReviewedAt() != null ? f.getReviewedAt().toString() : null)
                    .build();
        }
    }
}