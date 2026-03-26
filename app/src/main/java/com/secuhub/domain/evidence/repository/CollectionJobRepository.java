package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.CollectionJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectionJobRepository extends JpaRepository<CollectionJob, Long> {

    List<CollectionJob> findByIsActiveTrueAndScheduleCronIsNotNull();
}
