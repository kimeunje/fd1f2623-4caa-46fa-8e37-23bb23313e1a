package com.secuhub.domain.evidence.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Phase 5-14d — PATCH /tree 의 422 응답에 들어가는 항목별 검증 실패 정보.
 *
 * <p>spec §3.3.1.4 정합:
 * <pre>
 * {
 *   "target": "node",
 *   "targetId": 102,           // 기존 노드인 경우 (있을 때만 노출)
 *   "targetTempId": "temp_X",  // 신규 노드인 경우 (있을 때만 노출)
 *   "field": "code",
 *   "code": "duplicate_code",
 *   "message": "같은 분류 안에 ... 코드가 이미 있습니다"
 * }
 * </pre></p>
 *
 * <p>{@code @JsonInclude(NON_NULL)} 로 targetId / targetTempId 중 해당 없는 쪽 omit.</p>
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidationDetail {
    private String target;        // "node"
    private Long targetId;        // 기존 노드 PK
    private String targetTempId;  // 신규 노드의 클라이언트측 임시 ID
    private String field;         // "code", "depth", "nodeType", "parentId", ...
    private String code;          // "duplicate_code", "max_depth_exceeded", ...
    private String message;       // 사용자 안내 메시지 (한국어)
}