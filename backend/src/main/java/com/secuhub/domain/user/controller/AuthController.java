package com.secuhub.domain.user.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.config.security.ClientIpResolver;
import com.secuhub.domain.user.dto.LoginRequest;
import com.secuhub.domain.user.dto.LoginResponse;
import com.secuhub.domain.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;

    /**
     * 로그인
     * POST /api/v1/auth/login
     *
     * <p>v19.0: 계정별 IP 접근 제어를 위해 클라이언트 IP 를 해석하여 service 로 전달.</p>
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        LoginResponse response = authService.login(request, clientIp);
        return ResponseEntity.ok(ApiResponse.ok("로그인 성공", response));
    }

    /**
     * 내 정보 조회 (인증 필요)
     * GET /api/v1/auth/me
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<LoginResponse.UserInfo>> getMyInfo(
            @AuthenticationPrincipal UserPrincipal principal) {
        LoginResponse.UserInfo userInfo = authService.getMyInfo(principal.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(userInfo));
    }
}