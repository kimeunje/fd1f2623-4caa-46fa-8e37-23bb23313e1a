package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.ExecutionStatus;
import com.secuhub.domain.evidence.entity.JobExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface JobExecutionRepository extends JpaRepository<JobExecution, Long> {

    List<JobExecution> findByJobIdOrderByCreatedAtDesc(Long jobId);

    /**
     * v18.8.6 — 좀비 실행 검출용.
     *
     * <p>{@code started_at} 이 cutoff 이전인 {@code status=running} 의 JobExecution 들을 반환.
     * SchedulerService 의 startup hook + 정기 @Scheduled 검사가 본 메서드 활용.</p>
     *
     * <p>운영 무재부팅 환경 정합 — BE 가 거의 안 꺼지므로 startup hook 만으로는 회수 부족,
     * 정기 검사 (매 5분, timeout × 2 = 10분 임계값) 가 진짜 안전망.</p>
     *
     * @param status  통상 {@link ExecutionStatus#running}
     * @param cutoff  본 시각 이전 started_at 을 가진 row 들을 반환
     * @return 좀비 후보 목록 (빈 리스트 가능)
     */
    List<JobExecution> findByStatusAndStartedAtBefore(ExecutionStatus status, LocalDateTime cutoff);
}