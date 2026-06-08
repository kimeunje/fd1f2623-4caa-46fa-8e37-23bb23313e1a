package com.secuhub.domain.access.repository;

import com.secuhub.domain.access.entity.IpAccessRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * v19.0 — 계정별 IP 접근 규칙 조회 (BE-1).
 */
public interface IpAccessRuleRepository extends JpaRepository<IpAccessRule, Long> {

    /**
     * enforcement 경로 — 활성 규칙만. 0건이면 호출부가 "제한 없음"으로 해석.
     */
    List<IpAccessRule> findByUserIdAndEnabledTrue(Long userId);

    /**
     * 관리 화면 조회용 (BE-2) — 활성/비활성 모두, 생성순.
     */
    List<IpAccessRule> findByUserIdOrderByCreatedAtAsc(Long userId);
}