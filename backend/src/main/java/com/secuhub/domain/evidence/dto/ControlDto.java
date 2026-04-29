package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.Control;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.EvidenceType;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 통제 항목 관련 DTO 모음.
 *
 * <h3>v14 Phase 5-14f 변경</h3>
 * <ul>
 *   <li>{@link Response#from(Control, int)} / {@link Response#from(Control, int, long)}
 *       — {@code @Deprecated} 처리. {@link Control#getEvidenceTypes()} 매핑이 5-14f 에서
 *       제거되어 evidenceTotal 을 0 으로 채움 (deprecated 경로). v15 에서 메서드 제거.</li>
 *   <li>{@link Response#from(ControlNode, int, int, long)} — 신규 팩토리. leaf
 *       {@link ControlNode} 직접 받아 빌드. domain 은 leaf 의 depth=1 ancestor name
 *       으로 채움 (FrameworkExportService 패턴). evidenceTotal 은 호출 측에서 명시 전달.</li>
 *   <li>{@link DetailResponse#ancestors} — spec §8.2 신규 필드. EvidenceTypeDetailView
 *       헤더 서브텍스트의 N단 경로용. depth=1 부터 leaf 직계 부모까지 순서대로.</li>
 *   <li>{@link AncestorSummary} — spec §8.2 신규 클래스 (id / code / name 만 노출).</li>
 * </ul>
 */
public class ControlDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @jakarta.validation.constraints.NotBlank(message = "통제항목 코드는 필수입니다.")
        private String code;
        private String domain;
        @jakarta.validation.constraints.NotBlank(message = "통제항목 이름은 필수입니다.")
        private String name;
        private String description;
        private List<EvidenceTypeRequest> evidenceTypes;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String code;
        private String domain;
        private String name;
        private String description;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceTypeRequest {
        @jakarta.validation.constraints.NotBlank(message = "증빙 유형 이름은 필수입니다.")
        private String name;
        private String description;
    }

    /**
     * 통제항목 목록/생성/수정 응답 DTO.
     *
     * <p>v11 Phase 5-9 에서 Framework 상세 페이지의 행 단위 "검토 대기 N건" 배지를 위해
     * {@code pendingReviewCount} 필드를 추가했다. FrameworkDto.Response 와 동일한 규약:
     * 집계가 필요 없는 경로(생성/수정 직후)에서는 0 으로 채워진다.</p>
     *
     * <p>v14 Phase 5-14f — {@link #from(ControlNode, int, int, long)} 신규 팩토리. 신규
     * 호출 코드는 ControlNode 받는 팩토리 사용 권장. {@link #from(Control, int)} 와
     * {@link #from(Control, int, long)} 는 deprecated.</p>
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

        /**
         * 기존 2-인자 팩토리 — pendingReviewCount=0 으로 채움.
         *
         * @deprecated v14 Phase 5-14f. {@link #from(ControlNode, int, int, long)} 사용.
         *     {@link Control#getEvidenceTypes()} 매핑 제거로 evidenceTotal=0 (deprecated 경로).
         */
        @Deprecated(since = "v14 Phase 5-14f", forRemoval = true)
        public static Response from(Control entity, int collected) {
            return from(entity, collected, 0L);
        }

        /**
         * v11 Phase 5-9 집계 포함 팩토리.
         *
         * @deprecated v14 Phase 5-14f. {@link #from(ControlNode, int, int, long)} 사용.
         *     {@link Control#getEvidenceTypes()} 매핑이 5-14f 에서 제거되어 evidenceTotal
         *     은 0 으로 채워진다 (deprecated 경로 — 정확한 집계 필요 시 ControlNode 팩토리 사용).
         */
        @Deprecated(since = "v14 Phase 5-14f", forRemoval = true)
        public static Response from(Control entity, int collected, long pendingReviewCount) {
            // v14 Phase 5-14f: Control.evidenceTypes 매핑 제거됨 → evidenceTotal=0 (deprecated 경로)
            int total = 0;
            String status;
            if (total == 0) status = "미수집";
            else if (collected >= total) status = "완료";
            else if (collected > 0) status = "진행중";
            else status = "미수집";

            return Response.builder()
                    .id(entity.getId())
                    .frameworkId(entity.getFramework() != null ? entity.getFramework().getId() : null)
                    .code(entity.getCode())
                    .domain(entity.getDomain())
                    .name(entity.getName())
                    .description(entity.getDescription())
                    .evidenceTotal(total)
                    .evidenceCollected(collected)
                    .status(status)
                    .createdAt(entity.getCreatedAt() != null ?
                            entity.getCreatedAt().toString() : null)
                    .pendingReviewCount(pendingReviewCount)
                    .build();
        }

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