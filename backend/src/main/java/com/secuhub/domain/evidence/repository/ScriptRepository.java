package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.Script;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * v18.8.2 — Script entity repository.
 *
 * <p>JpaRepository 기본 메서드 (findById / save / delete 등) 활용.</p>
 */
@Repository
public interface ScriptRepository extends JpaRepository<Script, Long> {
}