package com.secuhub.config.jwt;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * JWT 인증 후 SecurityContext에 저장되는 사용자 정보
 * Controller에서 @AuthenticationPrincipal로 주입받아 사용
 */
@Getter
@AllArgsConstructor
public class UserPrincipal {

    private final Long userId;
    private final String email;
    private final String role;
}
