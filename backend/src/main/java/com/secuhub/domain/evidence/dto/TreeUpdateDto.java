package com.secuhub.domain.evidence.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Phase 5-14d — PATCH /api/v1/frameworks/{id}/tree DTO.
 *
 * <p>spec §3.3.1.4 페이로드 정합:</p>
 * <pre>
 * Request:
 * {
 *   "expectedVersion": 17,
 *   "changes": {
 *     "nodes": {
 *       "created": [...],   // CreatedNode (parentId: Long|String|null)
 *       "updated": [...],   // UpdatedNode (id 필수, 부분 갱신)
 *       "moved":   [...],   // MovedNode   (newParentId 다형)
 *       "deleted": [...]    // DeletedNode (id 만)
 *     }
 *   }
 * }
 *
 * Response (200):
 * {
 *   "version": 18,
 *   "mappings": { "nodes": [{"tempId": "temp_X", "id": 501}, ...] }
 * }
 * </pre>
 *
 * <h3>parentId / newParentId 다형 처리</h3>
 * <p>{@code Object} 타입으로 받음. Jackson 이 JSON 의 number → Long/Integer,
 * string → String, null → null 로 자동 deserialize. Service 에서
 * {@code instanceof Number / String / null} 분기.</p>
 */
public class TreeUpdateDto {

    // ========================================================================
    // Request
    // ========================================================================
    @Getter @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        private Long expectedVersion;
        private Changes changes;
    }

    @Getter @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Changes {
        private NodeChanges nodes;
    }

    @Getter @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeChanges {
        private List<CreatedNode> created;
        private List<UpdatedNode> updated;
        private List<MovedNode> moved;
        private List<DeletedNode> deleted;
    }

    @Getter @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatedNode {
        private String tempId;
        private Object parentId;          // Long | String (tempId) | null
        private String nodeType;          // "category" | "control"
        private String code;
        private String name;
        private String description;
        private Integer displayOrder;
        private Integer depth;
    }

    @Getter @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdatedNode {
        private Long id;
        private String code;              // optional — 제공 시 갱신
        private String name;              // optional
        private String description;       // optional
        private String nodeType;          // 5-14d 정책: 제공 시 422 (변환은 v2)
    }

    @Getter @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MovedNode {
        private Long id;
        private Object newParentId;       // Long | String (tempId) | null
        private Integer newDisplayOrder;
        private Integer newDepth;
    }

    @Getter @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeletedNode {
        private Long id;
    }

    // ========================================================================
    // Response (200)
    // ========================================================================
    @Getter
    @Builder
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        private Long version;
        private Mappings mappings;
    }

    @Getter
    @Builder
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @AllArgsConstructor
    public static class Mappings {
        private List<NodeMapping> nodes;
    }

    @Getter
    @Builder
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @AllArgsConstructor
    public static class NodeMapping {
        private String tempId;
        private Long id;
    }
}