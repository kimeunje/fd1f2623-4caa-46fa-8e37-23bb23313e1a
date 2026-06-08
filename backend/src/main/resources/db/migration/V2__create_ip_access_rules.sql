-- ════════════════════════════════════════════════════════════════════
-- V_v19_0__create_ip_access_rules.sql
--
-- v19.0 — 계정별 IP 접근 제어 (BE-1).
--
-- 한 계정에 enabled 규칙이 1건 이상이면 그 규칙들이 허용하는 IP/대역에서만
-- 로그인·요청 가능. enabled 규칙 0건이면 IP 제한 없음(기본). 전역(시스템 전체)
-- 차단은 본 phase 범위 외 — 계정별만.
--
-- prod 전용 (application-prod.yml flyway.enabled=true). dev/test 는 ddl-auto 가
-- IpAccessRule 엔티티로부터 자동 생성.
-- ════════════════════════════════════════════════════════════════════

CREATE TABLE `ip_access_rules` (
  `id`          bigint(20)   NOT NULL AUTO_INCREMENT,
  `user_id`     bigint(20)   NOT NULL,
  `cidr`        varchar(64)  NOT NULL
    COMMENT 'CIDR 표기 또는 단일 IP. 예: 203.0.113.0/24, 192.168.1.10, ::1/128',
  `description` varchar(200) DEFAULT NULL
    COMMENT '운영 메모 (예: 본사 사무실 대역)',
  `enabled`     bit(1)       NOT NULL DEFAULT b'1'
    COMMENT 'false 면 매칭 제외 (삭제 없이 일시 비활성)',
  `created_at`  datetime(6)  NOT NULL,
  `updated_at`  datetime(6)  NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ip_rules_user` (`user_id`),
  KEY `idx_ip_rules_user_enabled` (`user_id`,`enabled`),
  CONSTRAINT `fk_ip_rules_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='v19.0 - 계정별 IP 접근 제어 규칙. enabled 규칙 1건+ 이면 그 IP 만 허용.';