package com.secuhub;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 5-15d (v15.8) — GlobalExceptionHandler L24 픽스 회귀 보호.
 *
 * <h3>도입 동기</h3>
 * <p>v15.4 부수 발견 (L24): {@code GlobalExceptionHandler} 의
 * {@code NoResourceFoundException} 매핑 부재로 모든 잘못된 path 가 500 반환 +
 * generic Exception 핸들러의 {@code log.error} stacktrace 노이즈. v15.6 에서
 * 픽스 (404 + 표준 메시지 + {@code log.warn} 격하) 됐으나 회귀 테스트 누락.
 * v15.5.1 의 운영 영향 (사용자 leaf 클릭 시 콘솔 500 폭발) 가 본 갭의 직접 비용 —
 * 본 테스트로 회귀 차단.</p>
 *
 * <h3>왜 {@code addFilters = false}</h3>
 * <p>Spring Security filter chain 이 DispatcherServlet 보다 먼저 실행되므로,
 * 인증 토큰 없이는 {@code 401 Unauthorized} 가 먼저 반환되어 본 테스트의 의도
 * (DispatcherServlet 의 매핑 fallback → NoResourceFoundException → 핸들러)
 * 검증 불가. {@code addFilters = false} 로 security bypass → 핸들러 동작만 격리.</p>
 *
 * <p>spec §11 활성 학습 L24 의 직접 closure. v15.8 신규.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("Phase 5-15d (v15.8) — GlobalExceptionHandler L24 회귀 보호")
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("[L24] 매핑 없는 path GET → 404 + 표준 에러 응답 shape")
    void testNoResourceFoundReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/this-path-does-not-exist/12345"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."));

        System.out.println("✅ [L24] 매핑 없는 path → 404 + 표준 메시지 (v15.6 픽스 회귀 보호)");
    }
}