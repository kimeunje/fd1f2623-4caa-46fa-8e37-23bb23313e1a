package com.secuhub;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.config.security.FileUploadValidationProperties;
import com.secuhub.config.security.FileUploadValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v19.10 (SEC-3) — 업로드 파일 검증. 순수 단위 테스트(Spring context 불필요).
 */
@DisplayName("v19.10 - 업로드 파일 검증")
class FileUploadValidatorTest {

    private FileUploadValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FileUploadValidator(new FileUploadValidationProperties()); // 기본값
    }

    @Test
    @DisplayName("[Upload] 정상 PDF — 통과")
    void testValidPdf() {
        MockMultipartFile f = new MockMultipartFile(
                "file", "policy.pdf", "application/pdf",
                "%PDF-1.7\n%binary".getBytes(StandardCharsets.UTF_8));
        assertThatCode(() -> validator.validate(f)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[Upload] 허용되지 않은 확장자(.exe) — 거부")
    void testDisallowedExtension() {
        MockMultipartFile f = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream",
                new byte[]{0x4D, 0x5A, 0x00, 0x00}); // MZ
        assertThatThrownBy(() -> validator.validate(f))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("허용되지 않은");
    }

    @Test
    @DisplayName("[Upload] 확장자 위장(.png 인데 내용은 텍스트) — 매직바이트 불일치 거부")
    void testMagicMismatch() {
        MockMultipartFile f = new MockMultipartFile(
                "file", "fake.png", "image/png",
                "this is not a png".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> validator.validate(f))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("일치하지 않");
    }

    @Test
    @DisplayName("[Upload] docx(PK 시그니처) — 통과")
    void testValidDocxZipSignature() {
        MockMultipartFile f = new MockMultipartFile(
                "file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00});
        assertThatCode(() -> validator.validate(f)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[Upload] txt — 시그니처 없는 형식은 확장자만 검사(내용 무관 통과)")
    void testPlainTextNoMagic() {
        MockMultipartFile f = new MockMultipartFile(
                "file", "memo.txt", "text/plain",
                "아무 텍스트나".getBytes(StandardCharsets.UTF_8));
        assertThatCode(() -> validator.validate(f)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[Upload] 빈 파일 / 확장자 없음 — 거부")
    void testEmptyAndNoExtension() {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "x.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> validator.validate(empty))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("빈 파일");

        MockMultipartFile noExt = new MockMultipartFile(
                "file", "noextension", "application/octet-stream",
                "data".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> validator.validate(noExt))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("확장자가 없");
    }
}