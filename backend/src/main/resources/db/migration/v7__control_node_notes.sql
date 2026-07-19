-- v19.27 — 관리 항목 인수인계 노트 (누적 로그).
--
-- 배경: 관리자 이임 시 다음 관리자가 "이 항목의 증빙을 왜/어떻게 뽑았는지" 맥락을
--       인계받도록, 관리 항목당 여러 개의 노트를 시간순으로 누적한다. 편집할 때마다
--       덮어쓰는 게 아니라 새 노트가 쌓인다(1:N).
--
-- 설계:
--   - author_name : 작성 시 직접 입력한 작성자 이름. 관리자 계정이 공용일 수 있어
--                   로그인 계정과 무관하게 실제 작성자 이름을 수동 입력한다.
--   - body        : 노트 본문(마크다운).
--   - created_at / updated_at : BaseEntity 자동 관리(작성/수정 시각).
--   - ON DELETE CASCADE — 관리 항목 삭제 시 매달린 노트 일괄 삭제 (control_nodes 정합).
--
-- 권한: 노트 CRUD 는 관리자 전용(ControlNodeNoteController @PreAuthorize ADMIN).
--       심사원(reviewer)은 인수인계 노트를 볼 수 없다 — 내부 관리 맥락이므로.
--
-- 적용 범위: Flyway 는 prod(MySQL) 전용. dev/test(H2)는 ddl-auto 가 엔티티에서 자동 정합.
--
-- 참고: v19.26(control_nodes.description 감사 컬럼 2개)은 "덮어쓰기" 전제라 폐기됨.
--       description 컬럼 자체는 v19.22 보존 정책에 따라 DROP 하지 않고 미사용 상태로 둔다.

CREATE TABLE control_node_notes (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    control_node_id BIGINT       NOT NULL,
    author_name     VARCHAR(100) NOT NULL,
    body            TEXT         NOT NULL,
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cnn_control_node
        FOREIGN KEY (control_node_id) REFERENCES control_nodes (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_cnn_node ON control_node_notes (control_node_id);