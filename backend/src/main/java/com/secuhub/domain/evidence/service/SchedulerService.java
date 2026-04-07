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
 * 매 분마다 활성화된 작업 중 스케줄이 설정된 작업을 확인하고 실행합니다.
 * 실제 스크립트 호출은 Phase 2 후반부에서 ProcessBuilder 등으로 구현 예정이며,
 * 현재는 실행 기록(JobExecution)을 생성하고 성공으로 마킹하는 시뮬레이션입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final CollectionJobRepository collectionJobRepository;
    private final JobExecutionRepository jobExecutionRepository;

    /**
     * 매 분 실행 — 활성화된 스케줄 작업을 확인하고 실행
     * 실제 Cron 매칭은 간소화하여, 활성화된 작업을 주기적으로 실행합니다.
     *
     * 실무에서는 Spring의 TaskScheduler를 동적으로 등록하거나,
     * Quartz를 사용하여 각 작업별 Cron을 정밀하게 관리합니다.
     */
    @Scheduled(cron = "0 0 * * * ?")  // 매 정각 실행
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
     * 개별 작업 실행
     */
    private void executeJob(CollectionJob job) {
        JobExecution execution = JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build();
        execution = jobExecutionRepository.save(execution);

        try {
            // TODO: 실제 스크립트 실행 로직
            // ProcessBuilder pb = new ProcessBuilder("python3", job.getScriptPath());
            // Process process = pb.start();
            // int exitCode = process.waitFor();

            // 시뮬레이션: 성공 처리
            execution.markSuccess();
            jobExecutionRepository.save(execution);

            log.info("스케줄 작업 성공: {} (id={})", job.getName(), job.getId());
        } catch (Exception e) {
            execution.markFailed(e.getMessage());
            jobExecutionRepository.save(execution);
            log.error("스케줄 작업 실패: {} (id={})", job.getName(), job.getId(), e);
        }
    }
}
