package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.CollectionJobDto;
import com.secuhub.domain.evidence.entity.*;
import com.secuhub.domain.evidence.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionJobService {

    private final CollectionJobRepository collectionJobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;

    @Transactional(readOnly = true)
    public List<CollectionJobDto.Response> findAll() {
        return collectionJobRepository.findAll().stream()
                .map(job -> {
                    List<JobExecution> execs = jobExecutionRepository.findByJobIdOrderByCreatedAtDesc(job.getId());
                    JobExecution lastExec = execs.isEmpty() ? null : execs.get(0);
                    return CollectionJobDto.Response.from(job, lastExec);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public CollectionJobDto.DetailResponse findDetail(Long jobId) {
        CollectionJob job = collectionJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("수집 작업", jobId));

        List<JobExecution> execs = jobExecutionRepository.findByJobIdOrderByCreatedAtDesc(job.getId());
        List<CollectionJobDto.ExecutionSummary> execSummaries = execs.stream()
                .map(CollectionJobDto.ExecutionSummary::from)
                .toList();

        return CollectionJobDto.DetailResponse.builder()
                .id(job.getId())
                .name(job.getName())
                .description(job.getDescription())
                .jobType(job.getJobType().name())
                .scriptPath(job.getScriptPath())
                .evidenceTypeId(job.getEvidenceType() != null ? job.getEvidenceType().getId() : null)
                .evidenceTypeName(job.getEvidenceType() != null ? job.getEvidenceType().getName() : null)
                .scheduleCron(job.getScheduleCron())
                .isActive(job.getIsActive())
                .executions(execSummaries)
                .createdAt(job.getCreatedAt() != null ? job.getCreatedAt().toString() : null)
                .build();
    }

    @Transactional
    public CollectionJobDto.Response create(CollectionJobDto.CreateRequest request) {
        JobType jobType;
        try {
            jobType = JobType.valueOf(request.getJobType());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("유효하지 않은 작업 유형입니다: " + request.getJobType());
        }

        EvidenceType evidenceType = null;
        if (request.getEvidenceTypeId() != null) {
            evidenceType = evidenceTypeRepository.findById(request.getEvidenceTypeId())
                    .orElseThrow(() -> new ResourceNotFoundException("증빙 유형", request.getEvidenceTypeId()));
        }

        CollectionJob job = CollectionJob.builder()
                .name(request.getName())
                .description(request.getDescription())
                .jobType(jobType)
                .scriptPath(request.getScriptPath())
                .evidenceType(evidenceType)
                .scheduleCron(request.getScheduleCron())
                .build();

        job = collectionJobRepository.save(job);
        return CollectionJobDto.Response.from(job, null);
    }

    @Transactional
    public CollectionJobDto.Response update(Long jobId, CollectionJobDto.UpdateRequest request) {
        CollectionJob job = collectionJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("수집 작업", jobId));
        job.update(request.getName(), request.getDescription(), request.getScriptPath(), request.getScheduleCron());

        List<JobExecution> execs = jobExecutionRepository.findByJobIdOrderByCreatedAtDesc(job.getId());
        JobExecution lastExec = execs.isEmpty() ? null : execs.get(0);
        return CollectionJobDto.Response.from(job, lastExec);
    }

    @Transactional
    public void delete(Long jobId) {
        if (!collectionJobRepository.existsById(jobId)) {
            throw new ResourceNotFoundException("수집 작업", jobId);
        }
        collectionJobRepository.deleteById(jobId);
    }

    @Transactional
    public void toggleActive(Long jobId) {
        CollectionJob job = collectionJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("수집 작업", jobId));
        if (job.getIsActive()) {
            job.deactivate();
        } else {
            job.activate();
        }
    }

    /**
     * 수동 실행 — 실제 스크립트 호출은 Phase 2 후속에서 구현.
     * 여기서는 실행 기록만 생성합니다.
     */
    @Transactional
    public CollectionJobDto.ExecutionSummary executeManually(Long jobId) {
        CollectionJob job = collectionJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("수집 작업", jobId));

        JobExecution execution = JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build();
        execution = jobExecutionRepository.save(execution);

        // 시뮬레이션: 성공으로 마킹 (실제로는 비동기 처리)
        execution.markSuccess();
        jobExecutionRepository.save(execution);

        log.info("수집 작업 수동 실행 완료: {} (id={})", job.getName(), jobId);
        return CollectionJobDto.ExecutionSummary.from(execution);
    }
}
