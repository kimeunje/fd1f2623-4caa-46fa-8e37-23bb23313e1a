package com.secuhub.domain.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 감사 로그 저장소.
 * <p>AUDIT-2: 동적 다중 필터 검색을 위해 {@link JpaSpecificationExecutor} 추가
 * (actor/action/result/기간 optional 조합 — Criteria 로 null 안전).</p>
 */
public interface AuditLogRepository
        extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    /**
     * 보존정책 GC용 일괄 삭제 (AUDIT-1). 엔티티 로딩 없이 단일 DELETE 발행.
     *
     * @return 삭제된 행 수
     */
    @Modifying
    @Query("delete from AuditLog a where a.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}