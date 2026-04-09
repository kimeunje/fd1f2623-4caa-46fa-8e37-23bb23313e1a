package com.secuhub.domain.evidence.service;

import com.secuhub.domain.evidence.entity.CollectionJob;
import com.secuhub.domain.evidence.entity.ExecutionStatus;
import com.secuhub.domain.evidence.entity.JobExecution;
import com.secuhub.domain.evidence.repository.CollectionJobRepository;
import com.secuhub.domain.evidence.repository.JobExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Scheduler 기반 수집 작업 자동 실행
 *
 * 매 정각마다 활성화된 작업 중 스케줄이 설정된 작업을 확인하고
 * ScriptExecutionService를 통해 실제 스크립트를 실행합니다.
 *
 * 실무에서는 Spring의 TaskScheduler를 동적으로 등록하거나,
 * Quartz를 사용하여 각 작업별 Cron을 정밀하게 관리합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final CollectionJobRepository collectionJobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final ScriptExecutionService scriptExecutionService;

    /**
     * 매 정각 실행 — 활성화된 스케줄 작업을 확인하고 실행
     */
    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void executeScheduledJobs() {
        List<CollectionJob> activeJobs = collectionJobRepository.findByIsActiveTrueAndScheduleCronIsNotNull();

        if (activeJobs.isEmpty()) {
            return;
        }

        log.info("스케줄 작업 실행 시작 — 대상: {}건", activeJobs.size());

        for (CollectionJob job : activeJobs) {
            executeJob(job);
        }

        log.info("스케줄 작업 실행 완료");
    }

    /**
     * 개별 작업 실행 — ScriptExecutionService로 위임
     */
    private void executeJob(CollectionJob job) {
        // 스크립트 경로가 없는 작업은 스킵
        if (job.getScriptPath() == null || job.getScriptPath().isBlank()) {
            log.warn("스크립트 경로 미설정 — 스킵: {} (jobId={})", job.getName(), job.getId());
            return;
        }

        JobExecution execution = JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build();
        execution = jobExecutionRepository.save(execution);

        // 스케줄러에서는 동기 실행 (이미 스케줄러 스레드에서 실행 중)
        scriptExecutionService.executeSync(job, execution);
    }
}
