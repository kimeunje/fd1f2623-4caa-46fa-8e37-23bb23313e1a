-- ============================================================================
-- V_v19_13__add_audit_target_name.sql
-- 감사 로그 (B) — 대상의 "사람이 읽는 이름"(target_name) 기록 컬럼.
--
-- 쓰기 시점 스냅샷 → 대상이 삭제/개명된 뒤에도 "무엇을"이 보존된다
-- (읽기 시점 type:id → name resolve 로는 삭제된 대상의 이름을 살릴 수 없음).
--
-- ⚠ prod 전용 (Flyway). dev/test 는 ddl-auto 로 entity 매핑에서 자동 생성.
-- ⚠ 버전 번호: v19_12(audit_logs 생성) 이후 첫 audit 마이그레이션. 레포에 이미
--   V_v19_13 이 있으면 다음 빈 번호로 조정.
-- ============================================================================

ALTER TABLE audit_logs
    ADD COLUMN target_name VARCHAR(255) NULL AFTER target_id;   -- 대상 표시명(파일명/사용자명 등) 스냅샷