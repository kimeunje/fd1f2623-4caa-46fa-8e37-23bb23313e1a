-- ════════════════════════════════════════════════════════════════════
-- V_v18_7__job_execution_diagnosis.sql
--
-- v18.7 — 자동 수집 실패 진단 (Chat 6 결정 방향, L_USER_NEEDS_REDIRECT).
--
-- selenium wrapper template (`/scripts/templates/selenium_wrapper.py`) 가 산출하는
-- _diagnosis.json 의 전체 내용을 JobExecution 의 컬럼으로 보관.
--
-- 결정 사항:
--   Q16 = A — JSON column (MariaDB native, JSON indexing 가능)
--   Q17 = A — 스크린샷/page_source 는 outputPath 안의 표준 파일명, 별도 컬럼 추가 없음
--   Q18 = A — ScriptExecutionService.collectOutputFiles() 안에서 흡수
--
-- spec 정합:
--   - §5.1 job_executions brief 의 끝 `...` 에 흡수 (1 컬럼만 추가)
--   - §3.11 (v18.6a 신규) 의 책임 분리 정합 — JobExecution 책임 = 시점 + 성공/실패 + 진단
--   - L_RESPONSIBILITY_SEPARATION — EvidenceAsset / EvidenceFile 시스템 미관여
--
-- 운영 영향:
--   - 기존 row 의 error_diagnosis = NULL (graceful, v18.7 진입 전 실행은 진단 없음)
--   - v18.7 진입 후 신규 실행만 채워짐 (실패 시점에 wrapper 가 _diagnosis.json 산출)
--   - 성공 실행도 단계별 시간 추적용으로 채워질 수 있음 (wrapper 정책)
--   - 본 ALTER 는 RESTRICT 없음, prod 적용 후 ddl-auto 환경 (dev/test) 자연 정합
--
-- Roll-forward 안전: 본 컬럼 제거 시 ScriptExecutionService 의 진단 분기도 함께 제거 필요
-- (~15 라인). entity 의 @Column 제거. FE 의 진단 패널 컴포넌트 비활성화 또는 제거.
-- ════════════════════════════════════════════════════════════════════

ALTER TABLE job_executions
    ADD COLUMN error_diagnosis JSON NULL
        COMMENT 'v18.7 - selenium wrapper 산출 _diagnosis.json 의 전체 내용. status=failed 시점에 채워짐.';