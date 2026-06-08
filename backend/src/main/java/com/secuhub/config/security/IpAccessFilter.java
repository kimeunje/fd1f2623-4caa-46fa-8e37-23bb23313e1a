package com.secuhub.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secuhub.common.dto.ApiResponse;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.domain.access.service.IpAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * v19.0 — 계정별 IP 접근 게이트 (BE-1).
 *
 * <p>{@code JwtAuthenticationFilter} 뒤에 등록되어, JWT 로 인증이 끝난 요청에 대해서만
 * 계정별 허용 IP 를 검사한다. 토큰이 허용 대역 밖에서 재사용되는 것까지 매 요청 차단
 * (로그인 시점만 막으면 발급된 토큰이 다른 IP 에서 통과하므로).</p>
 *
 * <p>미인증 요청(로그인 · 정적 리소스)은 {@code UserPrincipal} 이 없어 그대로 통과한다 —
 * 로그인 시점의 계정별 검사는 {@code AuthService} 가 담당.</p>
 *
 * <p>거부 시 보안 필터 체인의 {@code ExceptionTranslationFilter} 앞 위치라 예외를
 * 던지지 않고 403 JSON({@link ApiResponse})을 직접 기록한다 — {@code JwtAccessDeniedHandler}
 * 패턴 정합.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpAccessFilter extends OncePerRequestFilter {

    private final IpAccessService ipAccessService;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal) {

            String clientIp = clientIpResolver.resolve(request);

            if (!ipAccessService.isAllowed(principal.getUserId(), clientIp)) {
                log.warn("IP 접근 거부: userId={} ip={} uri={}",
                        principal.getUserId(), clientIp, request.getRequestURI());
                writeForbidden(response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiResponse<Void> body = ApiResponse.error("허용되지 않은 IP 에서의 접근입니다.");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}