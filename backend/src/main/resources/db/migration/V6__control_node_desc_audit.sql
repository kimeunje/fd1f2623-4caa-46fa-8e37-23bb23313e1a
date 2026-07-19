-- v19.26 — 관리 항목 설명(인수인계 노트)의 작성자/수정일 감사 컬럼.
--
-- 배경: 기존 관리자 이임 시 다음 관리자가 "이 증빙을 왜/어떻게 뽑았는지" 맥락을
--       인계받을 수 있도록 control_nodes.description 에 인수인계 노트를 남긴다.
--       인수인계 특성상 "누가 언제 남겼는지"가 핵심이므로 두 컬럼을 추가한다.
--
-- 설계:
--   - description_updated_by_name : 작성 시점의 이름을 문자열로 박제 (FK 아님).
--       퇴사/개명과 무관하게 "당시 누가 남겼는지"의 스냅샷을 보존 (Q=이름 박제).
--   - description_updated_at      : 최종 수정 시각.
--   - 둘 다 NULL 허용 — 기존 행 + 설명이 없는 항목은 NULL.
--   - description 텍스트가 실제로 바뀔 때만 서비스가 갱신 (코드/이름만 수정 시 불변).
--
-- 적용 범위: Flyway 는 prod(MySQL) 전용. dev/test(H2)는 ddl-auto 가 엔티티에서 자동 정합.

ALTER TABLE control_nodes
    ADD COLUMN description_updated_by_name VARCHAR(100) NULL,
    ADD COLUMN description_updated_at      DATETIME     NULL;