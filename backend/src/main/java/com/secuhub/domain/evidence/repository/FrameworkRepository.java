package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.FrameworkStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FrameworkRepository extends JpaRepository<Framework, Long> {

    // v11: 상속 관계 조회 — 특정 Framework를 부모로 삼는 자식 Framework 목록
    List<Framework> findByParentFrameworkId(Long parentFrameworkId);

    // v11: 상태별 조회 — Framework 목록 페이지에서 active 만 표시
    List<Framework> findByStatus(FrameworkStatus status);

    List<Framework> findByStatusOrderByCreatedAtDesc(FrameworkStatus status);
}