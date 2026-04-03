package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.EvidenceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceTypeRepository extends JpaRepository<EvidenceType, Long> {

    List<EvidenceType> findByControlId(Long controlId);
}
