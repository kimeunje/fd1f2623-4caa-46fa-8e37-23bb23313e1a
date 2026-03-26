package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.JobExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobExecutionRepository extends JpaRepository<JobExecution, Long> {

    List<JobExecution> findByJobIdOrderByCreatedAtDesc(Long jobId);
}
