-- =====================================================================
-- V1: SecuHub 운영 baseline schema
-- Phase v18.5-platform-c (2026-05-XX)
--
-- 본 마이그레이션은 운영 환경 첫 배포 시점의 schema 전체 baseline.
-- dev 환경의 Hibernate ddl-auto: create 결과를 mysqldump --no-data 로
-- 추출 후 정리. 이전 phase 들 (v14 control_nodes 도입 / Phase 3 cleanup /
-- v18.3 ON DELETE CASCADE) 의 모든 변경이 본 baseline 에 포함됨.
--
-- 마이그레이션 이력 정책 (방향 B - 단일 baseline):
--   - 운영 진입 시점 = V1 (본 파일)
--   - 미래 schema 변경 = V2, V3, ... 로 추가
--   - 옛 V5__add_control_nodes.sql / V_p3_cleanup__*.sql 은 git history 보존
--
-- charset 정책: utf8mb4 + utf8mb4_unicode_ci (한글 정합)
-- engine: InnoDB (FK + transaction 지원)
-- FK 이름: Hibernate 자동 생성 hash 보존 (별도 cleanup phase 후보)
-- =====================================================================
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE `collection_jobs` (
  `is_active` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `evidence_type_id` bigint(20) DEFAULT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `schedule_cron` varchar(100) DEFAULT NULL,
  `name` varchar(300) NOT NULL,
  `script_path` varchar(1000) DEFAULT NULL,
  `description` text DEFAULT NULL,
  `job_type` enum('excel_extract','log_extract','web_scraping') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK30ebo0xryo4qm4o059ea7cjls` (`evidence_type_id`),
  CONSTRAINT `FK30ebo0xryo4qm4o059ea7cjls` FOREIGN KEY (`evidence_type_id`) REFERENCES `evidence_types` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
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
  CONSTRAINT `FKpv6qcc78mw8wgo2sle6tc9msa` FOREIGN KEY (`framework_id`) REFERENCES `frameworks` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FKq1x1urs57po6yijcpgne38yib` FOREIGN KEY (`parent_id`) REFERENCES `control_nodes` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_cn_node_type` CHECK (`node_type` in ('category','control')),
  CONSTRAINT `chk_cn_depth` CHECK (`depth` between 1 and 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `evidence_files` (
  `version` int(11) NOT NULL,
  `collected_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `evidence_type_id` bigint(20) NOT NULL,
  `execution_id` bigint(20) DEFAULT NULL,
  `file_size` bigint(20) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
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
  KEY `FKhaam96ik6ymhisj84o1svtccf` (`evidence_type_id`),
  KEY `FKfvmw7llaxsdv44k4rqqcqxpcn` (`execution_id`),
  CONSTRAINT `FK2jldegxbsgu3w2cal6g54ybl7` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FKfvmw7llaxsdv44k4rqqcqxpcn` FOREIGN KEY (`execution_id`) REFERENCES `job_executions` (`id`),
  CONSTRAINT `FKhaam96ik6ymhisj84o1svtccf` FOREIGN KEY (`evidence_type_id`) REFERENCES `evidence_types` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FKlj2vurtfd17hg6u61d2gpef65` FOREIGN KEY (`uploaded_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
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
  CONSTRAINT `FKlrfvo0mynmy8if8x01rikffmm` FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKn504iexlvxm3rnqu2j0fbkpej` FOREIGN KEY (`control_id`) REFERENCES `control_nodes` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
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
  CONSTRAINT `FKot6vkap67a7s9kmmtrhhn7nqu` FOREIGN KEY (`parent_framework_id`) REFERENCES `frameworks` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `job_executions` (
  `created_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `job_id` bigint(20) NOT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `error_message` text DEFAULT NULL,
  `status` enum('failed','running','success') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKobtj5gs3w6ltwxeeh2i4oejdu` (`job_id`),
  CONSTRAINT `FKobtj5gs3w6ltwxeeh2i4oejdu` FOREIGN KEY (`job_id`) REFERENCES `collection_jobs` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
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
  CONSTRAINT `FKt9qjvmcl36i14utm5uptyqg84` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
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

SET FOREIGN_KEY_CHECKS = 1;
