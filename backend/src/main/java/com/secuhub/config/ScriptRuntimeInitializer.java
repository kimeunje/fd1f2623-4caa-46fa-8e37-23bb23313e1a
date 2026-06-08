package com.secuhub.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 수집 프레임워크 파이썬 파일 자동 배포기.
 *
 * <p>서버 기동 시, 앱(jar)에 번들된 수집 프레임워크 파일
 * ({@code secuhub_task.py}, {@code selenium_wrapper.py})을 실행용 디렉토리
 * ({@code app.scripts.framework-dir})로 복사한다. 관리자가 수동으로 배치/갱신할
 * 필요가 없어진다 — <b>앱 버전 = 프레임워크 버전</b>.</p>
 *
 * <h3>동작</h3>
 * <ol>
 *   <li>{@code classpath*:runtime-scripts/*.py} 를 스캔</li>
 *   <li>각 파일을 {@code framework-dir} 로 복사 (매 기동마다 덮어쓰기)</li>
 * </ol>
 *
 * <h3>분리 원칙</h3>
 * <p>사용자가 작성하는 스크립트는 {@code app.scripts.base-dir} 의 {@code {uuid}.py}
 * 로 저장된다. 프레임워크 파일은 그와 <b>별개</b>인 {@code framework-dir} 에 둔다 —
 * 사용자 스크립트와 한 폴더에 섞이지 않는다.</p>
 *
 * <h3>import 정합</h3>
 * <p>ScriptExecutionService 가 스크립트 실행 시 {@code PYTHONPATH} 에 framework-dir 를
 * 포함시키므로, 사용자 스크립트의 {@code from secuhub_task import collect_task} /
 * {@code from selenium_wrapper import ...} 가 별도 경로 조작 없이 resolve 된다.</p>
 *
 * <h3>실행 순서</h3>
 * <p>{@code @Order(0)} — DataInitializer / EvidenceDataInitializer 보다 먼저 실행되어,
 * 어떤 수집 작업이 돌기 전에 프레임워크 파일이 준비됨을 보장한다.</p>
 */
@Slf4j
@Component
@Order(0)
public class ScriptRuntimeInitializer implements ApplicationRunner {

    /** jar 에 번들된 프레임워크 파일 위치 (src/main/resources/runtime-scripts/). */
    private static final String BUNDLED_LOCATION = "classpath*:runtime-scripts/*.py";

    @Value("${app.scripts.framework-dir:./scripts-framework}")
    private String frameworkDir;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path targetDir = Paths.get(frameworkDir).toAbsolutePath().normalize();
        Files.createDirectories(targetDir);

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(BUNDLED_LOCATION);

        if (resources.length == 0) {
            log.warn("번들된 수집 프레임워크 파일을 찾지 못했습니다 ({}). " +
                    "src/main/resources/runtime-scripts/ 에 secuhub_task.py / selenium_wrapper.py 배치를 확인하세요.",
                    BUNDLED_LOCATION);
            return;
        }

        int deployed = 0;
        for (Resource res : resources) {
            String filename = res.getFilename();
            if (filename == null || !filename.endsWith(".py")) continue;

            // 파일명만 사용 (path traversal 방지)
            String safeName = Paths.get(filename).getFileName().toString();
            Path target = targetDir.resolve(safeName);

            try (InputStream in = res.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                deployed++;
                log.info("수집 프레임워크 배포: {} → {}", safeName, target);
            } catch (Exception e) {
                // 한 파일 실패가 전체 기동을 막지 않도록 — 경고만 남기고 계속
                log.error("수집 프레임워크 배포 실패: {} (target={}), error={}",
                        safeName, target, e.getMessage());
            }
        }

        log.info("수집 프레임워크 자동 배포 완료 — {}개 파일, dir={}", deployed, targetDir);
    }
}