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
    private final ScriptRepository scriptRepository;            // v18.8.2 — Script entity 조회용
    private final ScriptExecutionService scriptExecutionService;

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
                // v18.8.2 — scriptId 노출 (NULL 이면 legacy scriptPath 활용)
                .scriptId(job.getScript() != null ? job.getScript().getId() : null)
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

        // v18.8.2 — scriptId 우선, 없으면 scriptPath fallback (Q2=A)
        Script script = null;
        if (request.getScriptId() != null) {
            script = scriptRepository.findById(request.getScriptId())
                    .orElseThrow(() -> new ResourceNotFoundException("스크립트", request.getScriptId()));
        }

        CollectionJob job = CollectionJob.builder()
                .name(request.getName())
                .description(request.getDescription())
                .jobType(jobType)
                .script(script)                            // v18.8.2
                .scriptPath(request.getScriptPath())       // legacy fallback
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

        // v18.8.2 — scriptId 갱신 (있으면 새 Script 연결)
        if (request.getScriptId() != null) {
            Script script = scriptRepository.findById(request.getScriptId())
                    .orElseThrow(() -> new ResourceNotFoundException("스크립트", request.getScriptId()));
            job.setScript(script);
        }

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
     * 수동 실행 — ProcessBuilder 기반 비동기 스크립트 실행
     *
     * 1. JobExecution을 running 상태로 생성
     * 2. ScriptExecutionService.executeAsync()로 비동기 위임
     * 3. 즉시 ExecutionSummary 반환 (실행 결과는 비동기로 업데이트)
     *
     * v18.8.2 — script 또는 scriptPath 중 하나라도 있어야 실행 가능.
     */
    @Transactional
    public CollectionJobDto.ExecutionSummary executeManually(Long jobId) {
        CollectionJob job = collectionJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("수집 작업", jobId));

        // v18.8.2 — script 또는 scriptPath 둘 중 하나라도 있으면 실행 가능
        boolean hasScript = job.getScript() != null;
        boolean hasScriptPath = job.getScriptPath() != null && !job.getScriptPath().isBlank();
        if (!hasScript && !hasScriptPath) {
            throw new BusinessException("스크립트 경로가 설정되지 않은 작업입니다. 작업 설정을 확인해주세요.");
        }

        JobExecution execution = JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build();
        execution = jobExecutionRepository.save(execution);

        // 비동기 스크립트 실행 위임
        scriptExecutionService.executeAsync(job, execution);

        log.info("수집 작업 수동 실행 요청: {} (jobId={}, executionId={})",
                job.getName(), jobId, execution.getId());
        return CollectionJobDto.ExecutionSummary.from(execution);
    }
}