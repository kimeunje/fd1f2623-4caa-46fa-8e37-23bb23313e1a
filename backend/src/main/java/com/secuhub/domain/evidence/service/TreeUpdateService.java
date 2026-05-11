package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.OptimisticLockMismatchException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.common.exception.TreeValidationException;
import com.secuhub.domain.evidence.dto.TreeUpdateDto;
import com.secuhub.domain.evidence.dto.ValidationDetail;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.NodeType;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Phase 5-14d — PATCH /api/v1/frameworks/{id}/tree 핵심 서비스.
 *
 * <p>spec §3.3.1.4 의 12개 검증 규칙을 단일 트랜잭션 안에서 실행하고,
 * 모든 검증 통과 시 INSERT/UPDATE/MOVE/DELETE 를 적용한다.</p>
 *
 * <h3>v15 Phase 5-15a — Hybrid 모델 채택 (검증 규칙 12 → 10)</h3>
 * <p>spec §3.3.1.9 의 hybrid 모델 채택에 따라 다음 2 검증 제거:</p>
 * <ul>
 *   <li><b>parent_must_be_category</b> — leaf 아래에 자식 노드 추가/이동 가능
 *       (validateCreated number 분기 + tempId 분기 + validateMoved number 분기 +
 *       tempId 분기, 총 4 occurrence 제거)</li>
 *   <li><b>leaf_with_evidence</b> — entity 가드 ({@link ControlNode#addEvidenceType})
 *       에서만 enforce 되고 있어 5-15a 의 ControlNode 가드 제거와 동시에 자연 무력화.
 *       본 service 측 별도 변경 없음</li>
 * </ul>
 *
 * <p>나머지 10 검증은 그대로 유지 (blank_name, blank_code, invalid_node_type,
 * parent_not_found, depth_mismatch, max_depth_exceeded, unresolved_temp_id,
 * invalid_parent_type, node_type_change_not_allowed, cycle_detected,
 * sibling_code_duplicate, node_not_found).</p>
 *
 * <h3>알고리즘 흐름</h3>
 * <ol>
 *   <li>Framework lookup (404 fallback)</li>
 *   <li>expectedVersion 일치 확인 → 불일치 시 즉시 409
 *       ({@link OptimisticLockMismatchException})</li>
 *   <li>모든 검증 (3~10) 실행, details 누적</li>
 *   <li>tempId 의존성 그래프 분석 (Kahn's 위상 정렬, 순환 검출)</li>
 *   <li>details 비어있지 않으면 422 ({@link TreeValidationException})</li>
 *   <li>위상 정렬 순서로 created INSERT (tempId → newId 매핑 누적)</li>
 *   <li>updated 적용 (code/name/description 부분 갱신)</li>
 *   <li>moved 적용 (parent/displayOrder/depth 갱신)</li>
 *   <li>deleted 적용 (DB ON DELETE CASCADE 가 자손 + evidence_types 정리)</li>
 *   <li>Framework.@Version 강제 증가 (Framework.touchVersion + flush)</li>
 *   <li>Response { version, mappings }</li>
 * </ol>
 *
 * <h3>5-14d 정책 결정</h3>
 * <ul>
 *   <li>updated 의 nodeType 변경: 거부 (v2 또는 delete+create 로 우회).
 *       v15 5-15a 시점에도 보존 (transitional, 5-15b 일괄 정리)</li>
 *   <li>moved 의 newParentId 가 leaf 노드: <s>거부</s> →
 *       <b>v15 5-15a 에서 허용</b> (hybrid)</li>
 *   <li>evidence 매달림 카운트 검증: 5-14f (매핑 이주 후) 본격 구현</li>
 *   <li>lastEditedBy / lastEditedAt: 5-14d 범위 외 (Q1=B), 후속 phase</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreeUpdateService {

    /** spec §3.3.1.3 의 max depth 가드. CHECK 제약 (depth BETWEEN 1 AND 10) 과 정합. */
    private static final int MAX_DEPTH = 10;

    private final FrameworkRepository frameworkRepository;
    private final ControlNodeRepository controlNodeRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public TreeUpdateDto.Response updateTree(Long frameworkId, TreeUpdateDto.Request req) {
        // -----------------------------------------------------------
        // 1. Framework lookup
        // -----------------------------------------------------------
        Framework fw = frameworkRepository.findById(frameworkId)
                .orElseThrow(() -> new ResourceNotFoundException("프레임워크", frameworkId));

        // -----------------------------------------------------------
        // 2. 검증 규칙 1 — expectedVersion 일치
        // -----------------------------------------------------------
        if (req.getExpectedVersion() == null
                || !req.getExpectedVersion().equals(fw.getVersion())) {
            throw new OptimisticLockMismatchException(
                    fw.getVersion() == null ? 0L : fw.getVersion());
        }

        // -----------------------------------------------------------
        // 3. 변경 항목 정규화 (null 처리)
        // -----------------------------------------------------------
        List<TreeUpdateDto.CreatedNode> created = nullToEmpty(getCreated(req));
        List<TreeUpdateDto.UpdatedNode> updated = nullToEmpty(getUpdated(req));
        List<TreeUpdateDto.MovedNode>   moved   = nullToEmpty(getMoved(req));
        List<TreeUpdateDto.DeletedNode> deleted = nullToEmpty(getDeleted(req));

        // -----------------------------------------------------------
        // 4. 기존 노드 모두 로드 (검증 + 매핑용)
        // -----------------------------------------------------------
        // 5-14c 의 LEFT JOIN FETCH 메서드 재사용 — parent 까지 한 번에 hydrate
        List<ControlNode> existingList =
                controlNodeRepository.findByFrameworkIdOrderByDepthAscDisplayOrderAsc(frameworkId);
        Map<Long, ControlNode> existingNodes = new HashMap<>();
        for (ControlNode cn : existingList) existingNodes.put(cn.getId(), cn);

        // -----------------------------------------------------------
        // 5. 검증 — details 누적
        // -----------------------------------------------------------
        List<ValidationDetail> details = new ArrayList<>();

        // tempId 색인 (검증 + 위상 정렬 + parentId 다형 해석에 필요)
        Map<String, TreeUpdateDto.CreatedNode> byTempId = new HashMap<>();
        for (TreeUpdateDto.CreatedNode c : created) {
            if (c.getTempId() != null) byTempId.put(c.getTempId(), c);
        }

        validateCreated(created, existingNodes, byTempId, details);
        validateUpdated(updated, existingNodes, details);
        validateMoved(moved, existingNodes, byTempId, details);
        validateDeleted(deleted, existingNodes, details);
        validateSiblingCodes(created, updated, moved, deleted, existingNodes, details);

        // 위상 정렬 — created 내 tempId 의존성 (순환 검출 + INSERT 순서 결정)
        List<TreeUpdateDto.CreatedNode> sortedCreated =
                topologicalSort(created, byTempId, details);

        if (!details.isEmpty()) {
            throw new TreeValidationException(details);
        }

        // -----------------------------------------------------------
        // 6. INSERT (위상 정렬 순서)
        // -----------------------------------------------------------
        Map<String, Long> tempIdToNewId = new HashMap<>();
        for (TreeUpdateDto.CreatedNode c : sortedCreated) {
            ControlNode parent = resolveParent(c.getParentId(), existingNodes, tempIdToNewId);
            int depth = c.getDepth() != null
                    ? c.getDepth()
                    : (parent == null ? 1 : parent.getDepth() + 1);
            int displayOrder = c.getDisplayOrder() != null ? c.getDisplayOrder() : 0;

            ControlNode saved = controlNodeRepository.save(ControlNode.builder()
                    .framework(fw)
                    .parent(parent)
                    .nodeType(NodeType.valueOf(c.getNodeType()))
                    .code(c.getCode())
                    .name(c.getName())
                    .description(c.getDescription())
                    .displayOrder(displayOrder)
                    .depth(depth)
                    .build());

            existingNodes.put(saved.getId(), saved);
            if (c.getTempId() != null) {
                tempIdToNewId.put(c.getTempId(), saved.getId());
            }
        }

        // -----------------------------------------------------------
        // 7. UPDATE (updated)
        //    ControlNode 의 기존 update 메서드 재활용 (5-14a 작성).
        //    PATCH 의미상 displayOrder/depth 는 null 전달 — 위치 변경은
        //    moved 액션의 책임.
        // -----------------------------------------------------------
        for (TreeUpdateDto.UpdatedNode u : updated) {
            ControlNode node = existingNodes.get(u.getId());
            // null safety: 검증 단계가 not-found 처리. 여기 도달 시 node != null
            node.update(u.getCode(), u.getName(), u.getDescription(), null, null);
        }

        // -----------------------------------------------------------
        // 8. MOVE (moved) — ControlNode.move() 5-14d 신규 메서드
        // -----------------------------------------------------------
        for (TreeUpdateDto.MovedNode m : moved) {
            ControlNode node = existingNodes.get(m.getId());
            ControlNode newParent = resolveParent(m.getNewParentId(), existingNodes, tempIdToNewId);
            int newDepth = m.getNewDepth() != null
                    ? m.getNewDepth()
                    : (newParent == null ? 1 : newParent.getDepth() + 1);
            int newDisplayOrder = m.getNewDisplayOrder() != null ? m.getNewDisplayOrder() : 0;
            node.move(newParent, newDisplayOrder, newDepth);
        }

        // -----------------------------------------------------------
        // 9. DELETE (deleted) — native SQL + DB ON DELETE CASCADE 우회
        //
        // v18.2 fix-2 — 옵션 A (entityManager.remove + flush) 도 silent skip 확인.
        // Hibernate 6 의 cascade graph 처리가 자손 collection 이 lazy load 된 상태에서
        // 모호함으로 silent skip 하는 패턴이 deleteById / em.remove 동일하게 발생.
        // Native SQL DELETE 로 Hibernate persistence context 전체를 우회.
        //
        // DB FK 의 ON DELETE CASCADE (ControlNode 엔티티의 @OnDelete(CASCADE) +
        // V6 마이그레이션에서 적용) 가 자손 control_nodes + evidence_types +
        // evidence_files 일괄 처리. ControlNodeSchemaTest.testCascadeOnParentDelete 에서
        // native DELETE → 자손 자동 삭제 검증 완료된 패턴 재사용.
        //
        // 처리 순서 (deleted 있을 때):
        //   (1) created/updated/moved + fw.touchVersion 먼저 commit (clear 전 마지막 기회)
        //   (2) native DELETE 발행 (각 id 마다 1회, DB CASCADE 가 자손 처리)
        //   (3) entityManager.clear() — stale persistence context 무효화
        //         (자손/evidence_types/files 는 DB 에서 사라졌지만 JPA cache 에 잔존)
        //
        // touchVersion 은 clear 전에 완료되어야 함 (clear 후 fw 도 detach 됨).
        // step 11 의 fw.getVersion() 은 단순 필드 접근으로 detach 상태에서도 정상 동작.
        // -----------------------------------------------------------
        if (!deleted.isEmpty()) {
            // (1) 다른 변경 + fw version touch 먼저 commit
            fw.touchVersion();
            entityManager.flush();

            // (2) 삭제할 노드 + 자손 모든 ID 수집 (in-memory walk)
            //
            // v18.2 fix-3 발견 — evidence_types.control_id FK 가 ON DELETE RESTRICT (CASCADE 아님).
            // EvidenceType 엔티티의 @JoinColumn 에 @OnDelete 루락 → default RESTRICT.
            // spec 의 "ON DELETE CASCADE 가 evidence_types 까지 자동 삭제" 명기와 실 스키마 mismatch.
            // 정공 fix 는 Flyway 로 FK 변경 (v18.3 또는 v19) → 일단 추가 fix 는
            // 자손 포함 evidence_files + evidence_types 를 사전에 명시 삭제.
            //
            // 자손 control_nodes 자신의 삭제는 self-FK (parent_id) 의 ON DELETE CASCADE 로 처리됨.
            //
            // existingNodes 의 ControlNode 는 이미 children 컬렉션이 lazy load 된 상태 (위 검증 단계에서
            // 발화) → in-memory walk 으로 자손 수집 가능.
            Set<Long> allDeletedIds = new HashSet<>();
            for (TreeUpdateDto.DeletedNode d : deleted) {
                ControlNode node = existingNodes.get(d.getId());
                if (node != null) {
                    collectNodeAndDescendantIds(node, allDeletedIds);
                }
            }

            // (3) evidence_files 삭제 (evidence_types 통해 연결)
            // (4) collection_jobs 삭제 (v18.2 fix-3 추가 발견 — collection_jobs.evidence_type_id FK 도 RESTRICT)
            // (5) evidence_types 삭제 (위 둘 FK 먼저 제거 후)
            //
            // 향후 FK 위반이 또 나오면 같은 패턴으로 한 줄 씩 추가. 정공 fix 는 v18.3:
            // Flyway 로 FK 들을 ON DELETE CASCADE 로 ALTER + Entity @OnDelete 개선.
            if (!allDeletedIds.isEmpty()) {
                // (a) evidence_files 삭제 (evidence_types 통해 연결)
                entityManager.createNativeQuery(
                        "DELETE FROM evidence_files WHERE evidence_type_id IN " +
                        "(SELECT id FROM evidence_types WHERE control_id IN (:nodeIds))")
                        .setParameter("nodeIds", allDeletedIds)
                        .executeUpdate();

                // (b) job_executions 삭제 (v18.2 fix-4 추가 발견 — collection_jobs 삭제 전 선행)
                //     job_executions.job_id FK 도 RESTRICT (JobExecution.job 의 @OnDelete 루락)
                entityManager.createNativeQuery(
                        "DELETE FROM job_executions WHERE job_id IN " +
                        "(SELECT id FROM collection_jobs WHERE evidence_type_id IN " +
                        "(SELECT id FROM evidence_types WHERE control_id IN (:nodeIds)))")
                        .setParameter("nodeIds", allDeletedIds)
                        .executeUpdate();

                // (c) collection_jobs 삭제
                entityManager.createNativeQuery(
                        "DELETE FROM collection_jobs WHERE evidence_type_id IN " +
                        "(SELECT id FROM evidence_types WHERE control_id IN (:nodeIds))")
                        .setParameter("nodeIds", allDeletedIds)
                        .executeUpdate();

                // (d) evidence_types 삭제
                entityManager.createNativeQuery(
                        "DELETE FROM evidence_types WHERE control_id IN (:nodeIds)")
                        .setParameter("nodeIds", allDeletedIds)
                        .executeUpdate();
            }

            // (5) control_nodes 삭제 (부모만 호출, self-FK CASCADE 가 자손 처리)
            for (TreeUpdateDto.DeletedNode d : deleted) {
                entityManager.createNativeQuery(
                        "DELETE FROM control_nodes WHERE id = :id")
                        .setParameter("id", d.getId())
                        .executeUpdate();
            }

            // (6) stale persistence context 무효화
            entityManager.clear();
        } else {
            // -----------------------------------------------------------
            // 10. Framework.@Version 강제 증가 (deleted 없는 평소 흐름)
            //
            // children (control_nodes) 변경만으로는 Hibernate dirty-checking 이
            // Framework 의 @Version 을 자동 증가시키지 않으므로 명시적 처리.
            // touchVersion() 이 @Version 필드를 직접 +1 → dirty 마킹 → flush 시
            // UPDATE frameworks SET version = ? WHERE id = ? AND version = ? SQL 발행.
            // -----------------------------------------------------------
            fw.touchVersion();
            entityManager.flush();
        }

        // -----------------------------------------------------------
        // 11. Response
        // -----------------------------------------------------------
        List<TreeUpdateDto.NodeMapping> nodeMappings = new ArrayList<>();
        for (Map.Entry<String, Long> e : tempIdToNewId.entrySet()) {
            nodeMappings.add(TreeUpdateDto.NodeMapping.builder()
                    .tempId(e.getKey()).id(e.getValue()).build());
        }

        return TreeUpdateDto.Response.builder()
                .version(fw.getVersion())
                .mappings(TreeUpdateDto.Mappings.builder().nodes(nodeMappings).build())
                .build();
    }

    // ========================================================================
    // 검증 — created
    //   규칙 3 (이름/코드 not blank), 4 (nodeType 유효),
    //   5 (parentId 유효), 6 (depth 일관성), 7 (depth ≤ 10)
    //
    // v15 Phase 5-15a — 9-부분 (parent 가 leaf 면 거부) 제거. hybrid 모델 채택.
    // ========================================================================
    private void validateCreated(List<TreeUpdateDto.CreatedNode> created,
                                  Map<Long, ControlNode> existingNodes,
                                  Map<String, TreeUpdateDto.CreatedNode> byTempId,
                                  List<ValidationDetail> details) {
        for (TreeUpdateDto.CreatedNode c : created) {
            // 규칙 3 — 이름/코드 not blank
            if (isBlank(c.getName())) {
                details.add(detailTemp(c, "name", "blank_name", "이름은 비어있을 수 없습니다"));
            }
            if (isBlank(c.getCode())) {
                details.add(detailTemp(c, "code", "blank_code", "코드는 비어있을 수 없습니다"));
            }

            // 규칙 4 — nodeType 유효
            NodeType nodeType = parseNodeType(c.getNodeType());
            if (nodeType == null) {
                details.add(detailTemp(c, "nodeType", "invalid_node_type",
                        "nodeType 은 'category' 또는 'control' 이어야 합니다"));
                continue;  // 이후 검증은 nodeType 의존
            }

            // 규칙 5 — parentId 유효
            // v15 Phase 5-15a: 9-부분 (parent 가 leaf 면 거부) 제거 — hybrid 모델
            Object pid = c.getParentId();
            Integer parentDepth = null;
            if (pid != null) {
                if (pid instanceof Number) {
                    long parentLong = ((Number) pid).longValue();
                    ControlNode parent = existingNodes.get(parentLong);
                    if (parent == null) {
                        details.add(detailTemp(c, "parentId", "parent_not_found",
                                "parentId " + parentLong + " 노드를 찾을 수 없습니다"));
                    } else {
                        // v15 Phase 5-15a — parent_must_be_category 제거 (hybrid 모델, spec §3.3.1.9).
                        // 모든 노드 아래에 자식 노드 추가 가능. 10단 가드는 별도 규칙(7)으로 유지.
                        parentDepth = parent.getDepth();
                    }
                } else if (pid instanceof String) {
                    String parentTempId = (String) pid;
                    TreeUpdateDto.CreatedNode parentCreated = byTempId.get(parentTempId);
                    if (parentCreated == null) {
                        details.add(detailTemp(c, "parentId", "unresolved_temp_id",
                                "parentId 의 임시 식별자 '" + parentTempId
                                        + "' 가 같은 요청 안에 정의되지 않았습니다"));
                    } else {
                        // v15 Phase 5-15a — parent_must_be_category 제거 (hybrid 모델, spec §3.3.1.9).
                        parentDepth = parentCreated.getDepth();
                    }
                } else {
                    details.add(detailTemp(c, "parentId", "invalid_parent_type",
                            "parentId 는 number, string(tempId), null 중 하나여야 합니다"));
                }
            }

            // 규칙 6 — depth 일관성
            if (c.getDepth() != null) {
                int expected = (pid == null) ? 1 : (parentDepth != null ? parentDepth + 1 : -1);
                if (expected != -1 && c.getDepth() != expected) {
                    details.add(detailTemp(c, "depth", "depth_mismatch",
                            "depth(" + c.getDepth() + ") 가 예상값(" + expected + ") 과 다릅니다"));
                }
                // 규칙 7 — depth ≤ 10
                if (c.getDepth() > MAX_DEPTH) {
                    details.add(detailTemp(c, "depth", "max_depth_exceeded",
                            "최대 depth(" + MAX_DEPTH + ") 를 초과했습니다"));
                }
            }
        }
    }

    // ========================================================================
    // 검증 — updated
    //   규칙 5 (id 유효), 9-부분 (nodeType 변경 거부)
    //
    // v15 Phase 5-15a 시점에 nodeType 변경 거부 보존 (transitional).
    // 5-15b 또는 그 이후 enum 일괄 제거 시 함께 정리 검토.
    // ========================================================================
    private void validateUpdated(List<TreeUpdateDto.UpdatedNode> updated,
                                  Map<Long, ControlNode> existingNodes,
                                  List<ValidationDetail> details) {
        for (TreeUpdateDto.UpdatedNode u : updated) {
            if (u.getId() == null || !existingNodes.containsKey(u.getId())) {
                details.add(detailId(u.getId(), "id", "node_not_found",
                        "id " + u.getId() + " 노드를 찾을 수 없습니다"));
                continue;
            }
            if (u.getNodeType() != null) {
                details.add(detailId(u.getId(), "nodeType", "node_type_change_not_allowed",
                        "nodeType 변경은 updated 로 불가합니다 (delete + create 또는 v2 액션 사용)"));
            }
            if (u.getName() != null && u.getName().isBlank()) {
                details.add(detailId(u.getId(), "name", "blank_name",
                        "이름은 비어있을 수 없습니다"));
            }
            if (u.getCode() != null && u.getCode().isBlank()) {
                details.add(detailId(u.getId(), "code", "blank_code",
                        "코드는 비어있을 수 없습니다"));
            }
        }
    }

    // ========================================================================
    // 검증 — moved
    //   규칙 5 (id 유효, newParentId 유효), 6 (depth), 7 (max depth), 10 (사이클)
    //
    // v15 Phase 5-15a — 9-부분 (newParentId 가 leaf 면 거부) 제거. hybrid 모델 채택.
    // ========================================================================
    private void validateMoved(List<TreeUpdateDto.MovedNode> moved,
                                Map<Long, ControlNode> existingNodes,
                                Map<String, TreeUpdateDto.CreatedNode> byTempId,
                                List<ValidationDetail> details) {
        for (TreeUpdateDto.MovedNode m : moved) {
            if (m.getId() == null || !existingNodes.containsKey(m.getId())) {
                details.add(detailId(m.getId(), "id", "node_not_found",
                        "id " + m.getId() + " 노드를 찾을 수 없습니다"));
                continue;
            }

            Object pid = m.getNewParentId();
            Integer parentDepth = null;
            if (pid != null) {
                if (pid instanceof Number) {
                    long parentLong = ((Number) pid).longValue();

                    // 규칙 10 — 사이클: 자기 자신 또는 자기 자손으로 이동 금지
                    if (parentLong == m.getId()) {
                        details.add(detailId(m.getId(), "newParentId", "cycle_detected",
                                "자기 자신을 부모로 지정할 수 없습니다"));
                    } else {
                        List<ControlNode> descendants =
                                controlNodeRepository.findAllDescendants(m.getId());
                        for (ControlNode d : descendants) {
                            if (d.getId() != null && d.getId() == parentLong) {
                                details.add(detailId(m.getId(), "newParentId", "cycle_detected",
                                        "자기 자신의 자손으로 이동할 수 없습니다"));
                                break;
                            }
                        }
                    }

                    ControlNode parent = existingNodes.get(parentLong);
                    if (parent == null) {
                        details.add(detailId(m.getId(), "newParentId", "parent_not_found",
                                "newParentId " + parentLong + " 노드를 찾을 수 없습니다"));
                    } else {
                        // v15 Phase 5-15a — parent_must_be_category 제거 (hybrid 모델, spec §3.3.1.9).
                        // 모든 노드 아래로 이동 가능. 10단 가드는 별도 규칙(7)으로 유지.
                        parentDepth = parent.getDepth();
                    }
                } else if (pid instanceof String) {
                    String parentTempId = (String) pid;
                    TreeUpdateDto.CreatedNode parentCreated = byTempId.get(parentTempId);
                    if (parentCreated == null) {
                        details.add(detailId(m.getId(), "newParentId", "unresolved_temp_id",
                                "newParentId 의 임시 식별자 '" + parentTempId
                                        + "' 가 같은 요청 안에 정의되지 않았습니다"));
                    } else {
                        // v15 Phase 5-15a — parent_must_be_category 제거 (hybrid 모델, spec §3.3.1.9).
                        parentDepth = parentCreated.getDepth();
                    }
                } else {
                    details.add(detailId(m.getId(), "newParentId", "invalid_parent_type",
                            "newParentId 는 number, string(tempId), null 중 하나여야 합니다"));
                }
            }

            // 규칙 6, 7 — depth
            if (m.getNewDepth() != null) {
                int expected = (pid == null) ? 1 : (parentDepth != null ? parentDepth + 1 : -1);
                if (expected != -1 && m.getNewDepth() != expected) {
                    details.add(detailId(m.getId(), "newDepth", "depth_mismatch",
                            "newDepth(" + m.getNewDepth() + ") 가 예상값("
                                    + expected + ") 과 다릅니다"));
                }
                if (m.getNewDepth() > MAX_DEPTH) {
                    details.add(detailId(m.getId(), "newDepth", "max_depth_exceeded",
                            "최대 depth(" + MAX_DEPTH + ") 를 초과했습니다"));
                }
            }
        }
    }

    // ========================================================================
    // 검증 — deleted (규칙 5: id 유효)
    // ========================================================================
    private void validateDeleted(List<TreeUpdateDto.DeletedNode> deleted,
                                  Map<Long, ControlNode> existingNodes,
                                  List<ValidationDetail> details) {
        for (TreeUpdateDto.DeletedNode d : deleted) {
            if (d.getId() == null || !existingNodes.containsKey(d.getId())) {
                details.add(detailId(d.getId(), "id", "node_not_found",
                        "id " + d.getId() + " 노드를 찾을 수 없습니다"));
            }
        }
    }

    // ========================================================================
    // 검증 — sibling code 중복 (규칙 8)
    //   final state 시뮬레이션: 기존 - deleted + updated.code + moved.newParent + created
    // ========================================================================
    private void validateSiblingCodes(List<TreeUpdateDto.CreatedNode> created,
                                       List<TreeUpdateDto.UpdatedNode> updated,
                                       List<TreeUpdateDto.MovedNode> moved,
                                       List<TreeUpdateDto.DeletedNode> deleted,
                                       Map<Long, ControlNode> existingNodes,
                                       List<ValidationDetail> details) {
        // 삭제 대상 set
        Set<Long> deletedIds = new HashSet<>();
        for (TreeUpdateDto.DeletedNode d : deleted) {
            if (d.getId() != null) deletedIds.add(d.getId());
        }
        // updated 의 code 변경 매핑
        Map<Long, String> overrideCode = new HashMap<>();
        for (TreeUpdateDto.UpdatedNode u : updated) {
            if (u.getId() != null && u.getCode() != null) overrideCode.put(u.getId(), u.getCode());
        }
        // moved 의 newParentId 매핑
        Map<Long, Object> overrideParent = new HashMap<>();
        for (TreeUpdateDto.MovedNode m : moved) {
            if (m.getId() != null) overrideParent.put(m.getId(), m.getNewParentId());
        }

        // (parentKey, code) → 발생 source 누적. 두 번째 발생 시 422.
        // parentKey 형식: "id:N" / "tempId:X" / "null"
        Map<String, Set<String>> siblingsByParent = new HashMap<>();

        // 기존 노드 (deleted 제외, updated 의 code/moved 의 parent 적용)
        for (ControlNode node : existingNodes.values()) {
            if (deletedIds.contains(node.getId())) continue;
            String code = overrideCode.getOrDefault(node.getId(), node.getCode());
            Object pid = overrideParent.containsKey(node.getId())
                    ? overrideParent.get(node.getId())
                    : (node.getParent() != null ? node.getParent().getId() : null);
            String key = parentKey(pid);
            Set<String> codes = siblingsByParent.computeIfAbsent(key, k -> new HashSet<>());
            if (!codes.add(code)) {
                details.add(ValidationDetail.builder()
                        .target("node").targetId(node.getId())
                        .field("code").code("duplicate_code")
                        .message("같은 분류 안에 " + code + " 코드가 이미 있습니다")
                        .build());
            }
        }

        // created 의 code 추가, 중복 검사
        for (TreeUpdateDto.CreatedNode c : created) {
            if (isBlank(c.getCode())) continue;
            String key = parentKey(c.getParentId());
            Set<String> codes = siblingsByParent.computeIfAbsent(key, k -> new HashSet<>());
            if (!codes.add(c.getCode())) {
                details.add(detailTemp(c, "code", "duplicate_code",
                        "같은 분류 안에 " + c.getCode() + " 코드가 이미 있습니다"));
            }
        }
    }

    private String parentKey(Object pid) {
        if (pid == null) return "null";
        if (pid instanceof Number) return "id:" + ((Number) pid).longValue();
        if (pid instanceof String) return "tempId:" + pid;
        return "unknown";
    }

    // ========================================================================
    // 위상 정렬 — Kahn's algorithm
    //   created 들 사이의 tempId 의존성 그래프 분석. 순환 검출 시 details 추가.
    // ========================================================================
    private List<TreeUpdateDto.CreatedNode> topologicalSort(
            List<TreeUpdateDto.CreatedNode> created,
            Map<String, TreeUpdateDto.CreatedNode> byTempId,
            List<ValidationDetail> details) {

        // tempId 가 있는 노드만 그래프 대상
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> graph = new HashMap<>();

        for (TreeUpdateDto.CreatedNode c : created) {
            if (c.getTempId() != null) {
                inDegree.putIfAbsent(c.getTempId(), 0);
            }
        }

        for (TreeUpdateDto.CreatedNode c : created) {
            if (c.getTempId() == null) continue;
            if (c.getParentId() instanceof String) {
                String parentTempId = (String) c.getParentId();
                if (byTempId.containsKey(parentTempId)) {
                    graph.computeIfAbsent(parentTempId, k -> new ArrayList<>()).add(c.getTempId());
                    inDegree.merge(c.getTempId(), 1, Integer::sum);
                }
                // 미해결 tempId 는 validateCreated 에서 별도 보고
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.offer(e.getKey());
        }

        List<TreeUpdateDto.CreatedNode> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String t = queue.poll();
            sorted.add(byTempId.get(t));
            visited.add(t);
            for (String childTempId : graph.getOrDefault(t, List.of())) {
                int newDeg = inDegree.merge(childTempId, -1, Integer::sum);
                if (newDeg == 0) queue.offer(childTempId);
            }
        }

        // 순환 검출
        if (visited.size() != inDegree.size()) {
            for (String tempId : inDegree.keySet()) {
                if (!visited.contains(tempId)) {
                    TreeUpdateDto.CreatedNode c = byTempId.get(tempId);
                    details.add(detailTemp(c, "parentId", "cycle_in_created",
                            "created 노드들 사이에 순환 의존성이 있습니다"));
                }
            }
        }

        // tempId 없는 created 노드도 결과에 포함 (의존성 무관, 순서 끝에)
        for (TreeUpdateDto.CreatedNode c : created) {
            if (c.getTempId() == null) sorted.add(c);
        }

        return sorted;
    }

    // ========================================================================
    // helper — parentId 다형 → ControlNode 해석
    // ========================================================================
    private ControlNode resolveParent(Object parentId,
                                       Map<Long, ControlNode> existingNodes,
                                       Map<String, Long> tempIdToNewId) {
        if (parentId == null) return null;
        if (parentId instanceof Number) {
            return existingNodes.get(((Number) parentId).longValue());
        }
        if (parentId instanceof String) {
            Long resolved = tempIdToNewId.get((String) parentId);
            return resolved != null ? existingNodes.get(resolved) : null;
        }
        return null;
    }

    // ========================================================================
    // 보일러플레이트
    // ========================================================================
    private static <T> List<T> nullToEmpty(List<T> list) {
        return list != null ? list : Collections.emptyList();
    }

    private static List<TreeUpdateDto.CreatedNode> getCreated(TreeUpdateDto.Request req) {
        return req.getChanges() == null || req.getChanges().getNodes() == null
                ? null : req.getChanges().getNodes().getCreated();
    }
    private static List<TreeUpdateDto.UpdatedNode> getUpdated(TreeUpdateDto.Request req) {
        return req.getChanges() == null || req.getChanges().getNodes() == null
                ? null : req.getChanges().getNodes().getUpdated();
    }
    private static List<TreeUpdateDto.MovedNode> getMoved(TreeUpdateDto.Request req) {
        return req.getChanges() == null || req.getChanges().getNodes() == null
                ? null : req.getChanges().getNodes().getMoved();
    }
    private static List<TreeUpdateDto.DeletedNode> getDeleted(TreeUpdateDto.Request req) {
        return req.getChanges() == null || req.getChanges().getNodes() == null
                ? null : req.getChanges().getNodes().getDeleted();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static NodeType parseNodeType(String s) {
        if (s == null) return null;
        try {
            return NodeType.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static ValidationDetail detailTemp(TreeUpdateDto.CreatedNode c,
                                                 String field, String code, String message) {
        return ValidationDetail.builder()
                .target("node").targetTempId(c != null ? c.getTempId() : null)
                .field(field).code(code).message(message)
                .build();
    }

    private static ValidationDetail detailId(Long id, String field, String code, String message) {
        return ValidationDetail.builder()
                .target("node").targetId(id)
                .field(field).code(code).message(message)
                .build();
    }

    /**
     * v18.2 fix-3 helper — 자손 포함 모든 ControlNode ID 재귀 수집.
     *
     * <p>evidence_files / evidence_types 사전 삭제에 필요. existingNodes 의 ControlNode 는 children 이
     * lazy load 된 상태 → in-memory 재귀로 자손 ID 전체 수집 가능.</p>
     */
    private static void collectNodeAndDescendantIds(ControlNode node, Set<Long> result) {
        result.add(node.getId());
        for (ControlNode child : node.getChildren()) {
            collectNodeAndDescendantIds(child, result);
        }
    }
}