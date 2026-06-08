package com.secuhub.domain.evidence.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * v19.3 — 진단/실행 산출 디렉터리 GC (carry-over ⑦ 종결).
 *
 * <p>{@code {storage}/output/{jobId}/{executionId}/} 는 자동 수집 실행의 작업 공간으로,
 * 산출 파일 + {@code _diagnosis.json} 이 쌓인다. 진단 내용은 이미
 * {@code job_executions.error_diagnosis} JSON 컬럼(v18.7)에 보존되고, 증빙 파일은
 * {@code {storage}/assets/} 로 복사·등록되므로, output 디렉터리는 보관기간이 지나면
 * 안전하게 삭제할 수 있다.</p>
 *
 * <p><b>판정 기준</b>: DB 결합 없이 "디렉터리 자체 + 직속 파일의 최신 수정시각"이 보관
 * 기준(now − retentionDays) 이전이면 삭제. 직속 파일 최신시각까지 보는 이유는 실행 중인
 * (계속 파일을 쓰는) 디렉터리를 오삭제하지 않기 위함. 좀비 실행은 SchedulerService 가
 * timeout×2(10분)에 정리하므로, 30일 GC 시점에 정당하게 활성인 오래된 디렉터리는 없다.</p>
 *
 * <p><b>설정</b>: {@code app.storage.output-retention-days}(기본 30, ≤0 이면 비활성),
 * {@code app.storage.output-gc-cron}(기본 매일 03:30).</p>
 */
@Slf4j
@Service
public class DiagnosisOutputGcService {

    @Value("${app.storage.path:./storage}")
    private String storagePath;

    @Value("${app.storage.output-retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "${app.storage.output-gc-cron:0 30 3 * * *}")
    public void scheduledCleanup() {
        cleanupOldOutputs();
    }

    /**
     * 보관기간을 지난 실행 산출 디렉터리({@code output/{jobId}/{executionId}})를 삭제.
     * 스케줄러뿐 아니라 운영자가 직접 호출하거나 테스트에서 검증할 수 있도록 public.
     */
    public void cleanupOldOutputs() {
        if (retentionDays <= 0) {
            log.info("진단 출력 GC 비활성화 (output-retention-days={})", retentionDays);
            return;
        }

        Path outputBase = Paths.get(storagePath, "output");
        if (!Files.isDirectory(outputBase)) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int deletedDirs = 0;
        long freedBytes = 0L;

        try (Stream<Path> jobDirs = Files.list(outputBase)) {
            for (Path jobDir : (Iterable<Path>) jobDirs::iterator) {
                if (!Files.isDirectory(jobDir)) {
                    continue;
                }
                try (Stream<Path> execDirs = Files.list(jobDir)) {
                    for (Path execDir : (Iterable<Path>) execDirs::iterator) {
                        if (!Files.isDirectory(execDir)) {
                            continue;
                        }
                        if (effectiveMtime(execDir).isBefore(cutoff)) {
                            long size = dirSize(execDir);
                            if (deleteRecursively(execDir)) {
                                deletedDirs++;
                                freedBytes += size;
                            }
                        }
                    }
                }
                removeIfEmpty(jobDir); // 비어버린 job 디렉터리 정리
            }
        } catch (IOException e) {
            log.warn("진단 출력 GC 중 오류: {}", e.getMessage());
        }

        if (deletedDirs > 0) {
            log.info("진단 출력 GC: {}개 실행 디렉터리 삭제, 약 {} bytes 회수 (보관 {}일)",
                    deletedDirs, freedBytes, retentionDays);
        }
    }

    /** 디렉터리 자체 + 직속 파일의 최신 수정시각. 실행 중 활성 디렉터리 오삭제 방지. */
    private Instant effectiveMtime(Path execDir) throws IOException {
        FileTime latest = Files.getLastModifiedTime(execDir);
        try (Stream<Path> children = Files.list(execDir)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                FileTime t = Files.getLastModifiedTime(child);
                if (t.compareTo(latest) > 0) {
                    latest = t;
                }
            }
        }
        return latest.toInstant();
    }

    private long dirSize(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private boolean deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("삭제 실패: {} ({})", p, e.getMessage());
                }
            });
            return !Files.exists(dir);
        } catch (IOException e) {
            log.warn("디렉터리 삭제 실패: {} ({})", dir, e.getMessage());
            return false;
        }
    }

    private void removeIfEmpty(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            if (s.findAny().isEmpty()) {
                Files.delete(dir);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }
}