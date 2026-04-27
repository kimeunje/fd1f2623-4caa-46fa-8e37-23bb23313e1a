-- =====================================================================
-- V5: control_nodes 자기참조 트리 + frameworks.version 추가
-- Phase 5-14a (v14)
--
-- 명세 출처: SecuHub_Project_Specification_v14.md §3.3.1.3 / §6.1 / §6.4
--
-- 본 마이그레이션은 테이블/컬럼 생성만 한다.
-- 기존 controls 테이블의 데이터 이주는 V6 (Phase 5-14b) 에서 처리.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Optimistic lock — frameworks.version 추가
--    JPA @Version 으로 자동 관리. INSERT 시 0, UPDATE 시 +1 자동.
-- ---------------------------------------------------------------------
ALTER TABLE frameworks
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------
-- 2. control_nodes — 자기참조 트리 (v14 신규)
--
--    무제한 depth 트리 (서비스 레이어 가드 = 10).
--    category=branch, control=leaf.
--    같은 부모 안에 leaf 와 category 가 sibling 으로 공존 가능 (mixed-depth).
--    evidence_types 는 leaf 노드에만 매달림 (5-14f 에서 매핑 이주).
-- ---------------------------------------------------------------------
CREATE TABLE control_nodes (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    framework_id    BIGINT       NOT NULL,
    parent_id       BIGINT       NULL,
    node_type       VARCHAR(20)  NOT NULL,
    code            VARCHAR(50)  NOT NULL,
    name            VARCHAR(500) NOT NULL,
    description     TEXT         NULL,
    display_order   INT          NOT NULL DEFAULT 0,
    depth           INT          NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,

    CONSTRAINT fk_cn_framework
        FOREIGN KEY (framework_id) REFERENCES frameworks(id)    ON DELETE CASCADE,

    CONSTRAINT fk_cn_parent
        FOREIGN KEY (parent_id)    REFERENCES control_nodes(id) ON DELETE CASCADE,

    CONSTRAINT chk_cn_node_type
        CHECK (node_type IN ('category', 'control')),

    CONSTRAINT chk_cn_depth
        CHECK (depth BETWEEN 1 AND 10)
);

-- ---------------------------------------------------------------------
-- 3. 인덱스 — spec §3.3.1.3 명세 정합
--    code 는 sibling 중복 검증용 (서비스 레이어), DB unique 제약은 사용 안 함
--    (NULL parent 처리 복잡성 회피).
-- ---------------------------------------------------------------------
CREATE INDEX idx_cn_framework        ON control_nodes (framework_id);
CREATE INDEX idx_cn_parent           ON control_nodes (parent_id);
CREATE INDEX idx_cn_framework_depth  ON control_nodes (framework_id, depth);
CREATE INDEX idx_cn_code             ON control_nodes (framework_id, code);