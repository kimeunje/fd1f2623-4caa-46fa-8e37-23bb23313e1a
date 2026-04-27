package com.secuhub.domain.evidence.entity;

/**
 * v14 Phase 5-14 — control_nodes 의 노드 유형.
 *
 * <ul>
 *   <li>{@code category} — branch 노드. 자식 노드(category 또는 control)를 가질 수 있고,
 *       evidence_types 를 직접 매달 수 없다.</li>
 *   <li>{@code control} — leaf 노드. 자식 노드를 가질 수 없고,
 *       evidence_types 의 부모가 된다 (5-14f 에서 EvidenceType.controlNode 매핑 변경 시 활성).</li>
 * </ul>
 *
 * <p>두 타입의 변환 규칙은 spec v14 §3.3.1.10 결정 10:</p>
 * <ul>
 *   <li>leaf → category: evidence_types 가 0 건일 때만 허용 (5-14d 에서 검증 추가)</li>
 *   <li>category → leaf: 자식 노드가 0 건일 때만 허용 (5-14d 에서 검증 추가)</li>
 * </ul>
 *
 * <p>Enum 명은 DB 컬럼값과 정확히 일치 (소문자). DB CHECK 제약: {@code node_type IN ('category', 'control')}.
 * Hibernate {@code @Enumerated(EnumType.STRING)} 으로 매핑.</p>
 */
public enum NodeType {
    category,
    control
}