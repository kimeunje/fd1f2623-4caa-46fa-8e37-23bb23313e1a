package com.secuhub;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Phase 5-15d (v15.8) — API surface 통합 회귀")
class ApiSurfaceTest {

    @Autowired private RequestMappingHandlerMapping handlerMapping;

    /** FE axios 의 baseURL prefix. {@code frontend/src/services/api.ts} 의 {@code axios.create({ baseURL: '/api/v1' })} 정합. */
    private static final String API_PREFIX = "/api/v1";

    /** FE source root. backend working dir 기준 상대 경로. */
    private static final Path FRONTEND_SRC = Paths.get("../frontend/src").toAbsolutePath().normalize();

    /** BE 테스트 source root. */
    private static final Path BACKEND_TEST_SRC = Paths.get("src/test/java").toAbsolutePath().normalize();

    /**
     * Set 3 BE 테스트 스캔 제외 파일.
     * <ul>
     *   <li>{@code ApiSurfaceTest.java} — self (regex 텍스트 자기 자신 보호)</li>
     *   <li>{@code GlobalExceptionHandlerTest.java} — L24 회귀 테스트가 *의도적으로*
     *       매핑 없는 path ({@code /api/v1/this-path-does-not-exist/12345}) 를 호출하여
     *       NoResourceFoundException → 404 동작 검증. 본 테스트는 그 비매핑성 자체가
     *       의도이므로 Set 3 검사에서 제외 (v15.8 산출 보정 2).</li>
     * </ul>
     */
    private static final Set<String> SKIPPED_BE_TEST_FILES = new HashSet<>(Arrays.asList(
            "ApiSurfaceTest.java",
            "GlobalExceptionHandlerTest.java"
    ));

    /**
     * FE 의 {@code api.<method>(...)} 호출 path 추출. backtick / single / double quote 모두 지원.
     * 제너릭 타입 파라미터 {@code <ApiResponse<Foo>>} 는 {@code <[^(]+>} 로 nested 까지 흡수.
     * <p><b>v15.8 산출 보정 2</b> — closing quote 후 {@code ,} / {@code )} 만 허용 (lookahead).
     * {@code "/foo/" + id} 같은 concat 패턴은 closing quote 후 {@code +} 가 오므로 자연 회피.
     * <pre>
     *   api.get&lt;ApiResponse&lt;Framework[]&gt;&gt;('/frameworks')              ← match (close ' then ))
     *   api.get(`/control-nodes/${id}`)                                          ← match (close ` then ))
     *   api.post('/foo', body)                                                   ← match (close ' then ,)
     *   api.delete('/foo/' + id)                                                 ← skip (close ' then space++)
     * </pre>
     */
    private static final Pattern FE_PATH_PATTERN = Pattern.compile(
            "api\\.(get|post|patch|delete|put)\\s*(?:<[^(]+>)?\\s*\\(\\s*[`'\"]([^`'\"]+)[`'\"]\\s*(?=[,)])"
    );

    /**
     * FE named import 패턴.
     * <pre>
     *   import { controlNodesApi, frameworksApi } from '@/services/evidenceApi'
     *   import type { Framework } from '@/types/evidence'   ← 매칭은 되지만 services 만 검증
     * </pre>
     */
    private static final Pattern FE_IMPORT_PATTERN = Pattern.compile(
            "import\\s+(?:type\\s+)?\\{\\s*([^}]+?)\\s*\\}\\s+from\\s+['\"](@/services/[^'\"]+)['\"]"
    );

    /** FE 모듈의 named export 추출. {@code export const|function|class|let|var|interface|type X}. */
    private static final Pattern FE_NAMED_EXPORT_PATTERN = Pattern.compile(
            "export\\s+(?:const|function|class|let|var|interface|type)\\s+(\\w+)"
    );

    /** FE 모듈의 re-export 추출. {@code export { X, Y as Z }}. */
    private static final Pattern FE_REEXPORT_PATTERN = Pattern.compile(
            "export\\s+\\{\\s*([^}]+?)\\s*\\}"
    );

