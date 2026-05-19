package com.secuhub.domain.evidence.controller;

import com.secuhub.domain.evidence.entity.JobExecution;
import com.secuhub.domain.evidence.repository.JobExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * v18.7 — 자동 수집 실패 진단 endpoint.
 *
 * <p>selenium wrapper template ({@code /scripts/templates/selenium_wrapper.py}) 가
 * 산출하는 진단 자산을 어드민이 다운로드 / 표시할 수 있도록 노출.</p>
 *
 * <p>경로 결정 (Q17=A 정합) — JobExecution 에 별도 경로 컬럼 없음.
 * {@code {storagePath}/output/{jobId}/{executionId}/_diag_*.{png,html}} 로 도출
 * (ScriptExecutionService 의 {@code prepareOutputDir} 패턴과 동일).</p>
 *
 * <p>권한 — 모두 admin 한정 (L_AUTH_CONVENTION_GREP 정합, ADMIN 대문자).</p>
 *
 * <p>진단 JSON 자체 ({@code errorDiagnosis} 필드) 는 본 Controller 에서 노출 안 함.
 * 기존 JobExecution 응답 (CollectionJob detail / job-executions list) 에 필드로
 * 포함되어 FE 가 {@code parseDiagnosis} helper 로 파싱.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/job-executions")
@RequiredArgsConstructor
public class JobExecutionController {

    private final JobExecutionRepository jobExecutionRepository;

    @Value("${app.storage.path:./storage}")
    private String storagePath;

    /**
     * 실패 시점 스크린샷 다운로드 (image/png 스트리밍).
     *
     * <p>FE 의 {@code <img :src="getDiagnosisScreenshotUrl(executionId)" />} 에 직접 활용.</p>
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}/diagnosis/screenshot")
    public ResponseEntity<Resource> getDiagnosisScreenshot(@PathVariable Long id) {
        JobExecution execution = jobExecutionRepository.findById(id).orElse(null);
        if (execution == null) {
            log.debug("v18.7 진단 스크린샷 — JobExecution 없음: id={}", id);
            return ResponseEntity.notFound().build();
        }

        Path screenshotPath = resolveOutputFile(execution, "_diag_screenshot.png");
        if (!Files.exists(screenshotPath) || !Files.isReadable(screenshotPath)) {
            log.debug("v18.7 진단 스크린샷 파일 없음: {}", screenshotPath);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(screenshotPath));
    }

    /**
     * 실패 시점 페이지 소스 다운로드 (text/html attachment).
     *
     * <p>FE 의 {@code window.open(getDiagnosisPageSourceUrl(executionId), '_blank')} 활용.
     * 별도 탭에서 파일 다운로드.</p>
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}/diagnosis/page-source")
    public ResponseEntity<Resource> getDiagnosisPageSource(@PathVariable Long id) {
        JobExecution execution = jobExecutionRepository.findById(id).orElse(null);
        if (execution == null) {
            log.debug("v18.7 진단 페이지 소스 — JobExecution 없음: id={}", id);
            return ResponseEntity.notFound().build();
        }

        Path pageSourcePath = resolveOutputFile(execution, "_diag_page_source.html");
        if (!Files.exists(pageSourcePath) || !Files.isReadable(pageSourcePath)) {
            log.debug("v18.7 진단 페이지 소스 파일 없음: {}", pageSourcePath);
            return ResponseEntity.notFound().build();
        }

        String downloadFileName = "page_source_" + id + ".html";
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + downloadFileName + "\"")
                .body(new FileSystemResource(pageSourcePath));
    }

    /**
     * v18.7 — output 디렉토리의 진단 자산 경로 도출.
     *
     * <p>ScriptExecutionService 의 {@code prepareOutputDir} 패턴 정합 —
     * {@code {storagePath}/output/{jobId}/{executionId}/{fileName}}.</p>
     */
    private Path resolveOutputFile(JobExecution execution, String fileName) {
        // job 은 LAZY 이지만 id 만 사용하므로 proxy initialization 불필요.
        Long jobId = execution.getJob().getId();
        Long executionId = execution.getId();
        return Paths.get(storagePath, "output",
                String.valueOf(jobId),
                String.valueOf(executionId),
                fileName)
                .toAbsolutePath().normalize();
    }
}