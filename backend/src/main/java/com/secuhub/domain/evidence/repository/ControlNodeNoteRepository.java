package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.ControlNodeNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * v19.27 — 관리 항목 인수인계 노트 Repository.
 */
public interface ControlNodeNoteRepository extends JpaRepository<ControlNodeNote, Long> {

    /**
     * 특정 관리 항목의 노트를 작성순(오래된 것 먼저)으로 반환.
     * 인수인계 로그는 시간 흐름대로 읽는 게 자연스러우므로 createdAt ASC.
     */
    List<ControlNodeNote> findByControlNodeIdOrderByCreatedAtAsc(Long controlNodeId);
}