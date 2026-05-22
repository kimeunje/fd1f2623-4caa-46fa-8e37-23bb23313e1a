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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /**
     * v18.9 — N+1 차단: evidenceType + controlNode + framework JOIN FETCH.
     *
     * <p>{@link CollectionJobDto.Response#from} 안에서 controlNode pathline (frameworkId /
     * controlNodeId / controlNodeCode / controlNodeName) 까지 traverse. lazy 그대로 두면 작업 N 개당
     * query 3N+1. {@link CollectionJobRepository#findAllWithGraph()} 로 단일 query hydrate.</p>
     */
    @Transactional(readOnly = true)
    public List<CollectionJobDto.Response> findAll() {
        return collectionJobRepository.findAllWithGraph().stream()
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

        // v18.9 — pathline 4 필드 안전 traverse (entity.evidenceType nullable, controlNode/framework null 가능)
        Long fwId = null;
        Long cnId = null;
        String cnCode = null;
        String cnName = null;
        if (job.getEvidenceType() != null && job.getEvidenceType().getControlNode() != null) {
            var cn = job.getEvidenceType().getControlNode();
            cnId = cn.getId();
            cnCode = cn.getCode();
            cnName = cn.getName();
            if (cn.getFramework() != null) {
                fwId = cn.getFramework().getId();
            }
        }

        return CollectionJobDto.DetailResponse.builder()
                .id(job.getId())
                .name(job.getName())
                .description(job.getDescription())
                .jobType(job.getJobType().name())
                // v18.8.2 — scriptId 노출 (NULL 이면 legacy scriptPath 활용)
                .scriptId(job.getScript() != null ? job.getScript().getId() : null)
                .scriptPath(job.getScriptPath())
                .evidenceTypeId(job.getEvidenceType() != null ? job.getEvidenceType().getId() : null)
                .evidenceTypeName(job.getEvidenceType() != null ?
                        job.getEvidenceType().getName() : null)
                // v18.9 — 양방향 navigation
                .frameworkId(fwId)
                .controlNodeId(cnId)
                .controlNodeCode(cnCode)
                .controlNodeName(cnName)
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
     * 2. <b>v18.8.6 — tx commit 완료 후</b> ScriptExecutionService.executeAsync()로 비동기 위임
     * 3. 즉시 ExecutionSummary 반환 (실행 결과는 비동기로 업데이트)
     *
     * v18.8.2 — script 또는 scriptPath 중 하나라도 있어야 실행 가능.
     *
     * <h4>v18.8.6 — afterCommit 패턴 (commit timing race fix)</h4>
     * <p><b>회귀 원인</b> (v18.8.5 → v18.8.6): v18.8.5 가 executeAsync 시그니처를
     * {@code (CollectionJob, JobExecution)} → {@code (Long, Long)} 로 바꾸면서 async 안에서
     * fresh fetch (findById) 하도록 정합. 하지만 본 메서드의 tx-outer 가 아직 commit 되기 전에
     * async 스레드가 시작하여 findById(executionId) 호출 → 별도 connection 에서는 INSERT
     * 가 안 보임 → {@link ResourceNotFoundException} → catch 의 markExecutionFailed 도 같은
     * 이유로 또 못 찾음 → status=running 영구 좀비.</p>
     *
     * <p><b>정공 fix</b>: {@link TransactionSynchronizationManager#registerSynchronization}
     * 으로 tx commit 후에 executeAsync 호출. async 스레드 시작 시점에는 DB 에 INSERT 가
     * 확정 보장 → findById 정상 성공.</p>
     *
     * <p>운영 로그 (2026-05-21 11:27:40, executionId=11) 가 본 race condition 증거 —
     * INSERT 후 15ms 만에 async 가 "작업 실행을(를) 찾을 수 없습니다 (id=11)" 발생.</p>
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

        // v18.8.6 — tx commit 후 async 호출 (commit timing race fix).
        // executeAsync 가 즉시 호출되면 별도 connection 에서 아직 INSERT 가 안 보임 →
        // findById 가 ResourceNotFoundException 던지고 좀비 발생. afterCommit 으로 보장.
        final Long capturedJobId = job.getId();
        final Long capturedExecutionId = execution.getId();
        final String capturedJobName = job.getName();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                scriptExecutionService.executeAsync(capturedJobId, capturedExecutionId);
                log.info("수집 작업 수동 실행 요청 (afterCommit): {} (jobId={}, executionId={})",
                        capturedJobName, capturedJobId, capturedExecutionId);
            }
        });

        return CollectionJobDto.ExecutionSummary.from(execution);
    }
}