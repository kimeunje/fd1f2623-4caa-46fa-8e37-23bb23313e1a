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
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.repository.UserRepository;
import com.secuhub.domain.audit.AuditAction;
import com.secuhub.domain.audit.AuditResult;
import com.secuhub.domain.audit.AuditService;
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
 * <p>spec §3.3.1.4 의 검증 규칙을 단일 트랜잭션 안에서 실행하고, 모든 검증 통과 시
 * INSERT/UPDATE/MOVE/DELETE 를 적용한다.</p>
 *
 * <h3>v15 Phase 5-15a — Hybrid 모델 (검증 12 → 10)</h3>
 * <p>spec §3.3.1.9 hybrid 채택으로 parent_must_be_category / leaf_with_evidence 2 검증 제거.</p>
 *
 * <h3>v18.3 — DELETE 정공 fix</h3>
 * <p>native SQL {@code DELETE FROM control_nodes WHERE id=:id} 1줄 + entity {@code @OnDelete(CASCADE)}
 * 로 DB cascade chain(control_nodes self-FK → evidence_types → collection_jobs → evidence_files).
 * {@code entityManager.clear()} 미사용(UPDATE/MOVE dirty 보존).</p>
 *
 * <h3>AUDIT (A-2) — 트리 변경 감사</h3>
 * <p>{@code @Auditable} 대신 명시 기록. 반환 DTO 에 framework 이름이 없어, 메서드 안에서 로드한
 * {@code fw.getName()} 을 targetName 으로, created/updated/moved/deleted 건수를 detail 로 남긴다.
 * 성공·실패(409/422/404) 모두 기록. 통제 노드 생성("관리항목 생성")은 created 건수로 포착된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreeUpdateService {

    /** spec §3.3.1.3 의 max depth 가드. CHECK 제약 (depth BETWEEN 1 AND 10) 과 정합. */
    private static final int MAX_DEPTH = 10;

    private final FrameworkRepository frameworkRepository;
    private final ControlNodeRepository controlNodeRepository;
    private final AuditService auditService;   // A-2: 트리 변경 감사
    private final UserRepository userRepository; // v19.26: 인수인계 노트 작성자 이름 조회

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * v19.26 — 하위호환 오버로드. editorUserId 없이 호출하면 인수인계 노트 작성자는
     * 미기록(null). 기존 테스트/호출부 보존용. 실제 요청은 컨트롤러가 3-arg 로 호출한다.
     */
    @Transactional
    public TreeUpdateDto.Response updateTree(Long frameworkId, TreeUpdateDto.Request req) {
        return updateTree(frameworkId, req, null);
    }

    /**
     * @param editorUserId v19.26 — 현재 로그인 사용자 id. description 이 실제로 바뀐
     *                     노드가 있을 때만 이 id 로 이름을 조회해 작성자로 박제한다.
     */
    @Transactional
    public TreeUpdateDto.Response updateTree(Long frameworkId, TreeUpdateDto.Request req, Long editorUserId) {
        Framework fw = null;
        try {
            // -----------------------------------------------------------
            // 1. Framework lookup
            // -----------------------------------------------------------
            fw = frameworkRepository.findById(frameworkId)
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
            // 7. UPDATE (updated) — PATCH 의미상 displayOrder/depth 는 null (위치는 moved 책임)
            // -----------------------------------------------------------
            // v19.26 — 인수인계 노트 작성자 이름. description 을 담은 updated 가 하나라도
            // 있을 때만 1회 조회 (없으면 null, 조회 스킵). 실제 값 변경 판정은 entity 가 함.
            String editorName = resolveEditorName(editorUserId, updated);
            for (TreeUpdateDto.UpdatedNode u : updated) {
                ControlNode node = existingNodes.get(u.getId());
                // null safety: 검증 단계가 not-found 처리. 여기 도달 시 node != null
                node.update(u.getCode(), u.getName(), u.getDescription(), null, null, editorName);
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
            // 9. DELETE (deleted) — v18.3 정공: native SQL DELETE 1줄 + DB FK 4단 cascade.
            //    Hibernate em.remove silent skip (L_HIBERNATE_CASCADE_SILENT) 회피.
            // -----------------------------------------------------------
            for (TreeUpdateDto.DeletedNode d : deleted) {
                entityManager.createNativeQuery(
                        "DELETE FROM control_nodes WHERE id = :id")
                    .setParameter("id", d.getId())
                    .executeUpdate();
            }

            // v18.3 회귀 fix — entityManager.clear() 미사용 (UPDATE/MOVE dirty 보존; flush 시 동반 반영).

            // -----------------------------------------------------------
            // 10. Framework.@Version 강제 증가 (touchVersion + flush)
            // -----------------------------------------------------------
            fw.touchVersion();
            entityManager.flush();

            // -----------------------------------------------------------
            // 11. Response
            // -----------------------------------------------------------
            List<TreeUpdateDto.NodeMapping> nodeMappings = new ArrayList<>();
            for (Map.Entry<String, Long> e : tempIdToNewId.entrySet()) {
                nodeMappings.add(TreeUpdateDto.NodeMapping.builder()
                        .tempId(e.getKey()).id(e.getValue()).build());
            }

            TreeUpdateDto.Response response = TreeUpdateDto.Response.builder()
                    .version(fw.getVersion())
                    .mappings(TreeUpdateDto.Mappings.builder().nodes(nodeMappings).build())
                    .build();

            // AUDIT — 성공: framework 이름 + 변경 건수 요약
            safeAudit(AuditResult.SUCCESS, frameworkId, fw.getName(),
                    auditService.toJson(Map.of(
                            "created", created.size(),
                            "updated", updated.size(),
                            "moved", moved.size(),
                            "deleted", deleted.size())));

            return response;

        } catch (RuntimeException ex) {
            // AUDIT — 실패 (fw 로드 전 404 면 이름 null)
            safeAudit(AuditResult.FAILURE, frameworkId, fw != null ? fw.getName() : null,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw ex;
        }
    }

    /**
     * v19.26 — 인수인계 노트 작성자 이름 해석.
     *
     * <p>editorUserId 가 null 이거나(하위호환 2-arg 경로) description 을 담은 updated 가
     * 하나도 없으면 조회 없이 null 반환. 이름을 못 찾아도 null — 노트 텍스트는 저장되고
     * 작성자만 미상으로 남는다(저장 자체를 막지 않음). 조회는 최대 1회.</p>
     */
    private String resolveEditorName(Long editorUserId, List<TreeUpdateDto.UpdatedNode> updated) {
        if (editorUserId == null) return null;
        boolean anyDescription = updated.stream().anyMatch(u -> u.getDescription() != null);
        if (!anyDescription) return null;
        return userRepository.findById(editorUserId).map(User::getName).orElse(null);
    }

    /** 감사 기록 — 실패가 업무 흐름을 막지 않도록 삼킴(REQUIRES_NEW 라 별도 tx). */
    private void safeAudit(AuditResult result, Long frameworkId, String frameworkName, String detail) {
        try {
            auditService.record(AuditAction.TREE_CHANGE, result, "Framework",
                    frameworkId == null ? null : String.valueOf(frameworkId), frameworkName, detail);
        } catch (Exception ignore) {
            // 감사 실패는 본 흐름에 영향 없음
        }
    }

    // ========================================================================
    // 검증 — created
    //   규칙 3 (이름/코드 not blank), 4 (nodeType 유효), 5 (parentId 유효),
    //   6 (depth 일관성), 7 (depth ≤ 10)
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

            // 규칙 6 — depth 일관성
            if (m.getNewDepth() != null) {
                int expected = (pid == null)
                        ? 1 : (parentDepth != null ? parentDepth + 1 : -1);
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
}