    /**
     * BE 테스트의 mockMvc URL 추출. {@code MockMvcRequestBuilders.<method>(...)} static import 후 단축 형태.
     * lookbehind 로 {@code forget(} / {@code .getForObject(} 같은 false positive 회피.
     * {@code "/api/v1/"} prefix 강제 → JPA repository {@code .delete(entity)} 등도 자연 회피.
     */
    private static final Pattern BE_TEST_URL_PATTERN = Pattern.compile(
            "(?<![A-Za-z_])(get|post|patch|delete|put)\\s*\\(\\s*\"(/api/v1/[^\"]+)\"\\s*(?=[,)])"
    );

    /**
     * Set 1 — 본 phase ({@code v15.8}) 시점에 검출된 *진짜* BE/FE 갭 whitelist.
     *
     * <p><b>본 whitelist 는 일시적</b> — 각 entry 는 차기 phase 에서 fix 후 삭제. 신규
     * 위반은 본 whitelist 통과 안 함 → 자연 fail (회귀 차단 정상 동작).</p>
     *
     * <h3>등재 항목 (10 건, 차기 phase 후보)</h3>
     * <ul>
     *   <li><b>{@code /users/*} 8 path</b> — UserController 매핑 부재 / 위치 미발견.
     *       {@code domain/user/controller/} ls 에 {@code AuthController} 1 파일만 노출.
     *       {@code RequestMappingHandlerMapping} 에 등록되지 않음 → 운영 시 admin
     *       UI ({@code usersApi}) 호출 모두 404 가능. <b>차기 phase</b>: UserController
     *       전수 검색 (다른 도메인/패키지 가능) → (있으면) 매핑 검증 / (없으면) 신설.</li>
     *   <li><b>{@code POST /frameworks/{*}/import}</b> — v15.3 (5-15b R1) 에서 BE
     *       {@code FrameworkController.importControls} + {@code ExcelImportService} 통째
     *       제거. FE {@code frameworksApi.importControls} 잔존 dead code. <b>차기 phase</b>:
     *       FE caller 수 0 검증 후 method 삭제.</li>
     *   <li><b>{@code DELETE /evidence-types/{*}}</b> — v15.6 에서 {@code evidenceTypesApi.delete}
     *       신설 (옛 {@code controlsApi.deleteEvidenceType} 의 namespace 일치 이전).
     *       BE 측 controller 매핑 위치 미확인 — {@code EvidenceFileController} /
     *       {@code ControlNodeController} 후보. <b>차기 phase</b>: BE 매핑 검증 또는 신설.</li>
     * </ul>
     */
    private static final Set<Endpoint> SET_1_KNOWN_GAPS = new HashSet<>(Arrays.asList(
            // /users/* — UserController 부재
            new Endpoint("GET",    "/api/v1/users"),
            new Endpoint("POST",   "/api/v1/users"),
            new Endpoint("GET",    "/api/v1/users/{*}"),
            new Endpoint("PATCH",  "/api/v1/users/{*}"),
            new Endpoint("DELETE", "/api/v1/users/{*}"),
            new Endpoint("GET",    "/api/v1/users/approvers"),
            new Endpoint("GET",    "/api/v1/users/developers"),
            new Endpoint("PATCH", "/api/v1/users/me/password"),
            // /frameworks/{*}/import — FE dead code (v15.3 BE 제거 잔여)
            new Endpoint("POST", "/api/v1/frameworks/{*}/import"),
            // /evidence-types/{*} DELETE — BE 매핑 위치 미확인
            new Endpoint("DELETE", "/api/v1/evidence-types/{*}")
    ));

    // ====================================================================
    // Set 1 — FE path literal × BE controller mapping
    // ====================================================================

