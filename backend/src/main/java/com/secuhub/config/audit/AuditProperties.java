package com.secuhub.config.audit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 감사 로그 설정 (app.audit.*).
 *
 * <p>L_CONFIG_EXTERNALIZE_KEEP_DEFAULT: 기본값은 코드에 두고 yml 은 override 만.
 * (기본값 제거 시 prod 프로필 없는 dev/test 가 비어 깨짐)
 *
 * <p>컴플라이언스 특성상 <b>기본 무기한 보존</b> — retention-days ≤ 0 이면 GC 비활성.
 * 운영에서 보존 한도를 명시적으로 켤 때만 양수 지정.
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.audit")
public class AuditProperties {

    /** 보존 일수. ≤ 0 이면 GC 비활성(무기한 보존). 기본 -1. */
    private long retentionDays = -1;

    /** 보존정책 GC 스케줄 (cron). 기본 매일 04:30. */
    private String gcCron = "0 30 4 * * *";
}