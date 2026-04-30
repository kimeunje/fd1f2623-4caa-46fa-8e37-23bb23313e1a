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
 *       "evidenceTypeCount",         // 모든 노드 (v15.2 hybrid, own only)
 *       "collectedCount",            // 모든 노드 (v15.2 hybrid, own only)
 *       "pendingReviewCount"         // 모든 노드 (v15.2 hybrid, own only)
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
 *
 * <p><b>v15.2 (5-15a 후속-1) 변경</b>: hybrid 모델 정합. 모든 노드에 세 카운트 필드
 * ({@code evidenceTypeCount}, {@code collectedCount}, {@code pendingReviewCount})
 * 노출 — Q1=A own only 정의 (자체 evidence_types 만 카운트, 자손 평탄화 0). Q2=A
 * Map miss 시 0 명시 (NULL 없음). Repository 쿼리는 5-14f/5-14g 의 explicit JOIN
 * 패턴이 leaf/category 구분 없이 own 카운트 자연 집계 — 추가 변경 0. category 자체에
 * evidence_types 매달림 가능 (v15.0 hybrid backend 보장).</p>
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
    //   category 와 leaf 가 같은 클래스를 사용. v15.2 5-15a 후속-1: 모든 노드에
    //   세 카운트 필드 노출 (own only 정의, Q1=A). 5-14c~5-14g 시점의 leaf 한정
    //   분기 (@JsonInclude NON_NULL) 폐기 — hybrid 카테고리 자체 evidence 카운트
    //   표시 정합.
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

        /**
         * v15.2 (5-15a 후속-1) — 모든 노드에 own evidence_types 수 노출 (Q1=A own only).
         * mutex 데이터 (5-14g 까지) 에서는 category=0, leaf=양수. hybrid (v15.0+) 후
         * 카테고리도 양수 가능. Map miss 시 0 명시.
         */
        private Integer evidenceTypeCount;

        /**
         * v14.7 (5-14g β) 신규 — own evidence_types 중 evidence_files 가 1개 이상 있는
         * type 의 distinct 수. ControlsView 의 진행바 N/M 의 N + 상태 derive
         * ("완료/진행중/미수집") 용. v15.2 (5-15a 후속-1): 모든 노드 노출 (own only).
         */
        private Integer collectedCount;

        /**
         * v15.2 (5-15a 후속-1) — 모든 노드에 own evidence_types 산하 pending 파일 수
         * 노출 (Q1=A own only). 검토 대기 N 배지용.
         */
        private Long pendingReviewCount;

        /**
         * @deprecated v15.2 5-15a 후속-1 — mutex 모델 가정 (category 카운트 omit).
         *             hybrid 후 모든 노드에 카운트 노출. {@code TreeService.toNodeSummary}
         *             가 직접 빌드 (본 헬퍼 미사용). 5-15b 또는 v15.x 일괄 정리 검토.
         */
        @Deprecated(since = "v15.2 Phase 5-15a")
        public static NodeSummary fromCategory(ControlNode cn) {
            return baseBuilder(cn).build();
        }

        /**
         * @deprecated v15.2 5-15a 후속-1 — mutex 모델 가정 (leaf 한정 카운트).
         *             hybrid 후 모든 노드에 own 카운트 노출. {@code TreeService.toNodeSummary}
         *             가 직접 빌드 (본 헬퍼 미사용). 5-15b 또는 v15.x 일괄 정리 검토.
         */
        @Deprecated(since = "v15.2 Phase 5-15a")
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