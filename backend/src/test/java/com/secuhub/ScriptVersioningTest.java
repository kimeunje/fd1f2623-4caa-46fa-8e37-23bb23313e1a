package com.secuhub;

import com.secuhub.domain.evidence.dto.ScriptManagementDto;
import com.secuhub.domain.evidence.dto.ScriptVersionDto;
import com.secuhub.domain.evidence.entity.Script;
import com.secuhub.domain.evidence.repository.ScriptRepository;
import com.secuhub.domain.evidence.service.ScriptManagementService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v19.4 — 스크립트 버전 이력 + 롤백 (carry-over ⑤).
 *
 * <p>{@code app.scripts.base-dir} 를 임시 디렉터리로 동적 주입. 서비스가 실제 파일을
 * 쓰고 DB 에 버전을 적재하는 흐름을 검증한다.</p>
 */
@SpringBootTest
@DisplayName("v19.4 - 스크립트 버전 이력 + 롤백")
class ScriptVersioningTest {

    static Path tempScripts;

    static {
        try {
            tempScripts = Files.createTempDirectory("secuhub-scriptver-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.scripts.base-dir", () -> tempScripts.toString());
    }

    @Autowired
    private ScriptManagementService scriptService;
    @Autowired
    private ScriptRepository scriptRepository;

    @AfterAll
    static void cleanup() throws IOException {
        if (Files.exists(tempScripts)) {
            try (Stream<Path> w = Files.walk(tempScripts)) {
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
    @DisplayName("[Version] 작성 → v1, 수정 → v2 (이력 보존)")
    @Transactional
    void testCreateAndUpdateRecordVersions() {
        var created = scriptService.create(new ScriptManagementDto.CreateRequest("print('v1')"));
        Long id = created.getId();

        scriptService.update(id, new ScriptManagementDto.UpdateRequest("print('v2')"));

        List<ScriptVersionDto.VersionResponse> versions = scriptService.listVersions(id);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getVersionNo()).isEqualTo(1);
        assertThat(versions.get(1).getVersionNo()).isEqualTo(2);

        assertThat(scriptService.getVersionContent(id, 1).getContent()).isEqualTo("print('v1')");
        assertThat(scriptService.getVersionContent(id, 2).getContent()).isEqualTo("print('v2')");
        // 현재 실행본 = 최신
        assertThat(scriptService.getContent(id).getContent()).isEqualTo("print('v2')");

        System.out.println("✅ [Version] 작성/수정 이력 적재");
    }

    @Test
    @DisplayName("[Rollback] v1 로 롤백 → v3 전진 기록, 실행본 = v1 내용")
    @Transactional
    void testRollbackForward() {
        var created = scriptService.create(new ScriptManagementDto.CreateRequest("V1"));
        Long id = created.getId();
        scriptService.update(id, new ScriptManagementDto.UpdateRequest("V2"));
        scriptService.update(id, new ScriptManagementDto.UpdateRequest("V3-broken"));

        // v1 로 롤백
        var result = scriptService.rollback(id, 1);

        assertThat(result.getContent()).isEqualTo("V1");
        assertThat(scriptService.getContent(id).getContent()).isEqualTo("V1");

        List<ScriptVersionDto.VersionResponse> versions = scriptService.listVersions(id);
        assertThat(versions).hasSize(4); // v1, v2, v3, v4(=롤백)
        var latest = versions.get(versions.size() - 1);
        assertThat(latest.getVersionNo()).isEqualTo(4);
        assertThat(latest.getNote()).contains("v1");
        assertThat(scriptService.getVersionContent(id, 4).getContent()).isEqualTo("V1");
        // 이력 불변 — v3 내용 그대로 보존
        assertThat(scriptService.getVersionContent(id, 3).getContent()).isEqualTo("V3-broken");

        System.out.println("✅ [Rollback] 전진형 롤백 + 이력 불변");
    }

    @Test
    @DisplayName("[Legacy] 버전 0건 스크립트는 조회 시 현재 내용을 v1 로 자동 시드")
    @Transactional
    void testLegacySeed() throws IOException {
        // 버저닝 이전처럼 entity + 파일만 있고 버전 row 0건인 상태 구성
        String filename = "legacy-" + System.nanoTime() + ".py";
        Files.writeString(tempScripts.resolve(filename), "legacy-content", StandardCharsets.UTF_8);
        Script legacy = scriptRepository.save(Script.builder()
                .filePath(filename)
                .contentSize((long) "legacy-content".getBytes(StandardCharsets.UTF_8).length)
                .build());

        List<ScriptVersionDto.VersionResponse> versions = scriptService.listVersions(legacy.getId());

        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getVersionNo()).isEqualTo(1);
        assertThat(scriptService.getVersionContent(legacy.getId(), 1).getContent())
                .isEqualTo("legacy-content");

        System.out.println("✅ [Legacy] v1 자동 시드");
    }
}