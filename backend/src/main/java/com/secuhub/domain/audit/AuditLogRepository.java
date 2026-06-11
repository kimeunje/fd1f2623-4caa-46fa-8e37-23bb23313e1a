package com.secuhub.domain.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * 보존정책 GC용 일괄 삭제. 엔티티 로딩 없이 단일 DELETE 발행 (대량 안전).
     *
     * @return 삭제된 행 수
     */
    @Modifying
    @Query("delete from AuditLog a where a.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}