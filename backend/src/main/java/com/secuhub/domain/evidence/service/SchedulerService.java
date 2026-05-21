package com.secuhub.domain.evidence.service;

import com.secuhub.domain.evidence.entity.CollectionJob;
import com.secuhub.domain.evidence.entity.ExecutionStatus;
import com.secuhub.domain.evidence.entity.JobExecution;
import com.secuhub.domain.evidence.repository.CollectionJobRepository;
import com.secuhub.domain.evidence.repository.JobExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Scheduler 기반 수집 작업 자동 실행 + 좀비 실행 정리.
 *
 * 매 정각마다 활성화된 작업 중 스케줄이 설정된 작업을 확인하고
 * ScriptExecutionService를 통해 실제 스크립트를 실행합니다.
 *
 * 실무에서는 Spring의 TaskScheduler를 동적으로 등록하거나,
 * Quartz를 사용하여 각 작업별 Cron을 정밀하게 관리합니다.
 *
 * <h3>v18.8.5 — script (UID) 분기 추가 (v18.8.2 누락분)</h3>
 * <p>{@link CollectionJob#getScript()} (Script entity FK) 도 있으면 실행. 옛 scriptPath
 * only 검사로 script UID 만 설정된 작업이 스킵되던 누락 fix.</p>
 *
 * <h3>v18.8.6 — 좀비 실행 정리 (운영 무재부팅 환경 정합)</h3>
 * <p>운영 reality: 서버는 한 번 켜지면 거의 재부팅 안 함. 좀비 실행이 발생할 수 있는 경로:</p>
 * <ul>
 *   <li>BE 가 OOM / SIGKILL / 정전 등으로 markFailed 호출 못 한 채 종료 → status=running 잔존</li>
 *   <li>ProcessBuilder 가 hang + waitFor timeout 후에도 자식 chrome 프로세스 살아있음</li>
 *   <li>async 의 catch 안에서 markFailed 자체가 또 예외로 실패</li>
 *   <li>v18.8.5 의 commit timing race (v18.8.6 의 afterCommit 패턴으로 종결됐지만 안전망)</li>
 * </ul>
 *
 * <p>2중 안전망:</p>
 * <ol>
 *   <li><b>startup hook</b> ({@link #cleanupZombiesOnStartup}): BE 시작 시점에 timeout × 2
 *       이상 경과한 running 들 일괄 정리 — 옛 서버 비정상 종료 흔적 청소</li>
 *   <li><b>정기 검사</b> ({@link #cleanupZombieExecutionsScheduled}): 매 5분, 동일 임계값으로
 *       검사 — 운영 중에도 좀비 자동 회수</li>
 * </ol>
 *
 * <p>설정값:</p>
 * <ul>
 *   <li>좀비 임계값 = {@code app.scripts.timeout-seconds} × 2 (기본 300 × 2 = 600초 = 10분)</li>
 *   <li>정기 검사 주기 = 5분 (fixedDelay)</li>
 *   <li>startup 시점은 초기 60초 지연 후 (다른 시작 작업 안 방해)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final CollectionJobRepository collectionJobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final ScriptExecutionService scriptExecutionService;

    @Value("${app.scripts.timeout-seconds:300}")
    private long scriptTimeoutSeconds;

    /** v18.8.6 — 좀비 검출 임계값 multiplier. 정상 timeout 의 N배 이상이면 좀비. */
    private static final int ZOMBIE_THRESHOLD_MULTIPLIER = 2;

    /** v18.8.6 — 정기 좀비 검사 주기 (ms). */
    private static final long ZOMBIE_CLEANUP_INTERVAL_MS = 5 * 60 * 1000L;

    // ========================================================================
    // 자동 스케줄 실행 (기존)
    // ========================================================================

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
     * 개별 작업 실행 — ScriptExecutionService로 위임.
     *
     * <p>v18.8.5 — script (UID) || scriptPath 둘 중 하나라도 있으면 실행 (v18.8.2 정합).</p>
     */
    private void executeJob(CollectionJob job) {
        // v18.8.5 — script (UID) 또는 scriptPath (legacy) 중 하나라도 있으면 실행
        boolean hasScript = job.getScript() != null;
        boolean hasScriptPath = job.getScriptPath() != null && !job.getScriptPath().isBlank();
        if (!hasScript && !hasScriptPath) {
            log.warn("스크립트 미설정 — 스킵: {} (jobId={})", job.getName(), job.getId());
            return;
        }

        JobExecution execution = JobExecution.builder()
                .job(job)
                .status(ExecutionStatus.running)
                .startedAt(LocalDateTime.now())
                .build();
        execution = jobExecutionRepository.save(execution);

        // 스케줄러에서는 동기 실행 (이미 스케줄러 스레드에서 실행 중, same session → LAZY OK)
        scriptExecutionService.executeSync(job, execution);
    }

    // ========================================================================
    // v18.8.6 — 좀비 실행 정리 (운영 무재부팅 환경 안전망)
    // ========================================================================

    /**
     * v18.8.6 — BE 시작 시점에 좀비 일괄 정리.
     *
     * <p>옛 BE 인스턴스가 비정상 종료 (OOM / SIGKILL / kill -9) 로 markFailed 호출 못 한
     * 채 죽으면, status=running 인 JobExecution 들이 DB 에 남음. 새 BE 가 뜨자마자 이런
     * 좀비를 일괄 정리.</p>
     *
     * <p>{@link ApplicationReadyEvent} 시점에 호출 — bean 초기화 + DataSource 활성 후.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void cleanupZombiesOnStartup() {
        long count = cleanupZombieExecutionsInternal("startup");
        if (count == 0) {
            log.info("BE 시작 시 좀비 검출 — 없음");
        }
    }

    /**
     * v18.8.6 — 정기 좀비 검사 (매 5분).
     *
     * <p>BE 가 안 죽고 long-running 중에도 좀비가 누적되는 경로 안전망 — async catch 의
     * markFailed 가 또 실패하는 경우 / chrome 좀비 프로세스로 인한 무한 hang 등.</p>
     *
     * <p>{@code fixedDelay} — 직전 실행 종료 후 N ms 대기. 직전 검사가 길어져도 겹쳐 실행
     * 안 됨 (cleanup 메서드 자체가 작은 작업이라 보통 ms 단위 종료).</p>
     */
    @Scheduled(fixedDelay = ZOMBIE_CLEANUP_INTERVAL_MS, initialDelay = 60_000L)
    @Transactional
    public void cleanupZombieExecutionsScheduled() {
        cleanupZombieExecutionsInternal("scheduled");
    }

    /**
     * v18.8.6 — 좀비 정리 본체 (startup / scheduled 공용).
     *
     * <p>{@code started_at} 이 {@code (timeout × 2)} 초 이전인 running 들을 일괄 markFailed.
     * 임계값 = timeout × 2 = 10분 (기본 timeout=300초 기준). 정상 5분 실행이 우연히 6~7분으로
     * 늘어도 안 잡힘.</p>
     *
     * <p>각 JobExecution 마다 markFailed + save → 개별 변경 추적 (전체 일괄 UPDATE 가 아닌
     * entity 단위로 처리 — auditing / event publishing 정합).</p>
     *
     * @param trigger 로그 식별용 (startup / scheduled)
     * @return 정리된 좀비 건수
     */
    public long cleanupZombieExecutionsInternal(String trigger) {
        long zombieThresholdSec = scriptTimeoutSeconds * ZOMBIE_THRESHOLD_MULTIPLIER;
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(zombieThresholdSec);

        List<JobExecution> zombies = jobExecutionRepository
                .findByStatusAndStartedAtBefore(ExecutionStatus.running, cutoff);

        if (zombies.isEmpty()) {
            return 0;
        }

        log.warn("좀비 실행 감지 ({}) — {}건 정리 시작 (cutoff={}, 임계값={}초)",
                trigger, zombies.size(), cutoff, zombieThresholdSec);

        for (JobExecution exec : zombies) {
            String reason = String.format(
                    "좀비 실행 자동 정리 (%s) — started_at=%s 이후 %d초 초과 (임계값=%d초). " +
                    "원인 추정: BE 비정상 종료 / Python 또는 chrome 프로세스 hang / async 예외 처리 실패.",
                    trigger,
                    exec.getStartedAt(),
                    zombieThresholdSec,
                    zombieThresholdSec
            );
            exec.markFailed(reason);
            jobExecutionRepository.save(exec);
            log.warn("  좀비 정리: executionId={}, jobId={}, started_at={}",
                    exec.getId(),
                    exec.getJob() != null ? exec.getJob().getId() : null,
                    exec.getStartedAt());
        }

        log.warn("좀비 실행 정리 완료 ({}) — {}건", trigger, zombies.size());
        return zombies.size();
    }
}