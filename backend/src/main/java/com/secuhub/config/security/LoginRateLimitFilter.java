package com.secuhub.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.common.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v19.9 (SEC-2) — 로그인 무차별 대입(brute-force) 방어.
 *
 * <p>POST /api/v1/auth/login 에만 작동. IP별로 윈도 내 비밀번호 틀린 시도(401)를 세고,
 * {@code maxAttempts} 이상이면 bcrypt 검증에 도달하기 전에 429 로 short-circuit 한다.
 * 로그인 성공(200) 시 해당 IP 카운터를 리셋한다.</p>
 *
 * <p>의존성 추가 없이 in-memory {@link ConcurrentHashMap}. 폐쇄망이라 distinct IP 수가
 * 제한적이지만, 만료 엔트리는 시간당 sweep 으로 정리한다. IP 해석은 ACL 과 동일하게
 * {@link ClientIpResolver} 재사용.</p>
 *
 * <p>카운팅 대상은 401(자격증명 불일치)만 — 400(검증 오류)/403(비활성·IP 거부)은 세지 않는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;
    private final LoginRateLimitProperties properties;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    private static final class Counter {
        int count;
        long windowStart;
        Counter(long now) {
            this.windowStart = now;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!properties.isEnabled() || !isLoginAttempt(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = clientIpResolver.resolve(request);
        long now = System.currentTimeMillis();
        long windowMs = properties.getWindowMinutes() * 60_000L;

        if (isBlocked(ip, now, windowMs)) {
            log.warn("로그인 rate limit 차단: ip={} (윈도 {}분 내 실패 {}회 초과)",
                    ip, properties.getWindowMinutes(), properties.getMaxAttempts());
            writeTooManyRequests(response);
            return; // bcrypt 도달 전 차단
        }

        filterChain.doFilter(request, response);

        onResult(ip, response.getStatus(), now, windowMs);
    }

    private boolean isLoginAttempt(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && LOGIN_PATH.equals(request.getRequestURI());
    }

    private boolean isBlocked(String ip, long now, long windowMs) {
        Counter c = counters.get(ip);
        if (c == null) return false;
        synchronized (c) {
            if (now - c.windowStart > windowMs) {
                c.count = 0;
                c.windowStart = now;
            }
            return c.count >= properties.getMaxAttempts();
        }
    }

    private void onResult(String ip, int status, long now, long windowMs) {
        if (status == 200) {
            counters.remove(ip);      // 성공 → 리셋
            return;
        }
        if (status == 401) {          // 자격증명 불일치만 카운트
            Counter c = counters.computeIfAbsent(ip, k -> new Counter(now));
            synchronized (c) {
                if (now - c.windowStart > windowMs) {
                    c.count = 0;
                    c.windowStart = now;
                }
                c.count++;
            }
        }
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429); // 429 Too Many Requests
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error("로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.")));
    }

    /** 만료 엔트리 정리(시간당). @EnableScheduling 은 기존 활성. */
    @Scheduled(fixedRate = 3_600_000L)
    public void sweepExpired() {
        long now = System.currentTimeMillis();
        long windowMs = properties.getWindowMinutes() * 60_000L;
        counters.entrySet().removeIf(entry -> {
            Counter c = entry.getValue();
            synchronized (c) {
                return now - c.windowStart > windowMs;
            }
        });
    }
}