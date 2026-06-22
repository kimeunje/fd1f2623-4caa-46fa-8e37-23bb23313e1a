package com.secuhub.config.approval;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 증빙 결재/승인 단계 on/off (app.approval.*).
 *
 * <p>{@code enabled=false} 면:</p>
 * <ul>
 *   <li>업로드/링크가 업로더 역할과 무관하게 즉시 {@code auto_approved}</li>
 *   <li>승인 대기 목록 / 승인 / 반려 기능 비활성</li>
 * </ul>
 *
 * <p>스키마는 그대로(reviewStatus 컬럼·rejected 사유 등 유지) — 동작만 우회한다.
 * 끄면 승인 단계가 화면·플로우에서 사라지고, 켜면 복귀. DB 변경 없음.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.approval")
@Getter
@Setter
public class ApprovalProperties {

    /** 승인 단계 사용 여부. 기본 true(기존 동작 유지). */
    private boolean enabled = true;
}