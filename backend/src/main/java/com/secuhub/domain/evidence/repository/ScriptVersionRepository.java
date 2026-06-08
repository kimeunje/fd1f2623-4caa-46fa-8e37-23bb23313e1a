package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.ScriptVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * v19.4 — 스크립트 버전 이력 조회 (carry-over ⑤).
 */
public interface ScriptVersionRepository extends JpaRepository<ScriptVersion, Long> {

    /** 버전 목록 (오래된 순). */
    List<ScriptVersion> findByScriptIdOrderByVersionNoAsc(Long scriptId);

    /** 특정 버전 단건. */
    Optional<ScriptVersion> findByScriptIdAndVersionNo(Long scriptId, int versionNo);

    /** 해당 스크립트에 버전이 하나라도 있는지 (레거시 시드 판단). */
    boolean existsByScriptId(Long scriptId);

    /** 현재 최대 버전 번호. 없으면 0. */
    @Query("select coalesce(max(v.versionNo), 0) from ScriptVersion v where v.scriptId = :scriptId")
    int findMaxVersionNo(@Param("scriptId") Long scriptId);
}