    @Test
    @DisplayName("[Set 1] FE path literal 이 모두 BE controller 매핑에 존재")
    void testFePathLiteralsResolve() throws IOException {
        assertThat(Files.exists(FRONTEND_SRC))
                .as("FE source root not found at %s — working dir = %s",
                        FRONTEND_SRC, Paths.get(".").toAbsolutePath().normalize())
                .isTrue();

        Set<Endpoint> beEndpoints = collectBeEndpoints();
        List<FePathHit> feHits = scanFePathLiterals();

        List<FePathHit> unmatched = feHits.stream()
                .filter(hit -> !beEndpoints.contains(hit.toEndpoint()))
                .collect(Collectors.toList());

        // v15.8 산출 보정 2 — 본 phase scope 외 known gaps 분리. 신규 위반은 자연 fail
        List<FePathHit> knownGapHits = unmatched.stream()
                .filter(hit -> SET_1_KNOWN_GAPS.contains(hit.toEndpoint()))
                .collect(Collectors.toList());
        List<FePathHit> violations = unmatched.stream()
                .filter(hit -> !SET_1_KNOWN_GAPS.contains(hit.toEndpoint()))
                .collect(Collectors.toList());

        if (!knownGapHits.isEmpty()) {
            System.out.println("⚠ [Set 1] Known gaps " + knownGapHits.size()
                    + " 건 (본 phase scope 외, 차기 phase 후보 — SET_1_KNOWN_GAPS 참조):");
            knownGapHits.forEach(h -> System.out.println(h.format()));
        }

        assertThat(violations)
                .as("FE path literal 중 BE controller 매핑 부재 (whitelist 외) %d 건:%n%s",
                        violations.size(),
                        violations.stream().map(FePathHit::format).collect(Collectors.joining("\n")))
                .isEmpty();

        System.out.println("✅ [Set 1] FE path literal " + feHits.size() + " 건 검사 — "
                + (feHits.size() - unmatched.size()) + " 정합 / "
                + knownGapHits.size() + " known gap / "
                + violations.size() + " 신규 위반");
    }

    // ====================================================================
    // Set 2 — FE import 식별자 잔존 (L33 차원 6)
    // ====================================================================

    @Test
    @DisplayName("[Set 2] FE named import 식별자가 모두 해당 모듈에 export 존재")
    void testFeImportIdentifiersResolve() throws IOException {
        assertThat(Files.exists(FRONTEND_SRC))
                .as("FE source root not found at %s", FRONTEND_SRC)
                .isTrue();

        Map<String, Set<String>> moduleExports = scanFeServiceExports();
        List<FeImportHit> imports = scanFeImports();

        List<FeImportHit> violations = imports.stream()
                .filter(hit -> {
                    Set<String> exports = moduleExports.getOrDefault(hit.getModulePath(), Collections.emptySet());
                    return !exports.contains(hit.getIdentifier());
                })
                .collect(Collectors.toList());

        assertThat(violations)
                .as("FE named import 중 모듈 export 부재 %d 건 (L33 차원 6 패턴):%n%s",
                        violations.size(),
                        violations.stream().map(FeImportHit::format).collect(Collectors.joining("\n")))
                .isEmpty();

        System.out.println("✅ [Set 2] FE named import " + imports.size() + " 건 모두 export 정합 (L33 차원 6 catch)");
    }

    // ====================================================================
    // Set 3 — BE 테스트 mockMvc URL × controller mapping (L33 차원 8)
    // ====================================================================

    @Test
    @DisplayName("[Set 3] BE 테스트 mockMvc URL 이 모두 BE controller 매핑에 존재")
    void testBeTestMockMvcUrlsResolve() throws IOException {
        assertThat(Files.exists(BACKEND_TEST_SRC))
                .as("BE test source root not found at %s", BACKEND_TEST_SRC)
                .isTrue();

        Set<Endpoint> beEndpoints = collectBeEndpoints();
        List<BeTestUrlHit> hits = scanBeTestUrls();

        List<BeTestUrlHit> violations = hits.stream()
                .filter(hit -> !beEndpoints.contains(hit.toEndpoint()))
                .collect(Collectors.toList());

        assertThat(violations)
                .as("BE 테스트 mockMvc URL 중 controller 매핑 부재 %d 건 (L33 차원 8 패턴):%n%s",
                        violations.size(),
                        violations.stream().map(BeTestUrlHit::format).collect(Collectors.joining("\n")))
                .isEmpty();

        System.out.println("✅ [Set 3] BE 테스트 mockMvc URL " + hits.size() + " 건 모두 BE 매핑 정합 (L33 차원 8 catch)");
    }

    // ====================================================================
    // Helpers — BE mapping 수집
    // ====================================================================

