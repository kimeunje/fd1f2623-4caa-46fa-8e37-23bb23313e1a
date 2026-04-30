package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.EvidenceType;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 통제 항목 관련 DTO 모음.
 *
 * <h3>v15.3 (5-15b) 정리</h3>
 * <ul>
 *   <li><b>제거</b>: {@code CreateRequest} / {@code UpdateRequest} / {@code EvidenceTypeRequest}
 *       — 5-14b/5-14f 부터 백엔드 410 Gone, FE 호출처 0 (Phase 5-14h 에서 controlsApi.create/
 *       update/delete 이미 삭제됨). 신규 진입은 모두 PATCH /tree.</li>
 *   <li><b>제거</b>: {@code Response.from(Control, int)} / {@code Response.from(Control, int, long)}
 *       deprecated 팩토리 — Control 엔티티 자체가 5-15b 에서 제거됨. 후속 호출 측은
 *       {@link Response#from(ControlNode, int, int, long)} 사용.</li>
 * </ul>
 *
 * <h3>v14 Phase 5-14f 변경 (보존)</h3>
 * <ul>
 *   <li>{@link Response#from(ControlNode, int, int, long)} — leaf {@link ControlNode}
 *       직접 받아 빌드. domain 은 leaf 의 depth=1 ancestor name 으로 채움.
 *       evidenceTotal 은 호출 측에서 명시 전달.</li>
 *   <li>{@link DetailResponse#ancestors} — spec §8.2 EvidenceTypeDetailView 헤더
 *       서브텍스트의 N단 경로용. depth=1 부터 leaf 직계 부모까지 순서대로.</li>
 *   <li>{@link AncestorSummary} — spec §8.2 (id / code / name 만 노출).</li>
 * </ul>
 */
public class ControlDto {

    // v15.3 5-15b — write 관련 3 DTO (CreateRequest / UpdateRequest / EvidenceTypeRequest)
    // 제거. 5-14b/5-14f 부터 백엔드 410 Gone, FE 호출처 0. 신규 진입은 PATCH /tree.

    /**
     * 통제항목 응답 DTO (read-only).
     *
     * <p>v11 Phase 5-9 에서 Framework 상세 페이지의 행 단위 "검토 대기 N건" 배지를 위해
     * {@code pendingReviewCount} 필드를 추가했다. FrameworkDto.Response 와 동일한 규약:
     * 집계가 필요 없는 경로(생성/수정 직후)에서는 0 으로 채워진다.</p>
     *
     * <p>v14 Phase 5-14f — {@link #from(ControlNode, int, int, long)} 신규 팩토리. 신규
     * 호출 코드는 ControlNode 받는 팩토리 사용 권장. Control 엔티티 받는 두 deprecated 팩토리는 v15.3 5-15b 에서 제거됨.</p>
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private Long frameworkId;
        private String code;
        private String domain;
        private String name;
        private String description;
        private int evidenceTotal;
        private int evidenceCollected;
        private String status;
        private String createdAt;

        // v11 Phase 5-9 — 통제항목 행 단위 검토 대기 배지
        private long pendingReviewCount;

        // v15.3 5-15b — Control 엔티티 받는 deprecated 팩토리 2개 제거
        // (from(Control, int) / from(Control, int, long)). 호출처 0 (5-14f 후 ControlNode 팩토리 사용).

        /**
         * v14 Phase 5-14f — leaf {@link ControlNode} 받는 신규 팩토리.
         *
         * <p>호출 측이 leaf 의 evidence_types 카운트 (total) + collected + pending 을 명시
         * 전달. domain 은 leaf 의 depth=1 ancestor name 으로 자동 채움 (없으면 null).
         * FrameworkExportService (5-14e) 의 영역 컬럼 패턴과 정합.</p>
         *
         * @param leaf {@code node_type='control'} 인 ControlNode (호출 측 보장)
         * @param total leaf 에 매달린 evidence_types 수
         * @param collected 그 중 evidence_files 가 1개 이상 있는 evidence_types 수
         * @param pendingReviewCount leaf 매달린 evidence_files 중 pending 상태 수
         */
        public static Response from(ControlNode leaf, int total, int collected, long pendingReviewCount) {
            // depth=1 ancestor 의 name 을 domain 으로 (controls.domain 컬럼 폐기 후 호환 필드)
            String domain = null;
            ControlNode cur = leaf.getParent();
            ControlNode top = null;
            while (cur != null) {
                top = cur;
                cur = cur.getParent();
            }
            if (top != null) domain = top.getName();

            String status;
            if (total == 0) status = "미수집";
            else if (collected >= total) status = "완료";
            else if (collected > 0) status = "진행중";
            else status = "미수집";

            return Response.builder()
                    .id(leaf.getId())
                    .frameworkId(leaf.getFramework() != null ? leaf.getFramework().getId() : null)
                    .code(leaf.getCode())
                    .domain(domain)
                    .name(leaf.getName())
                    .description(leaf.getDescription())
                    .evidenceTotal(total)
                    .evidenceCollected(collected)
                    .status(status)
                    .createdAt(leaf.getCreatedAt() != null ?
                            leaf.getCreatedAt().toString() : null)
                    .pendingReviewCount(pendingReviewCount)
                    .build();
        }
    }

    /**
     * 통제 상세 응답 DTO.
     *
     * <p>v14 Phase 5-14f — {@code ancestors[]} 필드 추가 (spec §8.2). EvidenceTypeDetailView
     * 헤더 서브텍스트의 N단 경로용. {@code domain} 필드는 ancestors[0].name 과 정합.</p>
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class DetailResponse {
        private Long id;
        private Long frameworkId;
        private String code;
        private String domain;
        private String name;
        private String description;
        private int evidenceTotal;
        private int evidenceCollected;
        private String status;
        private List<EvidenceTypeResponse> evidenceTypes;
        private String createdAt;

        /**
         * v14 Phase 5-14f 신규 (spec §8.2).
         *
         * <p>leaf 의 depth=1 ancestor 부터 직계 부모까지 순서대로. leaf 자기 자신은 미포함.
         * 빈 리스트 (depth=1 leaf 인 경우) 가능 — null 아님.</p>
         */
        @Builder.Default
        private List<AncestorSummary> ancestors = new ArrayList<>();
    }

    /**
     * v14 Phase 5-14f 신규 (spec §8.2) — leaf 의 ancestor 노드 요약.
     *
     * <p>EvidenceTypeDetailView 헤더 서브텍스트 표시에 필요한 최소 필드만 노출.
     * (id / code / name).</p>
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class AncestorSummary {
        private Long id;
        private String code;
        private String name;
    }

    /**
     * 증빙 유형 응답 DTO.
     *
     * <p>v11 Phase 5-12 (백엔드 owner DTO 보강, 2026-04) — 담당자/마감일 정보를 노출하여
     * 프론트 상세 페이지(EvidenceTypeDetailView)의 "담당자 미지정"이 실제 이름으로 표시되도록
     * 한다. 프론트 타입(EvidenceTypeResponse)은 Phase 5-12 에서 옵셔널로 미리 준비됨.</p>
     *
     * <p>ownerUser / dueDate 는 모두 nullable. 미배정 증빙 유형도 정상 동작하도록 null-safe
     * 매핑한다. EvidenceType.ownerUser 는 LAZY 페치이므로 이 DTO 변환은 반드시
     * {@code @Transactional} 내부에서 이뤄져야 한다 (현재 ControlService.findDetail 이
     * 해당 조건을 충족).</p>
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class EvidenceTypeResponse {
        private Long id;
        private String name;
        private String description;
        private boolean collected;
        private List<EvidenceFileDto.Response> files;

        // v11 Phase 5-12 — 담당자·마감일
        private Long ownerUserId;
        private String ownerUserName;
        private String ownerUserTeam;
        private String dueDate;

        public static EvidenceTypeResponse from(EvidenceType et, List<EvidenceFileDto.Response> files) {
            return EvidenceTypeResponse.builder()
                    .id(et.getId())
                    .name(et.getName())
                    .description(et.getDescription())
                    .collected(files != null && !files.isEmpty())
                    .files(files)
                    // 담당자 (null-safe)
                    .ownerUserId(et.getOwnerUser() != null ? et.getOwnerUser().getId() : null)
                    .ownerUserName(et.getOwnerUser() != null ? et.getOwnerUser().getName() : null)
                    .ownerUserTeam(et.getOwnerUser() != null ? et.getOwnerUser().getTeam() : null)
                    // 마감일 (LocalDate → String, null-safe)
                    .dueDate(et.getDueDate() != null ? et.getDueDate().toString() : null)
                    .build();
        }
    }
}