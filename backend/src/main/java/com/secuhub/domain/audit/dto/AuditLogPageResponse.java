package com.secuhub.domain.audit.dto;

import java.util.List;

/**
 * 감사 로그 페이지 응답 (AUDIT-2).
 *
 * <p>Spring Data {@code Page} 를 그대로 직렬화하지 않고(PageImpl JSON 불안정)
 * 필요한 메타만 명시 노출한다. 본 프로젝트 첫 페이지네이션 응답 컨벤션.</p>
 */
public record AuditLogPageResponse(
        List<AuditLogResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}