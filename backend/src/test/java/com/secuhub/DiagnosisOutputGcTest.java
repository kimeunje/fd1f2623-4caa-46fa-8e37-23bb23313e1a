package com.secuhub;

import com.secuhub.domain.evidence.service.DiagnosisOutputGcService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v19.3 — 진단 출력 GC (carry-over ⑦).
 *
 * <p>{@code app.storage.path} 를 임시 디렉터리로 동적 주입, 보관일수 7 로 설정.
 * 오래된 실행 디렉터리는 삭제되고, 최근 디렉터리 및 최근 파일이 있는 디렉터리는 보존됨을 검증.</p>
 */
@SpringBootTest
@DisplayName("v19.3 - 진단 출력 GC")
class DiagnosisOutputGcTest {

    static Path tempStorage;

    static {
        try {
            tempStorage = Files.createTempDirectory("secuhub-gc-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.storage.path", () -> tempStorage.toString());
        registry.add("app.storage.output-retention-days", () -> 7);
    }

    @Autowired
    private DiagnosisOutputGcService gcService;

    @AfterAll
    static void cleanup() throws IOException {
        if (Files.exists(tempStorage)) {
            try (Stream<Path> w = Files.walk(tempStorage)) {
                w.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("[GC] 보관기간 지난 디렉터리 삭제 + 최근 디렉터리 보존")
    void testOldDeletedRecentKept() throws IOException {
        Path output = Files.createDirectories(tempStorage.resolve("output").resolve("10"));
        Path oldExec = Files.createDirectories(output.resolve("100"));
        Path recentExec = Files.createDirectories(output.resolve("101"));
        Files.writeString(oldExec.resolve("result.txt"), "old");
        Files.writeString(recentExec.resolve("result.txt"), "new");

        // oldExec 의 파일과 디렉터리를 30일 전으로 backdate
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofDays(30)));
        Files.setLastModifiedTime(oldExec.resolve("result.txt"), old);
        Files.setLastModifiedTime(oldExec, old);

        gcService.cleanupOldOutputs();

        assertThat(Files.exists(oldExec)).isFalse();
        assertThat(Files.exists(recentExec)).isTrue();
        System.out.println("✅ [GC] 오래된 디렉터리 삭제, 최근 보존");
    }

    @Test
    @DisplayName("[GC] 디렉터리 mtime 은 오래됐어도 직속 파일이 최근이면 보존(활성 보호)")
    void testRecentFileKeepsDir() throws IOException {
        Path output = Files.createDirectories(tempStorage.resolve("output").resolve("20"));
        Path activeExec = Files.createDirectories(output.resolve("200"));
        Files.writeString(activeExec.resolve("live.txt"), "writing"); // mtime = now

        // 디렉터리 자체만 30일 전으로 backdate (파일은 최근)
        Files.setLastModifiedTime(activeExec, FileTime.from(Instant.now().minus(Duration.ofDays(30))));

        gcService.cleanupOldOutputs();

        assertThat(Files.exists(activeExec)).isTrue();
        System.out.println("✅ [GC] 최근 파일 있는 디렉터리 보존");
    }
}