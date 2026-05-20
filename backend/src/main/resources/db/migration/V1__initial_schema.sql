-- ════════════════════════════════════════════════════════════════════
-- V1__initial_schema.sql
--
-- 통합 baseline (v18.8.2 시점).
-- 기존 V1 + V2 (job_execution_diagnosis) + V3 (scripts UID) + V4 (evidence_assets v18.6a 보정)
-- 모두 합침. drop database 후 본 V1 한 번만 실행되어 전체 스키마 구성.
--
-- 옛 V2/V3/V4 파일은 삭제. flyway_schema_history 도 처음부터 V1 만 기록.
-- ════════════════════════════════════════════════════════════════════

SET FOREIGN_KEY_CHECKS = 0;

-- ───────────────────────────────────────────────────────────────────
-- 1. users (다른 테이블 FK 의 root)
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE `users` (
  `permission_evidence` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `last_login_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `name` varchar(100) NOT NULL,
  `team` varchar(100) DEFAULT NULL,
  `email` varchar(255) NOT NULL,
  `hashed_password` varchar(255) NOT NULL,
  `role` enum('admin','approver','developer') NOT NULL,
  `status` enum('active','inactive') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`),
  KEY `idx_users_email` (`email`),
  KEY `idx_users_role` (`role`),
  KEY `idx_users_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ───────────────────────────────────────────────────────────────────
-- 2. frameworks
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE `frameworks` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `parent_framework_id` bigint(20) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `version` bigint(20) NOT NULL,
  `name` varchar(200) NOT NULL,
  `description` text DEFAULT NULL,
  `status` enum('active','archived') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_frameworks_parent` (`parent_framework_id`),
  KEY `idx_frameworks_status` (`status`),
  CONSTRAINT `FKot6vkap67a7s9kmmtrhhn7nqu`
    FOREIGN KEY (`parent_framework_id`) REFERENCES `frameworks` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ───────────────────────────────────────────────────────────────────
