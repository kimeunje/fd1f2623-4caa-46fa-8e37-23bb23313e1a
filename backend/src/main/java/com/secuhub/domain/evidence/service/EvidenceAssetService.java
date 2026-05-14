package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.domain.evidence.dto.EvidenceAssetDto;
import com.secuhub.domain.evidence.entity.EvidenceAsset;
import com.secuhub.domain.evidence.repository.EvidenceAssetRepository;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * EvidenceAsset (물리 파일) 서비스. v18.6a 신규.
 *
 * <h3>주요 책임</h3>
 * <ul>
 *   <li>SHA-256 stream 계산 ({@link #computeSha256(MultipartFile)},
 *       {@link #computeSha256(Path)})</li>
 *   <li>sha256 기반 기존 asset 조회 ({@link #findBySha256}) — 중복 감지 dialog (Q1=b)</li>
 *   <li>신규 asset 생성 ({@link #createNewAssetFromMultipart},
 *       {@link #createNewAssetFromPath}) — 2-단계 INSERT 패턴</li>
 *   <li>검색 ({@link #search}) — prod (FULLTEXT) / dev (LIKE fallback) 환경 분기</li>
 *   <li>단건 조회 ({@link #getById})</li>
 *   <li>GC ({@link #gcIfUnused}) — EvidenceFileService.delete 가 위임</li>
 * </ul>
 *
 * <h3>2-단계 INSERT 패턴</h3>
 * <p>{@code asset.id} 가 filePath 의 일부 ({@code assets/{id%1000}/{id}}, Q2=c) 이므로
 * INSERT 후에만 path 확정 가능:</p>
 * <ol>
 *   <li>{@code builder().filePath("PENDING").build()} 로 entity save → id 받음</li>
 *   <li>{@code resolveAssetPath(id)} 로 물리 저장 (createDirectories + transferTo/move)</li>
 *   <li>{@code asset.updateFilePath(absolutePath)} — Hibernate dirty checking 으로
 *       flush 시 UPDATE SQL 발행 (@Transactional 안)</li>
 * </ol>
 *
 * <h3>환경 분기 (L_TEST_BYPASS_FALSE_POSITIVE 사례 ⑦ 정합)</h3>
 * <p>MariaDB FULLTEXT INDEX 는 Flyway V2 에서만 추가됨. dev/test (ddl-auto) 에서는
 * FULLTEXT 자체가 없으므로 native query 실패 가능. {@link #search} 가 Spring profile
 * 기반 분기 — prod 면 {@link EvidenceAssetRepository#searchAssetsFulltext}, 그 외 면
 * {@link EvidenceAssetRepository#searchAssetsLike}.</p>
 *
 * <h3>본 프로젝트 service 컨벤션 정합</h3>
 * <ul>
 *   <li>{@code @Slf4j + @Service + @RequiredArgsConstructor + @Transactional}
 *       (EvidenceFileService / ControlService 정합)</li>
 *   <li>{@code @Value("${app.storage.path:./storage}")} —
 *       ScriptExecutionService 정합</li>
 *   <li>한국어 Javadoc + 한국어 log 메시지</li>
 *   <li>BusinessException / ResourceNotFoundException — 본 프로젝트 공통 예외</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceAssetService {

    private final EvidenceAssetRepository evidenceAssetRepository;
    private final UserRepository userRepository;
    private final Environment environment;

    @Value("${app.storage.path:./storage}")
    private String storagePath;

    // ========================================================================
    // SHA-256 계산
    // ========================================================================

    /**
     * MultipartFile 의 SHA-256 stream 계산 — FE 업로드 흐름.
     */
    public String computeSha256(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            return computeSha256(is);
        } catch (IOException e) {
            throw new BusinessException("파일 읽기 실패 (SHA-256 계산): " + e.getMessage());
        }
    }

    /**
     * Path 의 SHA-256 stream 계산 — ScriptExecutionService.collectOutputFiles 자동 수집용.
     */
    public String computeSha256(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            return computeSha256(is);
        } catch (IOException e) {
            throw new BusinessException("파일 읽기 실패 (SHA-256 계산): " + e.getMessage());
        }
    }

    private String computeSha256(InputStream is) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException("SHA-256 알고리즘 미지원: " + e.getMessage());
        }
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            md.update(buf, 0, n);
        }
        byte[] hash = md.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    // ========================================================================
    // 조회 — 중복 감지 dialog
    // ========================================================================

    /**
     * SHA-256 기반 기존 asset 조회. 중복 감지 dialog 의 "기존 사용" 후보 (Q1=b).
     *
     * <p>같은 해시의 asset 이 여러 개 가능 (Q9 — forceUpload=true 시 별도 생성).
     * 가장 최근 1개 반환.</p>
     */
    @Transactional(readOnly = true)
    public Optional<EvidenceAsset> findBySha256(String sha256) {
        return evidenceAssetRepository.findFirstBySha256OrderByCreatedAtDesc(sha256);
    }

    // ========================================================================
    // 신규 생성 — 2-단계 INSERT 패턴
    // ========================================================================

    /**
     * MultipartFile 기반 신규 asset 생성 — FE 업로드용.
     *
     * <p>호출 측 (EvidenceFileService.upload, Chat 2B) 책임:</p>
     * <ul>
     *   <li>{@link #computeSha256(MultipartFile)} 으로 사전 계산</li>
     *   <li>{@link #findBySha256} 로 중복 검사 + forceUpload 분기</li>
     *   <li>본 method 는 단순 신규 생성 (중복 무시) — Q9 정합</li>
     * </ul>
     *
     * @param file FE 업로드 multipart
     * @param sha256 호출 측이 계산한 해시 (재계산 회피)
     * @param uploader 업로드 사용자 (admin 또는 담당자)
     * @return 영속화된 asset (id + filePath 갱신 완료)
     */
    @Transactional
    public EvidenceAsset createNewAssetFromMultipart(MultipartFile file, String sha256,
                                                      UserPrincipal uploader) {
        User user = (uploader != null)
                ? userRepository.findById(uploader.getUserId()).orElse(null)
                : null;

        // 1. asset entity 우선 save — id 받기 위해 (filePath 는 임시값)
        EvidenceAsset asset = evidenceAssetRepository.save(
                EvidenceAsset.builder()
                        .sha256(sha256)
                        .filePath("PENDING")
                        .fileSize(file.getSize())
                        .originalFileName(file.getOriginalFilename())
                        .uploadedBy(user)
                        .build()
        );

        // 2. 물리 저장 — {storage}/assets/{id % 1000}/{id}
        Path absolutePath = resolveAssetPath(asset.getId());
        try {
            Files.createDirectories(absolutePath.getParent());
            try (InputStream is = file.getInputStream()) {
                Files.copy(is, absolutePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("asset 물리 저장 실패: assetId={}, path={}, error={}",
                    asset.getId(), absolutePath, e.getMessage());
            throw new BusinessException("asset 파일 저장 실패: " + e.getMessage());
        }

        // 3. filePath 갱신 — Hibernate dirty checking flush
        asset.updateFilePath(absolutePath.toString());

        log.info("증빙 자산 신규 등록 (FE 업로드): id={}, sha256={}, size={}, file={}",
                asset.getId(), sha256, file.getSize(), file.getOriginalFilename());
        return asset;
    }

    /**
     * Path 기반 신규 asset 생성 — ScriptExecutionService 자동 수집용.
     *
     * <p>output 디렉토리의 파일을 그대로 asset 으로 이동 (복사가 아닌 move) — 운영 디스크
     * 효율. 자동 수집은 Q7 = 자동 reuse (confirm 없음).</p>
     *
     * @param source script output 디렉토리의 파일 경로
     * @param originalFileName 보존할 원본 파일명 (검색용)
     * @param sha256 호출 측이 계산한 해시
     * @param uploader 자동 수집 — 호출 측에서 null 또는 시스템 user
     * @return 영속화된 asset
     */
    @Transactional
    public EvidenceAsset createNewAssetFromPath(Path source, String originalFileName,
                                                 String sha256, User uploader) {
        long fileSize;
        try {
            fileSize = Files.size(source);
        } catch (IOException e) {
            throw new BusinessException("파일 크기 조회 실패: " + e.getMessage());
        }

        EvidenceAsset asset = evidenceAssetRepository.save(
                EvidenceAsset.builder()
                        .sha256(sha256)
                        .filePath("PENDING")
                        .fileSize(fileSize)
                        .originalFileName(originalFileName)
                        .uploadedBy(uploader)
                        .build()
        );

        Path absolutePath = resolveAssetPath(asset.getId());
        try {
            Files.createDirectories(absolutePath.getParent());
            Files.move(source, absolutePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("asset 물리 이동 실패: assetId={}, source={}, dest={}, error={}",
                    asset.getId(), source, absolutePath, e.getMessage());
            throw new BusinessException("asset 파일 이동 실패: " + e.getMessage());
        }

        asset.updateFilePath(absolutePath.toString());

        log.info("증빙 자산 신규 등록 (script output): id={}, sha256={}, size={}, source={}",
                asset.getId(), sha256, fileSize, source.getFileName());
        return asset;
    }

    /**
     * Storage 안의 asset 절대 경로 조립.
     *
     * <p>{@code {storage.path}/assets/{id % 1000}/{id}} (Q2=c).</p>
     *
     * @param assetId asset.id (NULL 불가)
     * @return 절대 경로 (정규화 + absolute)
     */
    public Path resolveAssetPath(Long assetId) {
        String relPath = EvidenceAsset.buildRelativePath(assetId);
        return Paths.get(storagePath, relPath).toAbsolutePath().normalize();
    }

    // ========================================================================
    // 검색 — controller GET /evidence-assets
    // ========================================================================

    /**
     * 검색 — 환경 분기 (prod = FULLTEXT, dev/test = LIKE fallback) + usedInCount batch.
     *
     * <p>L_TEST_BYPASS_FALSE_POSITIVE 사례 ⑦ 정합: MariaDB FULLTEXT INDEX 가 prod-only
     * 라서 환경 분기 필수. Spring active profile 기반 (option 1, repository Javadoc 참조).</p>
     *
     * <p>usedInCount (Q11) batch query — N+1 회피 ({@link
     * EvidenceAssetRepository#countLinkedFilesByAssetIds}).</p>
     */
    @Transactional(readOnly = true)
    public Page<EvidenceAssetDto.Response> search(EvidenceAssetDto.SearchRequest req,
                                                   Pageable pageable) {
        String query = (req.getQuery() == null) ? "" : req.getQuery().trim();

        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        Page<EvidenceAsset> page;
        if (isProd) {
            page = evidenceAssetRepository.searchAssetsFulltext(
                    query, req.getUploaderId(), req.getFromDate(), req.getToDate(), pageable);
        } else {
            page = evidenceAssetRepository.searchAssetsLike(
                    query, req.getUploaderId(), req.getFromDate(), req.getToDate(), pageable);
        }

        // usedInCount batch — N+1 회피
        List<Long> ids = page.getContent().stream()
                .map(EvidenceAsset::getId)
                .toList();
        Map<Long, Long> countMap;
        if (ids.isEmpty()) {
            countMap = Map.of();
        } else {
            countMap = evidenceAssetRepository.countLinkedFilesByAssetIds(ids).stream()
                    .collect(Collectors.toMap(
                            row -> (Long) row[0],
                            row -> (Long) row[1]));
        }

        return page.map(asset ->
                EvidenceAssetDto.Response.from(asset, countMap.getOrDefault(asset.getId(), 0L)));
    }

    // ========================================================================
    // 단건 조회 — controller GET /evidence-assets/{id}
    // ========================================================================

    @Transactional(readOnly = true)
    public EvidenceAssetDto.Response getById(Long assetId) {
        EvidenceAsset asset = evidenceAssetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 자산", assetId));
        long usedInCount = evidenceAssetRepository.countLinkedFiles(assetId);
        return EvidenceAssetDto.Response.from(asset, usedInCount);
    }

    // ========================================================================
    // GC — 마지막 link 삭제 시 EvidenceFileService.delete 가 위임
    // ========================================================================

    /**
     * Asset GC — reference_count == 0 시 물리 파일 + entity 자동 정리 (Q10).
     *
     * <p>{@code EvidenceFileService.delete} 가 link 삭제 후 본 method 호출 (Chat 2B):</p>
     * <ol>
     *   <li>asset 의 link 카운트 조회</li>
     *   <li>== 0 시 물리 파일 삭제 + entity 삭제</li>
     *   <li>&gt; 0 시 no-op (다른 EvidenceFile 이 같은 asset 참조 중)</li>
     * </ol>
     *
     * <p>본 method 가 RESTRICT FK constraint (Flyway V2) 와 정합 — count &gt; 0 일 때
     * entity 삭제 시도하면 FK 위반 예외, count == 0 일 때만 정상 삭제.</p>
     */
    @Transactional
    public void gcIfUnused(Long assetId) {
        long count = evidenceAssetRepository.countLinkedFiles(assetId);
        if (count > 0) {
            log.debug("asset GC 스킵: id={}, 참조 중 link={}", assetId, count);
            return;
        }

        EvidenceAsset asset = evidenceAssetRepository.findById(assetId).orElse(null);
        if (asset == null) {
            log.debug("asset GC 스킵 — 이미 삭제됨: id={}", assetId);
            return;
        }

        // 1. 물리 파일 삭제 — 실패해도 entity 정리 진행 (운영 admin 정리 carry-over)
        try {
            Path path = Paths.get(asset.getFilePath());
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("asset 물리 파일 삭제 실패 (entity 만 정리): id={}, path={}, error={}",
                    assetId, asset.getFilePath(), e.getMessage());
        }

        // 2. entity 삭제
        evidenceAssetRepository.delete(asset);
        log.info("증빙 자산 GC: id={}, sha256={}", assetId, asset.getSha256());
    }
}