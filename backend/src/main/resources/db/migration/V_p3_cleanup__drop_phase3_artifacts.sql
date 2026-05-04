-- ============================================================================
-- V_p3_cleanup__drop_phase3_artifacts.sql
-- ----------------------------------------------------------------------------
-- Phase 3 (취약점 관리) 프로젝트 제거 (2026-05-04). 다음 일괄 정리:
--
-- 1. users.permission_vuln 컬럼 DROP (User entity 에서 필드 제거됨)
-- 2. vulnerabilities / vuln_action_logs / approval_requests 3 테이블 DROP
--    (entity / repository 일괄 삭제됨)
--
-- 운영 안전성:
-- - IF EXISTS 가드로 prod 환경에서 미생성 / 이미 제거된 경우도 안전
-- - dev/test (H2 ddl-auto:create) 환경 = 본 migration 실행 안 됨 (entity 만으로
--   자동 정합)
-- - 운영 데이터 손실 가능성: vulnerabilities / vuln_action_logs /
--   approval_requests 의 행 일괄 삭제. Phase 3 미진행이라 prod 행 0 가정,
--   확인 후 실행 권장
--
-- 실행 전 권장 prod 확인:
--   SELECT COUNT(*) FROM vulnerabilities;
--   SELECT COUNT(*) FROM vuln_action_logs;
--   SELECT COUNT(*) FROM approval_requests;
--   SELECT COUNT(*) FROM users WHERE permission_vuln = true;
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. permission_vuln 컬럼 DROP (users 테이블)
-- ----------------------------------------------------------------------------
ALTER TABLE users DROP COLUMN IF EXISTS permission_vuln;

-- ----------------------------------------------------------------------------
-- 2. Phase 3 도메인 3 테이블 DROP
-- ----------------------------------------------------------------------------
-- FK 의존성 순서: approval_requests → vulnerabilities (vulnerability_id),
--                vuln_action_logs → vulnerabilities (vulnerability_id),
--                vulnerabilities → users (assignee_id, approver_id)
-- 따라서 자식 부터 → 부모 순으로 DROP
DROP TABLE IF EXISTS approval_requests;
DROP TABLE IF EXISTS vuln_action_logs;
DROP TABLE IF EXISTS vulnerabilities;