-- 3. control_nodes
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE `control_nodes` (
  `depth` int(11) NOT NULL,
  `display_order` int(11) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `framework_id` bigint(20) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `parent_id` bigint(20) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `code` varchar(50) NOT NULL,
  `name` varchar(500) NOT NULL,
  `description` text DEFAULT NULL,
  `node_type` enum('category','control') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_cn_framework` (`framework_id`),
  KEY `idx_cn_parent` (`parent_id`),
  KEY `idx_cn_framework_depth` (`framework_id`,`depth`),
  KEY `idx_cn_code` (`framework_id`,`code`),
  CONSTRAINT `FKpv6qcc78mw8wgo2sle6tc9msa`
    FOREIGN KEY (`framework_id`) REFERENCES `frameworks` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FKq1x1urs57po6yijcpgne38yib`
    FOREIGN KEY (`parent_id`) REFERENCES `control_nodes` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_cn_node_type` CHECK (`node_type` in ('category','control')),
  CONSTRAINT `chk_cn_depth` CHECK (`depth` between 1 and 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ───────────────────────────────────────────────────────────────────
-- 4. evidence_types
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE `evidence_types` (
  `due_date` date DEFAULT NULL,
  `control_id` bigint(20) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `owner_user_id` bigint(20) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `name` varchar(300) NOT NULL,
  `description` text DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_evidence_types_owner` (`owner_user_id`),
  KEY `idx_evidence_types_due` (`due_date`),
  KEY `FKn504iexlvxm3rnqu2j0fbkpej` (`control_id`),
  CONSTRAINT `FKlrfvo0mynmy8if8x01rikffmm`
    FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKn504iexlvxm3rnqu2j0fbkpej`
    FOREIGN KEY (`control_id`) REFERENCES `control_nodes` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ───────────────────────────────────────────────────────────────────
-- 5. evidence_assets (v18.6a 의 신규 — 통합 V1 에 직접 포함)
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE `evidence_assets` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `sha256` varchar(64) NOT NULL
    COMMENT 'v18.6a - SHA-256 소문자 hex 64자. Q9 정합으로 UNIQUE 가 아닌 일반 인덱스.',
  `file_path` varchar(1000) NOT NULL
    COMMENT 'v18.6a - 물리 파일 절대 경로. {storage}/assets/{id % 1000}/{id} 패턴.',
  `file_size` bigint(20) NOT NULL DEFAULT 0,
  `original_file_name` varchar(500) DEFAULT NULL
    COMMENT 'v18.6a - 첫 업로드 시점 파일명. FULLTEXT MATCH AGAINST 대상.',
  `uploaded_by` bigint(20) DEFAULT NULL
    COMMENT 'v18.6a - 첫 업로드 사용자. user 삭제 시 SET NULL (asset 보존).',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_assets_sha256` (`sha256`),
  KEY `idx_assets_uploaded_by` (`uploaded_by`),
  FULLTEXT KEY `ft_assets_filename` (`original_file_name`),
  CONSTRAINT `fk_evidence_assets_uploaded_by`
    FOREIGN KEY (`uploaded_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='v18.6a - 증빙 자산 (물리 파일 1:1). EvidenceFile 의 link 가 본 entity 를 참조.';

-- ───────────────────────────────────────────────────────────────────
-- 6. scripts (v18.8.2 의 신규 — 통합 V1 에 직접 포함)
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE `scripts` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `file_path` varchar(255) NOT NULL
    COMMENT 'v18.8.2 - 상대 경로 (filename only, app.scripts.base-dir 기준 resolve). 예: "12.py"',
  `content_size` bigint(20) NOT NULL DEFAULT 0
    COMMENT 'v18.8.2 - 파일 크기 (bytes, UTF-8 기준)',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='v18.8.2 - Python 스크립트 entity (UID 기반)';

-- ───────────────────────────────────────────────────────────────────
-- 7. collection_jobs (v18.8.2 - script_id 컬럼 통합)
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE `collection_jobs` (
  `is_active` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `evidence_type_id` bigint(20) DEFAULT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `script_id` bigint(20) DEFAULT NULL
    COMMENT 'v18.8.2 - Script entity FK. NULL 이면 legacy script_path 활용.',
  `updated_at` datetime(6) NOT NULL,
  `schedule_cron` varchar(100) DEFAULT NULL,
  `name` varchar(300) NOT NULL,
  `script_path` varchar(1000) DEFAULT NULL
    COMMENT 'legacy - v18.8.2 의 script_id 가 NULL 일 때 fallback.',
  `description` text DEFAULT NULL,
  `job_type` enum('excel_extract','log_extract','web_scraping') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK30ebo0xryo4qm4o059ea7cjls` (`evidence_type_id`),
  KEY `idx_collection_jobs_script_id` (`script_id`),
  CONSTRAINT `FK30ebo0xryo4qm4o059ea7cjls`
    FOREIGN KEY (`evidence_type_id`) REFERENCES `evidence_types` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_collection_jobs_script_id`
    FOREIGN KEY (`script_id`) REFERENCES `scripts` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ───────────────────────────────────────────────────────────────────
-- 8. job_executions (v18.7 - error_diagnosis JSON 컬럼 통합)
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE `job_executions` (
  `created_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `job_id` bigint(20) NOT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `error_message` text DEFAULT NULL,
  `error_diagnosis` json DEFAULT NULL
    COMMENT 'v18.7 - selenium wrapper 산출 _diagnosis.json 의 전체 내용. status=failed 시점에 채워짐.',
  `status` enum('failed','running','success') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKobtj5gs3w6ltwxeeh2i4oejdu` (`job_id`),
  CONSTRAINT `FKobtj5gs3w6ltwxeeh2i4oejdu`
    FOREIGN KEY (`job_id`) REFERENCES `collection_jobs` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ───────────────────────────────────────────────────────────────────
-- 9. evidence_files (v18.6a - asset_id 컬럼 통합)
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE `evidence_files` (
  `version` int(11) NOT NULL,
  `collected_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `evidence_type_id` bigint(20) NOT NULL,
  `execution_id` bigint(20) DEFAULT NULL,
  `file_size` bigint(20) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `asset_id` bigint(20) DEFAULT NULL
    COMMENT 'v18.6a - EvidenceAsset entity FK (transitional NULLABLE). 옛 file 은 NULL, 신규는 채워짐.',
  `reviewed_at` datetime(6) DEFAULT NULL,
  `reviewed_by` bigint(20) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `uploaded_by` bigint(20) DEFAULT NULL,
  `file_name` varchar(500) NOT NULL,
  `file_path` varchar(1000) NOT NULL,
  `review_note` text DEFAULT NULL,
  `submit_note` text DEFAULT NULL,
  `collection_method` enum('auto','manual') NOT NULL,
  `review_status` enum('approved','auto_approved','pending','rejected') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_evidence_files_review_status` (`review_status`),
  KEY `idx_evidence_files_reviewed_by` (`reviewed_by`),
  KEY `idx_evidence_files_uploaded_by` (`uploaded_by`),
  KEY `idx_evidence_files_asset_id` (`asset_id`),
  KEY `FKhaam96ik6ymhisj84o1svtccf` (`evidence_type_id`),
  KEY `FKfvmw7llaxsdv44k4rqqcqxpcn` (`execution_id`),
  CONSTRAINT `FK2jldegxbsgu3w2cal6g54ybl7`
    FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FKfvmw7llaxsdv44k4rqqcqxpcn`
    FOREIGN KEY (`execution_id`) REFERENCES `job_executions` (`id`),
  CONSTRAINT `FKhaam96ik6ymhisj84o1svtccf`
    FOREIGN KEY (`evidence_type_id`) REFERENCES `evidence_types` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FKlj2vurtfd17hg6u61d2gpef65`
    FOREIGN KEY (`uploaded_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_evidence_files_asset_id`
    FOREIGN KEY (`asset_id`) REFERENCES `evidence_assets` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ───────────────────────────────────────────────────────────────────
-- 10. notification_preferences
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE `notification_preferences` (
  `email_daily_digest` bit(1) NOT NULL,
  `email_on_approval` bit(1) NOT NULL,
  `email_on_due_reminder` bit(1) NOT NULL,
  `email_on_new_assignment` bit(1) NOT NULL,
  `email_on_rejection` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  PRIMARY KEY (`user_id`),
  CONSTRAINT `FKt9qjvmcl36i14utm5uptyqg84`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;