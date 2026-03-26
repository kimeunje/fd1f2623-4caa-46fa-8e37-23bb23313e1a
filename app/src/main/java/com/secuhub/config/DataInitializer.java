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

        userRepository.save(User.builder()
                .email("admin@company.com")
                .name("관리자")
                .hashedPassword(passwordEncoder.encode("admin1234"))
                .team("보안팀")
                .role(UserRole.admin)
                .permissionEvidence(true)
                .permissionVuln(true)
                .build());

        userRepository.save(User.builder()
                .email("park_tl@company.com")
                .name("박팀장")
                .hashedPassword(passwordEncoder.encode("park1234"))
                .team("백엔드팀")
                .role(UserRole.approver)
                .permissionEvidence(false)
                .permissionVuln(true)
                .build());

        userRepository.save(User.builder()
                .email("kim@company.com")
                .name("김개발")
                .hashedPassword(passwordEncoder.encode("dev1234"))
                .team("백엔드팀")
                .role(UserRole.developer)
                .permissionEvidence(false)
                .permissionVuln(true)
                .build());

        userRepository.save(User.builder()
                .email("lee@company.com")
                .name("이보안")
                .hashedPassword(passwordEncoder.encode("dev1234"))
                .team("프론트엔드팀")
                .role(UserRole.developer)
                .permissionEvidence(false)
                .permissionVuln(true)
                .build());

        userRepository.save(User.builder()
                .email("choi@company.com")
                .name("박백엔드")
                .hashedPassword(passwordEncoder.encode("dev1234"))
                .team("백엔드팀")
                .role(UserRole.developer)
                .permissionEvidence(false)
                .permissionVuln(true)
                .build());

        log.info("데모 계정 초기화 완료 (5명)");
    }
}