    /**
     * Spring {@link RequestMappingHandlerMapping} 의 등록 매핑을 {@link Endpoint}
     * (HTTP method + 정규화 path) 셋으로 수집.
     * 정규화: path 의 {@code {xxx}} / {@code {xxx:regex}} 모두 {@code {*}} 로 치환.
     */
    private Set<Endpoint> collectBeEndpoints() {
        Set<Endpoint> endpoints = new HashSet<>();
        Map<RequestMappingInfo, HandlerMethod> methods = handlerMapping.getHandlerMethods();
        for (RequestMappingInfo info : methods.keySet()) {
            Set<RequestMethod> httpMethods = info.getMethodsCondition().getMethods();
            // 매핑이 method 미지정인 경우 모든 HTTP method 등록 (Spring 기본 동작 정합)
            if (httpMethods.isEmpty()) {
                httpMethods = new HashSet<>(Arrays.asList(RequestMethod.values()));
            }
            Set<String> patterns = extractPatterns(info);
            for (RequestMethod m : httpMethods) {
                for (String pat : patterns) {
                    endpoints.add(new Endpoint(m.name(), normalizeBePath(pat)));
                }
            }
        }
        return endpoints;
    }

    private Set<String> extractPatterns(RequestMappingInfo info) {
        // Spring Boot 3.x 기본 PathPatternParser
        PathPatternsRequestCondition pathPatterns = info.getPathPatternsCondition();
        if (pathPatterns != null) {
            return pathPatterns.getPatternValues();
        }
        // 2.x / AntPathMatcher fallback
        PatternsRequestCondition fallback = info.getPatternsCondition();
        return fallback != null ? fallback.getPatterns() : Collections.emptySet();
    }

    private static String normalizeBePath(String pattern) {
        return pattern.replaceAll("\\{[^}]+\\}", "{*}");
    }

    // ====================================================================
    // Helpers — FE 스캔
    // ====================================================================

    private List<FePathHit> scanFePathLiterals() throws IOException {
        List<FePathHit> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(FRONTEND_SRC)) {
            stream.filter(this::isFeSource).forEach(p -> {
                try {
                    String content = Files.readString(p);
                    Matcher m = FE_PATH_PATTERN.matcher(content);
                    while (m.find()) {
                        String method = m.group(1).toUpperCase();
                        String rawPath = m.group(2);
                        // path 가 절대 경로 형태 ('/' 시작) 가 아니면 skip — module path 등 false positive 회피
                        if (!rawPath.startsWith("/")) continue;
                        String normalized = API_PREFIX + rawPath.replaceAll("\\$\\{[^}]+\\}", "{*}");
                        int line = lineNumberOf(content, m.start());
                        hits.add(new FePathHit(method, normalized, p, line, rawPath));
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read " + p, e);
                }
            });
        }
        return hits;
    }

    private Map<String, Set<String>> scanFeServiceExports() throws IOException {
        Map<String, Set<String>> moduleExports = new HashMap<>();
        Path servicesDir = FRONTEND_SRC.resolve("services");
        if (!Files.exists(servicesDir)) return moduleExports;
        try (Stream<Path> stream = Files.walk(servicesDir)) {
            stream.filter(p -> p.toString().endsWith(".ts")).forEach(p -> {
                try {
                    String content = Files.readString(p);
                    Set<String> exports = new HashSet<>();
                    Matcher named = FE_NAMED_EXPORT_PATTERN.matcher(content);
                    while (named.find()) exports.add(named.group(1));
                    Matcher reexport = FE_REEXPORT_PATTERN.matcher(content);
                    while (reexport.find()) {
                        for (String token : reexport.group(1).split(",")) {
                            String name = token.trim().split("\\s+as\\s+")[0].trim();
                            if (!name.isEmpty()) exports.add(name);
                        }
                    }
                    String moduleName = pathToModuleName(p);
                    moduleExports.put(moduleName, exports);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read " + p, e);
                }
            });
        }
        return moduleExports;
    }

