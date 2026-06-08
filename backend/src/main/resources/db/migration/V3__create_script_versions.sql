-- ════════════════════════════════════════════════════════════════════
-- V_v19_4__create_script_versions.sql
--
-- v19.4 — 스크립트 버전 이력 (carry-over ⑤).
--
-- 스크립트 저장(create/update)마다 본문 스냅샷을 한 줄씩 적재. 롤백은 옛 버전 내용을
-- 복사한 "새 버전"으로 전진 기록(이력 불변). 현재 실행본은 기존대로 {uuid}.py 파일.
--
-- prod 전용. dev/test 는 ddl-auto 가 ScriptVersion 엔티티로부터 자동 생성.
-- content 는 @Lob String → MariaDB longtext 정합 (스크립트 1MB 상한이라 충분).
-- ════════════════════════════════════════════════════════════════════

CREATE TABLE `script_versions` (
  `id`           bigint(20)   NOT NULL AUTO_INCREMENT,
  `script_id`    bigint(20)   NOT NULL,
  `version_no`   int(11)      NOT NULL,
  `content`      longtext     NOT NULL,
  `content_size` bigint(20)   NOT NULL DEFAULT 0,
  `note`         varchar(200) DEFAULT NULL,
  `created_by`   bigint(20)   DEFAULT NULL,
  `created_at`   datetime(6)  NOT NULL,
  `updated_at`   datetime(6)  NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_script_versions_script_no` (`script_id`,`version_no`),
  KEY `idx_script_versions_script` (`script_id`),
  CONSTRAINT `fk_script_versions_script`
    FOREIGN KEY (`script_id`) REFERENCES `scripts` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_script_versions_created_by`
    FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='v19.4 - 스크립트 버전 이력 (편집 스냅샷 + 롤백)';