package com.secuhub.config;

import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.entity.UserRole;
import com.secuhub.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("데모 데이터가 이미 존재합니다. 초기화를 건너뜁니다.");
            return;
        }

        log.info("데모 계정 초기화 시작...");

        // 발표용 단일 계정 — admin 만 생성.
        // 담당자(approver/developer) 계정은 "내 할 일" / 승인·반려 워크플로우가
        // 아직 미구현이라 시드에서 제외 (구현 후 복원).
        userRepository.save(User.builder()
                .email("admin@company.com")
                .name("관리자")
                .hashedPassword(passwordEncoder.encode("admin1234"))
                .team("보안팀")
                .role(UserRole.admin)
                .permissionEvidence(true)
                .build());

        log.info("데모 계정 초기화 완료 (admin 1명)");
    }
}