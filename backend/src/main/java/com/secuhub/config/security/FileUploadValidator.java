package com.secuhub.config.security;

import com.secuhub.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v19.10 (SEC-3) — 업로드 파일 검증.
 *
 * <p>확장자 allowlist + 크기 + 매직바이트(시그니처) 검증. 위반 시 400(BusinessException).
 * 증빙 업로드 진입부({@code EvidenceFileService.upload})에서 sha256/dedup 이전에 호출한다.</p>
 *
 * <p><b>매직바이트</b>: 시그니처가 안정적인 형식만 검사한다. office(docx/xlsx/pptx)·hwpx 는
 * ZIP 컨테이너(PK), 레거시 office·hwp 는 OLE2 라 거기까지만 구분한다. txt/csv 등 평문은
 * 신뢰할 시그니처가 없어 확장자만 본다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileUploadValidator {

    private final FileUploadValidationProperties properties;

    // ── 시그니처 ──
    private static final byte[] SIG_PDF  = {0x25, 0x50, 0x44, 0x46};                 // %PDF
    private static final byte[] SIG_PNG  = {(byte) 0x89, 0x50, 0x4E, 0x47};          // .PNG
    private static final byte[] SIG_JPG  = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};  // JPEG
    private static final byte[] SIG_GIF  = {0x47, 0x49, 0x46, 0x38};                 // GIF8
    private static final byte[] SIG_ZIP  = {0x50, 0x4B, 0x03, 0x04};                 // PK..
    private static final byte[] SIG_ZIPE = {0x50, 0x4B, 0x05, 0x06};                 // PK.. (empty)
    private static final byte[] SIG_OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                                            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    /** 확장자 → 허용 시그니처. 없는 확장자(txt/csv/json/xml)는 매직 검사 생략. */
    private static final Map<String, List<byte[]>> SIGNATURES = Map.ofEntries(
            Map.entry("pdf",  List.of(SIG_PDF)),
            Map.entry("png",  List.of(SIG_PNG)),
            Map.entry("jpg",  List.of(SIG_JPG)),
            Map.entry("jpeg", List.of(SIG_JPG)),
            Map.entry("gif",  List.of(SIG_GIF)),
            Map.entry("zip",  List.of(SIG_ZIP, SIG_ZIPE)),
            Map.entry("docx", List.of(SIG_ZIP, SIG_ZIPE)),
            Map.entry("xlsx", List.of(SIG_ZIP, SIG_ZIPE)),
            Map.entry("pptx", List.of(SIG_ZIP, SIG_ZIPE)),
            Map.entry("hwpx", List.of(SIG_ZIP, SIG_ZIPE)),
            Map.entry("doc",  List.of(SIG_OLE2)),
            Map.entry("xls",  List.of(SIG_OLE2)),
            Map.entry("ppt",  List.of(SIG_OLE2)),
            Map.entry("hwp",  List.of(SIG_OLE2))
    );

    public void validate(MultipartFile file) {
        if (!properties.isEnabled()) {
            return;
        }

        if (file == null || file.isEmpty()) {
            throw new BusinessException("빈 파일은 업로드할 수 없습니다.");
        }

        // 크기 (multipart 프레임워크 상한과 별개로 명시 검증)
        if (file.getSize() > properties.getMaxSizeBytes()) {
            throw new BusinessException(
                    "파일이 너무 큽니다. 최대 " + (properties.getMaxSizeBytes() / (1024 * 1024)) + "MB 까지 가능합니다.");
        }

        // 확장자 allowlist
        String ext = StringUtils.getFilenameExtension(file.getOriginalFilename());
        if (ext == null || ext.isBlank()) {
            throw new BusinessException("확장자가 없는 파일은 업로드할 수 없습니다.");
        }
        ext = ext.toLowerCase(Locale.ROOT);
        if (!properties.getAllowedExtensions().contains(ext)) {
            throw new BusinessException("허용되지 않은 파일 형식입니다: ." + ext);
        }

        // 매직바이트 (시그니처가 정의된 형식만)
        if (properties.isVerifyMagicBytes()) {
            List<byte[]> expected = SIGNATURES.get(ext);
            if (expected != null && !matchesAny(file, expected)) {
                log.warn("업로드 매직바이트 불일치: file={}, ext={}", file.getOriginalFilename(), ext);
                throw new BusinessException("파일 내용이 확장자(." + ext + ")와 일치하지 않습니다.");
            }
        }
    }

    private boolean matchesAny(MultipartFile file, List<byte[]> signatures) {
        int maxLen = signatures.stream().mapToInt(s -> s.length).max().orElse(0);
        byte[] header = new byte[maxLen];
        int read;
        try (InputStream is = file.getInputStream()) {
            read = is.readNBytes(header, 0, maxLen);
        } catch (IOException e) {
            throw new BusinessException("파일을 읽을 수 없습니다: " + e.getMessage());
        }
        for (byte[] sig : signatures) {
            if (startsWith(header, read, sig)) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWith(byte[] header, int headerLen, byte[] sig) {
        if (headerLen < sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if (header[i] != sig[i]) {
                return false;
            }
        }
        return true;
    }
}