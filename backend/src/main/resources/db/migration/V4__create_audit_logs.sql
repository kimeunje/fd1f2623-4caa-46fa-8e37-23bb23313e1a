-- ============================================================================
-- V_v19_12__create_audit_logs.sql
-- 감사 로그 (AUDIT-1) — "누가/언제/무엇을/어디서(IP)/결과" 영속 기록.
--
-- ⚠ prod 전용 (Flyway). dev/test 는 ddl-auto 로 entity 매핑에서 자동 생성.
-- ⚠ L_SCHEMA_DRIFT_DEV_PROD: 배포 전 dev ddl-auto 가 운영 DB 를 가리키지 않는지 확인.
--   (dev 가 운영 DB 에 선생성 시 이 CREATE 가 1050 already exists 로 충돌 + 실패 history 잔존)
-- ⚠ L_LOB_STRING_DIALECT: detail 은 @Lob 금지, 아래처럼 LONGTEXT 로 명시 (entity 도 columnDefinition 일치).
-- ============================================================================

CREATE TABLE audit_logs (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    actor_user_id BIGINT       NULL,                  -- 행위자 (users.id). 시스템/익명 시 NULL
    actor_email   VARCHAR(255) NULL,                  -- 기록 시점 이메일 스냅샷 (계정 삭제돼도 보존)
    action        VARCHAR(64)  NOT NULL,              -- AuditAction enum (EnumType.STRING)
    target_type   VARCHAR(64)  NULL,                  -- 대상 도메인 (Script, User, EvidenceFile, Framework ...)
    target_id     VARCHAR(128) NULL,                  -- 대상 식별자 (Long id 또는 UUID 문자열 — 둘 다 수용)
    detail        LONGTEXT     NULL,                  -- 부가 정보(JSON 권장). LONGTEXT 명시 (tinytext 매핑 함정 회피)
    client_ip     VARCHAR(45)  NULL,                  -- IPv6 최대 45자
    result        VARCHAR(16)  NOT NULL,              -- AuditResult: SUCCESS / FAILURE / BLOCKED
    created_at    DATETIME(6)  NOT NULL,              -- 기록 시각 (Hibernate LocalDateTime → datetime(6))
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id)
        REFERENCES users (id) ON DELETE SET NULL      -- 계정 삭제 시 로그는 보존, actor 만 NULL
);

-- 조회 패턴 대응 인덱스 (AUDIT-2 admin 조회 API: actor/action/기간/result 필터)
CREATE INDEX idx_audit_actor   ON audit_logs (actor_user_id);
CREATE INDEX idx_audit_action  ON audit_logs (action);
CREATE INDEX idx_audit_created ON audit_logs (created_at);
CREATE INDEX idx_audit_target  ON audit_logs (target_type, target_id);