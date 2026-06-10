package com.secuhub.config.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v19.10 (SEC-3) — 업로드 파일 검증 설정.
 *
 * <pre>
 * app:
 *   security:
 *     upload:
 *       enabled: true
 *       verify-magic-bytes: true
 *       max-size-bytes: 52428800        # 50MB (multipart 상한과 정렬)
 *       allowed-extensions: [pdf, doc, docx, xls, xlsx, ppt, pptx, hwp, hwpx,
 *                            png, jpg, jpeg, gif, txt, csv, zip]
 * </pre>
 *
 * 컴플라이언스 증빙 특성상 한글 문서(hwp/hwpx)와 번들 zip 을 기본 포함한다. 운영 정책에
 * 따라 allowed-extensions 에서 제거(예: zip)하거나 추가할 수 있다.
 */
@Component
@ConfigurationProperties(prefix = "app.security.upload")
@Getter
@Setter
public class FileUploadValidationProperties {

    /** 비활성화 시 검증을 건너뛴다. */
    private boolean enabled = true;

    /** 매직바이트(파일 시그니처) 검증 여부. */
    private boolean verifyMagicBytes = true;

    /** 허용 최대 크기(byte). multipart 프레임워크 상한과 정렬 권장. */
    private long maxSizeBytes = 52_428_800L; // 50MB

    /** 허용 확장자(소문자, 점 없이). */
    private List<String> allowedExtensions = List.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "hwp", "hwpx", "png", "jpg", "jpeg", "gif",
            "txt", "csv", "zip"
    );
}