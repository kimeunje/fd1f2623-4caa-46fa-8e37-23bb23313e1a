package com.secuhub.config;

import com.secuhub.config.jwt.JwtAccessDeniedHandler;
import com.secuhub.config.jwt.JwtAuthenticationEntryPoint;
import com.secuhub.config.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import com.secuhub.config.security.LoginRateLimitFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final com.secuhub.config.security.IpAccessFilter ipAccessFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final LoginRateLimitFilter loginRateLimitFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF 비활성화 (JWT 사용)
            .csrf(csrf -> csrf.disable())

            // v19.8 — 보안 헤더. 기본 X-Frame-Options(DENY) / X-Content-Type-Options(nosniff)
            // 는 그대로 두고 CSP / Referrer-Policy / Permissions-Policy 추가.
            // 폐쇄망 평문(HTTP) 이므로 HSTS 는 추가하지 않음(HTTP 에서 무의미).
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self'; " +
                    "style-src 'self' 'unsafe-inline'; " +   // Vue/Tailwind/PrimeIcons 인라인 스타일 허용
                    "img-src 'self' data:; " +
                    "font-src 'self' data:; " +
                    "connect-src 'self'; " +
                    "object-src 'none'; " +
                    "frame-ancestors 'none'; " +
                    "base-uri 'self'; " +
                    "form-action 'self'"))
                .referrerPolicy(ref -> ref.policy(
                    ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .addHeaderWriter(new StaticHeadersWriter(
                    "Permissions-Policy",
                    "geolocation=(), camera=(), microphone=(), payment=(), usb=()"))
            )

            // 세션 사용하지 않음
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // 예외 처리
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                .accessDeniedHandler(jwtAccessDeniedHandler)
            )

            // URL별 접근 권한 설정
            .authorizeHttpRequests(auth -> auth
                // === 프론트엔드 정적 리소스 (Vue SPA) ===
                .requestMatchers(
                    "/", "/index.html",
                    "/assets/**", "/favicon.ico",
                    "/*.js", "/*.css", "/*.png", "/*.svg", "/*.ico"
                ).permitAll()

                // === SPA 클라이언트 라우팅 지원 ===
                // Vue Router의 History 모드 경로들 (API가 아닌 경로)
                //
                // v18.9.5 — 모든 SPA 경로에 /** 추가. 정확 매치만 허용했을 때
                // 깊은 path (/controls/1, /controls/1/3/evidence-types/2 등) 새로고침
                // 시 .anyRequest().authenticated() 분기로 떨어져 JWT 필터가 토큰 없는
                // HTML 요청에 401 JSON 응답 → 브라우저가 JSON 그대로 표시.
                // /api/v1/** 는 별도 매칭 룰이라 충돌 없음.
                .requestMatchers(
                    "/login",
                    "/dashboard", "/dashboard/**",
                    "/controls", "/controls/**",
                    "/jobs", "/jobs/**",
                    "/files", "/files/**",
                    "/vulns", "/vulns/**",
                    "/accounts", "/accounts/**",
                    "/settings", "/settings/**",
                    "/my-tasks", "/my-tasks/**",   // v11: 담당자 "내 할 일" 페이지 (Phase 5-5)
                    "/dev/**"
                ).permitAll()

                // === 인증 API ===
                .requestMatchers("/api/v1/auth/login").permitAll()

                // === 개발 도구 ===
                .requestMatchers("/h2-console/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // === 증빙 수집 (관리자 전용 영역) ===
                // Framework / Control / Job 은 관리자만. 담당자는 "내 할 일" 경로로만 접근.
                .requestMatchers("/api/v1/frameworks/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/controls/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/evidence/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/jobs/**").hasRole("ADMIN")

                // === 관리자 대시보드 (v18.3 추가 — v16.4a 시점부터 잠재 잠재 버그였음) ===
                // SecurityConfig URL 매핑 부재 시 controller 의 @PreAuthorize 만으로는 anonymous 요청
                // 차단 불가. fallback .anyRequest().authenticated() 가 URL 레벨 통과 → method 레벨
                // @PreAuthorize 가 SecurityContext 비어있는 anonymous 평가 시점에
                // AuthenticationCredentialsNotFoundException throw → GlobalExceptionHandler 가 500
                // 매핑. v18.3 환경에서 발현된 회귀를 정공 fix.
                .requestMatchers("/api/v1/dashboard/**").hasRole("ADMIN")

                // === 증빙 파일 (역할 혼재 영역) — Phase 5-2 변경 ===
                // URL 레벨은 인증만 요구하고, 메서드별 @PreAuthorize 와
                // EvidenceAuthService 가 admin / 담당자(소유자) 구분을 담당.
                .requestMatchers("/api/v1/evidence-files/**").authenticated()

                // === 담당자 "내 할 일" API 예약 (Phase 5-5 에서 구현) ===
                // permission_evidence 체크는 엔드포인트 메서드 레벨에서 수행.
                .requestMatchers("/api/v1/my-tasks/**").authenticated()

                // === 시스템 관리 — ADMIN 역할만 ===
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                // === 그 외 API — 인증된 사용자만 ===
                .anyRequest().authenticated()
            )

            // H2 콘솔 iframe 허용
            .headers(headers ->
                headers.frameOptions(frame -> frame.sameOrigin())
            )

            // JWT 필터 등록
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

            // 로그인 brute-force 차단 (JWT 필터 앞에서 short-circuit)
            .addFilterBefore(loginRateLimitFilter, JwtAuthenticationFilter.class)

            // 계정별 IP 접근 게이트
            .addFilterAfter(ipAccessFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}