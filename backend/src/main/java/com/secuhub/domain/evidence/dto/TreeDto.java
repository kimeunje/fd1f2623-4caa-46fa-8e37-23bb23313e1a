package com.secuhub.domain.evidence.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.Framework;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Phase 5-14c — GET /api/v1/frameworks/{id}/tree 응답 DTO.
 *
 * <p>spec §3.3.1.4 페이로드 정합:
 * <pre>
 * {
 *   "framework": { "id", "name", "version" },
 *   "nodes": [
 *     { "id", "parentId", "nodeType", "code", "name", "description",
 *       "displayOrder", "depth",
 *       "evidenceTypeCount"?,        // leaf 만 (category 는 omit)
 *       "collectedCount"?,           // leaf 만 — v14.7 (5-14g β) 신규
 *       "pendingReviewCount"?        // leaf 만 (category 는 omit)
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>{@code nodes} 는 평탄화된 배열. 클라이언트가 {@code parentId} 로
 * 트리 reconstruction. 정렬은 {@link com.secuhub.domain.evidence.service.TreeService}
 * 에서 적용 — depth ASC, parent.id ASC (NULL FIRST), displayOrder ASC.
 * 부모가 자식보다 먼저 등장하므로 클라이언트의 트리 빌드 비용 최소화.</p>
 *
 * <p><b>v14.6 (5-14f) 변경</b>: leaf 의 {@code evidenceTypeCount} /
 * {@code pendingReviewCount} 본격 집계 (5-14c 의 0 고정 → 실제).</p>
 *
 * <p><b>v14.7 (5-14g β) 변경</b>: ControlsView 트리 본문의 6컬럼 진행바 ({@code N/M})
 * 와 "완료 / 진행중 / 미수집" 상태 derive 용으로 {@code collectedCount} 추가.
 * 정의: leaf 에 매달린 evidence_types 중 evidence_files 가 1개 이상 있는 type 의
 * distinct 수. category 는 omit.</p>
 */
public class TreeDto {

    // ========================================================================
    // Response — 최상위 응답 (framework + 평탄 nodes 배열)
    // ========================================================================
    @Getter
    @Builder
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @AllArgsConstructor
    public static class Response {
        private FrameworkSummary framework;
        private List<NodeSummary> nodes;
    }

    // ========================================================================
    // FrameworkSummary — id, name, version
    //   version 은 PATCH /tree (5-14d) 의 expectedVersion 과 짝맞춤
    // ========================================================================
    @Getter
    @Builder
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @AllArgsConstructor
    public static class FrameworkSummary {
        private Long id;
        private String name;
        private Long version;

        public static FrameworkSummary from(Framework fw) {
            return FrameworkSummary.builder()
                    .id(fw.getId())
                    .name(fw.getName())
                    .version(fw.getVersion())
                    .build();
        }
    }

    // ========================================================================
    // NodeSummary — 평탄 노드 표현
    //   category 와 leaf 가 같은 클래스를 사용. leaf 만 카운트 필드(3개)를 채우고,
    //   category 는 모두 null 로 두어 Jackson 이 omit (필드별 @JsonInclude).
    //   parentId / description 은 null 도 explicit 직렬화 (spec 의 첫 cat
    //   응답 예제: "parentId": null, "description": null).
    // ========================================================================
    @Getter
    @Builder
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @AllArgsConstructor
    public static class NodeSummary {
        private Long id;
        private Long parentId;            // null 허용 (depth=1 의 framework 직속)
        private String nodeType;          // "category" | "control"
        private String code;
        private String name;
        private String description;       // null 허용
        private Integer displayOrder;
        private Integer depth;

        // leaf 만 채우고 category 는 null → JSON omit (필드별 @JsonInclude)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Integer evidenceTypeCount;

        /**
         * v14.7 (5-14g β) 신규 — leaf 에 매달린 evidence_types 중 evidence_files 가
         * 1개 이상 있는 type 의 distinct 수. ControlsView 의 진행바 N/M 의 N + 상태
         * derive ("완료/진행중/미수집") 용. category 는 omit.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Integer collectedCount;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Long pendingReviewCount;

        /** category 노드 — 모든 카운트 필드 omit. */
        public static NodeSummary fromCategory(ControlNode cn) {
            return baseBuilder(cn).build();
        }

        /**
         * leaf 노드 — 세 카운트 필드 항상 노출 (5-14c 에서는 0 고정,
         * 5-14f / 14g 에서 본격 집계).
         */
        public static NodeSummary fromLeaf(ControlNode cn,
                                           int evidenceTypeCount,
                                           int collectedCount,
                                           long pendingReviewCount) {
            return baseBuilder(cn)
                    .evidenceTypeCount(evidenceTypeCount)
                    .collectedCount(collectedCount)
                    .pendingReviewCount(pendingReviewCount)
                    .build();
        }

        private static NodeSummaryBuilder baseBuilder(ControlNode cn) {
            return NodeSummary.builder()
                    .id(cn.getId())
                    .parentId(cn.getParent() != null ? cn.getParent().getId() : null)
                    .nodeType(cn.getNodeType().name())
                    .code(cn.getCode())
                    .name(cn.getName())
                    .description(cn.getDescription())
                    .displayOrder(cn.getDisplayOrder())
                    .depth(cn.getDepth());
        }
    }
}