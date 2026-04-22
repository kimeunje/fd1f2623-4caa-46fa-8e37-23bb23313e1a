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

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF 비활성화 (JWT 사용)
            .csrf(csrf -> csrf.disable())

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
                .requestMatchers(
                    "/login", "/dashboard", "/controls", "/jobs", "/files",
                    "/vulns", "/accounts", "/settings",
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
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}