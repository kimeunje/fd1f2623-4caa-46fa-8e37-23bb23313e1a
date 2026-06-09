package com.secuhub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * v19.8 (SEC-1) — 보안 헤더 회귀 보호.
 *
 * <p>응답 상태와 무관하게 HeaderWriterFilter 가 헤더를 기록하므로, 검증 실패(400) 응답에서
 * 헤더 존재만 확인. 폐쇄망 평문(HTTP) 이라 HSTS 는 검사하지 않는다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("v19.8 - 보안 헤더")
class SecurityHeadersTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("[Headers] 응답에 CSP / Referrer-Policy / Permissions-Policy / 기본 헤더 존재")
    void testSecurityHeadersPresent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\"}"))
                // Spring Security 기본 헤더
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                // 값은 프로필별 상이(test=SAMEORIGIN / prod=DENY) → 존재만 검증.
                // 프레이밍 차단의 권위는 CSP frame-ancestors 'none' 가 보장.
                .andExpect(header().exists("X-Frame-Options"))
                // v19.8 추가 헤더
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().exists("Permissions-Policy"));

        System.out.println("✅ [Headers] 보안 헤더 정상 노출");
    }
}