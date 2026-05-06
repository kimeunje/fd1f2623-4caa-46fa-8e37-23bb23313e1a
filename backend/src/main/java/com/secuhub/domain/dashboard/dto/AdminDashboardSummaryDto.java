package com.secuhub.domain.dashboard.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 대시보드 요약 응답 DTO (Phase v16.4a 신규).
 *
 * <p>spec §3.8 정합:</p>
 * <ul>
 *   <li>KPI: "내 승인 대기 N건" (pendingApprovalCount)</li>
 *   <li>승인 대기 목록: 제출자 / 시각 / 경로 / 바로가기 (top 10, submittedAt DESC)</li>
 *   <li>Framework별 진척: 진척률 바 + 검토 대기 건수</li>
 * </ul>
 *
 * <p>중첩 DTO 4개 (Kpi / PendingApproval / FrameworkProgress) 는 본 outer 클래스
 * 안에 함께 정의 — 단일 응답 컨텍스트라 분리 가치 적음. 외부 도메인에서 재사용 시점
 * 별도 파일 분리 검토.</p>
 *
 * <p>spec §3.8 의 "딥링크 형식" (관리자):
 * {@code /controls/{frameworkId}/{controlNodeId}/evidence-types/{evidenceTypeId}}.
 * 본 DTO 의 {@link PendingApproval#deepLinkUrl} 필드가 그 path 를 직접 제공 — FE 가
 * 그대로 router.push 또는 anchor href.</p>
 */
@Getter
@Builder
public class AdminDashboardSummaryDto {

    /** 상단 KPI 카드 영역 (현재 1개, 향후 확장 예약). */
    private final Kpi kpi;

    /**
     * 승인 대기 파일 목록 (top 10, submitted_at DESC).
     * 11+ 건 이상 시 클라이언트가 KPI 카드 클릭 → 별도 목록 페이지 (향후 phase).
     */
    private final List<PendingApproval> pendingApprovals;

    /** 활성 Framework 별 진척 목록. status='active' 만 포함. */
    private final List<FrameworkProgress> frameworkProgresses;

    // -------------------------------------------------------------------
    // 중첩 DTO
    // -------------------------------------------------------------------

    @Getter
    @Builder
    public static class Kpi {
        /**
         * 전체 pending 건 수. spec §3.8 의 "내 승인 대기 N건" 정합 — 모든 admin 이
         * 동일 풀에서 승인 가능하므로 전체 pending 카운트.
         */
        private final long pendingApprovalCount;
    }

    @Getter
    @Builder
    public static class PendingApproval {
        /** evidence_files.id (승인/반려 액션 호출 시 사용). */
        private final Long fileId;

        /** evidence_types.id (딥링크 path 의 마지막 segment). */
        private final Long evidenceTypeId;

        /** evidence_types.name. */
        private final String evidenceTypeName;

        /** 제출자 이름 (uploaded_by 의 user.name). */
        private final String uploaderName;

        /** 제출자 팀 (uploaded_by 의 user.team). null 가능. */
        private final String uploaderTeam;

        /** 제출 시각 (evidence_files.created_at). */
        private final LocalDateTime submittedAt;

        /** Framework id (딥링크 path 첫 segment). */
        private final Long frameworkId;

        /** Framework name (목록 표시용). */
        private final String frameworkName;

        /** ControlNode (leaf 또는 hybrid) id (딥링크 path 두 번째 segment). */
        private final Long controlNodeId;

        /**
         * 트리 계층 경로 — ancestors[].code + leaf.code 를 " > " 로 연결.
         * 예: "1 > 1.1 > 1.1.1". FrameworkExportService 와 같은 형식.
         */
        private final String controlPath;

        /**
         * 딥링크 URL — FE 가 그대로 router.push 또는 anchor href.
         * 형식: {@code /controls/{frameworkId}/{controlNodeId}/evidence-types/{evidenceTypeId}}.
         */
        private final String deepLinkUrl;
    }

    @Getter
    @Builder
    public static class FrameworkProgress {
        private final Long frameworkId;
        private final String frameworkName;

        /** 해당 framework 의 evidence_types 총 개수 (분모). */
        private final long totalEvidenceTypes;

        /**
         * 수집 완료 카운트 — 해당 evidence_type 에 review_status IN ('approved',
         * 'auto_approved') 인 evidence_files 가 1+ 존재. spec §6.4 의 "수집 완료" 정의
         * 정합.
         */
        private final long collectedCount;

        /** 검토 대기 카운트 — review_status='pending' 의 evidence_files 가 1+ 존재. */
        private final long pendingReviewCount;

        /**
         * 진척률 = collectedCount / totalEvidenceTypes. 0.0 ~ 1.0. total=0 시 0.0
         * 반환 (NaN 방지).
         */
        private final double progressRatio;
    }
}