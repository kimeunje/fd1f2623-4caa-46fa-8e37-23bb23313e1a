package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.EvidenceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceTypeRepository extends JpaRepository<EvidenceType, Long> {

    List<EvidenceType> findByControlId(Long controlId);

    // v11: 담당자 본인의 증빙 유형 조회 — "내 할 일" 페이지 기반 (Phase 5-5)
    List<EvidenceType> findByOwnerUserId(Long ownerUserId);
}