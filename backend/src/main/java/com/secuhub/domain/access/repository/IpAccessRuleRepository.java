package com.secuhub.domain.access.repository;

import com.secuhub.domain.access.entity.IpAccessRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * v19.0 — 계정별 IP 접근 규칙 조회 (BE-1).
 * v19.1 — 관리 CRUD 용 소유 검증 조회 추가 (BE-2).
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

    /**
     * 관리 CRUD 소유 검증 (BE-2) — 규칙이 해당 사용자 소유인지 확인하며 단건 조회.
     * 다른 사용자의 ruleId 로 접근 시 빈 Optional → 404.
     */
    Optional<IpAccessRule> findByIdAndUserId(Long id, Long userId);
}