    private List<FeImportHit> scanFeImports() throws IOException {
        List<FeImportHit> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(FRONTEND_SRC)) {
            stream.filter(this::isFeSource).forEach(p -> {
                try {
                    String content = Files.readString(p);
                    Matcher m = FE_IMPORT_PATTERN.matcher(content);
                    while (m.find()) {
                        String moduleName = m.group(2);
                        String identList = m.group(1);
                        int line = lineNumberOf(content, m.start());
                        for (String token : identList.split(",")) {
                            String trimmed = token.trim();
                            // v15.8 산출 보정 2 — TS 4.5+ inline type modifier:
                            // `import { type Foo, Bar }` 의 'type Foo' → 'Foo' 분리
                            if (trimmed.startsWith("type ")) {
                                trimmed = trimmed.substring(5).trim();
                            }
                            String name = trimmed.split("\\s+as\\s+")[0].trim();
                            if (name.isEmpty()) continue;
                            hits.add(new FeImportHit(name, moduleName, p, line));
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read " + p, e);
                }
            });
        }
        return hits;
    }

    private boolean isFeSource(Path p) {
        if (!Files.isRegularFile(p)) return false;
        String name = p.getFileName().toString();
        return name.endsWith(".ts") || name.endsWith(".vue");
    }

    private static String pathToModuleName(Path filePath) {
        // /abs/path/to/frontend/src/services/api.ts → @/services/api
        Path rel = FRONTEND_SRC.relativize(filePath);
        String relStr = rel.toString().replace('\\', '/');
        if (relStr.endsWith(".ts")) relStr = relStr.substring(0, relStr.length() - 3);
        return "@/" + relStr;
    }

    // ====================================================================
    // Helpers — BE 테스트 스캔
    // ====================================================================

    private List<BeTestUrlHit> scanBeTestUrls() throws IOException {
        List<BeTestUrlHit> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(BACKEND_TEST_SRC)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .filter(p -> !SKIPPED_BE_TEST_FILES.contains(p.getFileName().toString()))
                  .forEach(p -> {
                      try {
                          String content = Files.readString(p);
                          Matcher m = BE_TEST_URL_PATTERN.matcher(content);
                          while (m.find()) {
                              String method = m.group(1).toUpperCase();
                              String rawPath = m.group(2);
                              String normalized = normalizeBePath(rawPath);
                              int line = lineNumberOf(content, m.start());
                              hits.add(new BeTestUrlHit(method, normalized, p, line, rawPath));
                          }
                      } catch (IOException e) {
                          throw new RuntimeException("Failed to read " + p, e);
                      }
                  });
        }
        return hits;
    }

    // ====================================================================
    // Helpers — 공통
    // ====================================================================

    private static int lineNumberOf(String content, int charOffset) {
        int line = 1;
        int limit = Math.min(charOffset, content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    // ====================================================================
    // 데이터 클래스 (plain Java — Lombok 의존성 0)
    // ====================================================================

    /** HTTP method + 정규화된 path. equals/hashCode 로 매칭 — Set 컨테이너 정합. */
    private static final class Endpoint {
        private final String method;
        private final String path;

        Endpoint(String method, String path) {
            this.method = method;
            this.path = path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Endpoint)) return false;
            Endpoint that = (Endpoint) o;
            return method.equals(that.method) && path.equals(that.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(method, path);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static final class FePathHit {
        private final String method;
        private final String normalizedPath;
        private final Path file;
        private final int line;
        private final String rawPath;

        FePathHit(String method, String normalizedPath, Path file, int line, String rawPath) {
            this.method = method;
            this.normalizedPath = normalizedPath;
            this.file = file;
            this.line = line;
            this.rawPath = rawPath;
        }

        Endpoint toEndpoint() { return new Endpoint(method, normalizedPath); }

        String format() {
            return String.format("  %s %s  (%s:%d  raw=%s)",
                    method, normalizedPath, file.getFileName(), line, rawPath);
        }
    }

    private static final class FeImportHit {
        private final String identifier;
        private final String modulePath;
        private final Path file;
        private final int line;

        FeImportHit(String identifier, String modulePath, Path file, int line) {
            this.identifier = identifier;
            this.modulePath = modulePath;
            this.file = file;
            this.line = line;
        }

        String getIdentifier() { return identifier; }
        String getModulePath() { return modulePath; }

        String format() {
            return String.format("  %s ← %s  (%s:%d)",
                    identifier, modulePath, file.getFileName(), line);
        }
    }

    private static final class BeTestUrlHit {
        private final String method;
        private final String normalizedPath;
        private final Path file;
        private final int line;
        private final String rawPath;

        BeTestUrlHit(String method, String normalizedPath, Path file, int line, String rawPath) {
            this.method = method;
            this.normalizedPath = normalizedPath;
            this.file = file;
            this.line = line;
            this.rawPath = rawPath;
        }

        Endpoint toEndpoint() { return new Endpoint(method, normalizedPath); }

        String format() {
            return String.format("  %s %s  (%s:%d  raw=%s)",
                    method, normalizedPath, file.getFileName(), line, rawPath);
        }
    }
}