package com.secuhub.domain.audit;

import com.secuhub.config.audit.AuditProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 감사 로그 보존정책 GC (v19.3 DiagnosisOutputGcService 패턴 재사용).
 *
 * <p>{@code app.audit.retention-days} ≤ 0 이면 비활성(무기한 보존) — 함부로 지우지 않음.
 * <p>@Scheduled 는 기존 @EnableScheduling(v19.3 도입) 위에서 동작.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditRetentionGcService {

    private final AuditLogRepository auditLogRepository;
    private final AuditProperties auditProperties;

    @Scheduled(cron = "${app.audit.gc-cron:0 30 4 * * *}")
    @Transactional
    public void purgeExpired() {
        long days = auditProperties.getRetentionDays();
        if (days <= 0) {
            log.debug("[audit-gc] 비활성 (retention-days={}) — 무기한 보존", days);
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        int deleted = auditLogRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("[audit-gc] 만료 감사 로그 {}건 정리 (cutoff={}, retention-days={})",
                    deleted, cutoff, days);
        }
    }
}