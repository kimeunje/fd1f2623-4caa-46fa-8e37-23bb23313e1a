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
                    "/dev/**"
                ).permitAll()

                // === 인증 API ===
                .requestMatchers("/api/v1/auth/login").permitAll()

                // === 개발 도구 ===
                .requestMatchers("/h2-console/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // === 증빙 수집 관련 — ADMIN 역할만 ===
                .requestMatchers("/api/v1/frameworks/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/controls/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/evidence/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/jobs/**").hasRole("ADMIN")

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