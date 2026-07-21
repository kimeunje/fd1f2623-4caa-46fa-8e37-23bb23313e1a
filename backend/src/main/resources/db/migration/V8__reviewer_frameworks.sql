-- v19.25 (심사원 프레임워크 배정) — reviewer_frameworks 조인 테이블.
--
-- 심사원(reviewer) 계정마다 열람 가능한 프레임워크를 명시 배정한다(계정 설정에서 지정).
-- 배정 규칙이 없는 심사원은 아무것도 보지 못한다(fail-closed — ip_access_rules 의 fail-open 과 반대).
--
-- ⚠ Flyway 버전 주의: 본 기능은 v19.25 이지만, 이미 적용된 최신 마이그레이션이 V_v19_27 이므로
--    forward-only 정합을 위해 파일명 버전을 v19_28 로 둔다(Flyway 는 기본적으로 out-of-order 를 거부).
--    feature 명(v19.25)과 무관하게 "적용 순서"만 단조 증가하면 된다.
--    dev/test(H2 ddl-auto)는 본 파일 없이 @Entity(ReviewerFramework)로 자동 생성되므로 prod 전용이다.

CREATE TABLE reviewer_frameworks (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    framework_id BIGINT      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_reviewer_framework UNIQUE (user_id, framework_id),
    CONSTRAINT fk_rf_user      FOREIGN KEY (user_id)      REFERENCES users (id)      ON DELETE CASCADE,
    CONSTRAINT fk_rf_framework FOREIGN KEY (framework_id) REFERENCES frameworks (id) ON DELETE CASCADE
);

CREATE INDEX idx_rf_user ON reviewer_frameworks (user_id);