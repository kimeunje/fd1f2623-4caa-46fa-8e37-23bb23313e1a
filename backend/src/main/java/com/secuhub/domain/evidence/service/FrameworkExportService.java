package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.NodeType;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import com.secuhub.config.audit.Auditable;
import com.secuhub.domain.audit.AuditAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 5-14e (생성, leaf 평탄화) /
 * v15 Phase 5-15a 후속-3 (v15.14, hybrid 평탄화 정합) —
 * Framework 의 control_nodes 트리를 엑셀로 export.
 *
 * <p>spec §6.4 의 Import 포맷 + 계층 경로 컬럼:</p>
 * <pre>
 * | 코드 | 영역 | 항목명 | 설명 | 필요 증빙 | 계층 경로 |
 * </pre>
 *
 * <h3>v15 Phase 5-15a 후속-3 (v15.14) — hybrid 평탄화 정합</h3>
 *
 * <p>v14 시점 (5-14e) export 대상 = leaf only ({@code node_type='control'}). v15.0
 * (5-15a) hybrid 모델 채택 후 category 노드도 evidence 보유 가능 → category + evidence
 * 의 silent data loss 갭 (사용자가 hybrid 만들어놓고 export 하면 그 노드의 evidence
 * 가 Excel 에서 사라짐). 본 phase 가 평탄화 대상을 다음 두 분기로 갱신:</p>
 *
 * <ul>
 *   <li><b>leaf ({@code node_type='control'})</b> — 기존 동작 그대로. evidence 0 인
 *       leaf 도 통제 항목 목록 의미로 행 보존.</li>
 *   <li><b>hybrid category</b> — {@code node_type='category'} + {@code !evidenceTypes
 *       .isEmpty()}. evidence 0 인 순수 category 는 행 0 (계층 경로 컬럼에만 자손의
 *       ancestor 로 녹음).</li>
 * </ul>
 *
 * <p>운영 영향: hybrid 노드 0 인 환경 = export 결과 v15.14 ≡ v14.5 동일. hybrid
 * 노드 1+ = 새 행으로 추가됨 (사용자 의도 정합 — hybrid 모델 = "노드 자체에 증빙").
 * v15.5.1 ~ v15.13 의 5 phase 동안 미룬 항목의 마감.</p>
 *
 * <h3>핵심 컬럼 의미 (v15.14)</h3>
 * <ul>
 *   <li><b>코드</b>: 해당 노드의 code (예: {@code "1.1.1"})</li>
 *   <li><b>영역</b>: 해당 노드의 depth=1 ancestor 의 name. depth=1 노드 자체면 빈 문자열.
 *       Import 포맷의 "영역" (= 구 controls.domain) 과 호환.</li>
 *   <li><b>항목명</b>: 해당 노드의 name</li>
 *   <li><b>설명</b>: 해당 노드의 description (null → 빈 문자열)</li>
 *   <li><b>필요 증빙</b>: 해당 노드의 evidence_types[].name 을 {@code ", "} 로 연결.
 *       leaf + evidence 0 = 빈 문자열 (기존 동작). hybrid = 항상 비어있지 않음
 *       (filter 조건).</li>
 *   <li><b>계층 경로</b>: 모든 ancestors[].code + 본 노드.code 를 {@code " > "} 로 연결
 *       (예: {@code "1 > 1.1 > 1.1.1"}). spec §3.4.0 의 "ancestors 헤더 서브텍스트"
 *       와 의미 일치.</li>
 * </ul>
 *
 * <h3>Lazy load 전략 — v15.14 단순화</h3>
 *
 * <p>v14 시점에는 {@code evidenceTypeRepository.findByControlNodeId(...)} 별도 조회.
 * 본 phase 에서 {@link ControlNode#getEvidenceTypes()} (LAZY) 직접 활용 —
 * {@code @Transactional(readOnly = true)} 안에서 자연 hydrate. 트리 크기 작음 (typical
 * &lt; 500 노드) + export 빈도 낮음 (일/주 단위) 으로 N+1 운영 영향 무시.
 * {@code evidenceTypeRepository} inject 자연 제거 (본 service 의 유일한 사용처였음).</p>
 *
 * <p>{@link ControlNodeRepository#findByFrameworkIdOrderByDepthAscDisplayOrderAsc(Long)}
 * 가 5-14c 에서 {@code LEFT JOIN FETCH cn.parent} 적용으로 첫 단계 parent 즉시 load.
 * 다단계 ancestors traversal 시 parent.parent 는 lazy 라 byId 맵으로 in-memory
 * traversal 수행.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrameworkExportService {

    private final FrameworkRepository frameworkRepository;
    private final ControlNodeRepository controlNodeRepository;

    /** Excel 컬럼 인덱스 — Import 포맷 + 계층 경로. */
    private static final String[] HEADERS = {
            "코드", "영역", "항목명", "설명", "필요 증빙", "계층 경로"
    };

    /**
     * Framework 의 트리를 엑셀 byte[] 로 export.
     *
     * <p>Framework 검증 (404) 후 모든 노드 조회 → export 대상 노드 추출 (leaf 모두
     * + evidence 보유 hybrid category) → byId 맵으로 ancestors 빌드 → POI XSSFWorkbook
     * 생성. 빈 트리 / 모든 노드 evidence 0 + leaf 0 인 트리도 헤더만 있는 시트 정상
     * export (Import 템플릿용).</p>
     *
     * @param frameworkId Framework id
     * @return ExportResult { data, fileName }
     * @throws ResourceNotFoundException Framework 미존재
     * @throws BusinessException IO 에러
     */
    @Auditable(action = AuditAction.FILE_DOWNLOAD, targetType = "Framework",
               targetId = "#a0", targetName = "#result.fileName")   // a0=frameworkId, result.fileName=xlsx명
    @Transactional(readOnly = true)
    public ExportResult export(Long frameworkId) {
        Framework framework = frameworkRepository.findById(frameworkId)
                .orElseThrow(() -> new ResourceNotFoundException("프레임워크", frameworkId));

        // 1. 모든 노드 조회 (5-14c 의 LEFT JOIN FETCH cn.parent)
        List<ControlNode> allNodes = controlNodeRepository
                .findByFrameworkIdOrderByDepthAscDisplayOrderAsc(frameworkId);

        // 2. id → node 맵 (ancestors traversal 시 in-memory lookup)
        Map<Long, ControlNode> byId = allNodes.stream()
                .collect(Collectors.toMap(ControlNode::getId, n -> n));

        // 3. export 대상 노드 추출 + 코드 ASC 정렬 (Import 포맷과 호환되는 순서).
        //    v14 시점 = leaf only. v15.14 (5-15a 후속-3) 부터 hybrid 정합:
        //      - leaf (node_type=control) — 기존 동작 그대로 (evidence 무관)
        //      - hybrid category (node_type=category + evidence 1+) — 신규 포함
        //    evidence 0 인 순수 category 는 행 0 (자손의 ancestor 로만 녹음).
        List<ControlNode> exportNodes = allNodes.stream()
                .filter(this::shouldExport)
                .sorted(Comparator.comparing(ControlNode::getCode,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();

        // 4. POI XSSFWorkbook 생성
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("통제 항목");
            CellStyle headerStyle = buildHeaderStyle(wb);

            // 헤더 행
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            // 데이터 행 — leaf + hybrid 모두
            int rowIdx = 1;
            for (ControlNode node : exportNodes) {
                Row row = sheet.createRow(rowIdx++);
                List<ControlNode> ancestors = buildAncestors(node, byId);

                row.createCell(0).setCellValue(node.getCode() != null ? node.getCode() : "");

                // 영역 = depth=1 ancestor 의 name. depth=1 노드 자체면 빈 문자열.
                String domain = ancestors.isEmpty() ? "" : ancestors.get(0).getName();
                row.createCell(1).setCellValue(domain != null ? domain : "");

                row.createCell(2).setCellValue(node.getName() != null ? node.getName() : "");
                row.createCell(3).setCellValue(node.getDescription() != null ? node.getDescription() : "");

                // 필요 증빙 — node 의 evidence_types (v15.14: getEvidenceTypes() 직접
                // 활용, evidenceTypeRepository.findByControlNodeId 호출 제거).
                List<EvidenceType> ets = node.getEvidenceTypes();
                String etNames = (ets == null) ? "" : ets.stream()
                        .map(EvidenceType::getName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining(", "));
                row.createCell(4).setCellValue(etNames);

                // 계층 경로 — ancestors[].code + node.code 를 " > " 로 연결
                List<String> pathCodes = new ArrayList<>(ancestors.size() + 1);
                for (ControlNode a : ancestors) pathCodes.add(a.getCode() != null ? a.getCode() : "");
                pathCodes.add(node.getCode() != null ? node.getCode() : "");
                row.createCell(5).setCellValue(String.join(" > ", pathCodes));
            }

            // 컬럼 너비 자동
            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            wb.write(out);
            log.info("Framework export 완료: id={}, exportNodes={}, bytes={}",
                    frameworkId, exportNodes.size(), out.size());

            String fileName = (framework.getName() != null ? framework.getName() : "framework")
                    + "_통제목록.xlsx";
            return new ExportResult(out.toByteArray(), fileName);

        } catch (IOException e) {
            log.error("Framework export 실패: id={}, msg={}", frameworkId, e.getMessage(), e);
            throw new BusinessException("엑셀 생성에 실패했습니다: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 내부 헬퍼
    // ------------------------------------------------------------------

    /**
     * v15.14 (5-15a 후속-3) — export 대상 노드 판정.
     *
     * <ul>
     *   <li>leaf ({@code node_type='control'}) — 항상 true (evidence 무관, 기존 동작
     *       보존)</li>
     *   <li>hybrid category ({@code node_type='category'} + evidence 1+) — true
     *       (v15.0 hybrid 모델 정합)</li>
     *   <li>순수 category ({@code node_type='category'} + evidence 0) — false
     *       (자손의 ancestor 로만 녹음, 행 0)</li>
     * </ul>
     */
    private boolean shouldExport(ControlNode node) {
        if (node.getNodeType() == NodeType.control) {
            return true;  // leaf — 기존 동작 보존
        }
        // category — evidence 1+ 일 때만 (hybrid 정합)
        List<EvidenceType> ets = node.getEvidenceTypes();
        return ets != null && !ets.isEmpty();
    }

    /**
     * 노드의 ancestors 를 root 부터 직계 부모 순서로 반환 (자기 자신 미포함).
     *
     * <p>byId 맵을 활용해 in-memory traversal — N+1 차단. 5-14c 의 LEFT JOIN FETCH 가
     * 첫 단계 parent 보장하지만, parent.parent 는 lazy 라 byId.get() 으로 직접 가져옴.
     * 무한 루프 방지를 위해 visited set 으로 가드 (이론적으로 사이클은 PATCH /tree 의
     * 검증 규칙 #10 에서 차단됐지만 안전망).</p>
     */
    private List<ControlNode> buildAncestors(ControlNode node, Map<Long, ControlNode> byId) {
        List<ControlNode> ancestors = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        ControlNode cur = node;
        while (cur != null) {
            ControlNode parentRef = cur.getParent();
            if (parentRef == null) break;
            Long pid = parentRef.getId();
            if (pid == null || !visited.add(pid)) break;  // null 또는 사이클
            ControlNode parent = byId.get(pid);
            if (parent == null) break;  // map 밖 (다른 framework 등 — 정상 트리에서는 발생 안 함)
            ancestors.add(0, parent);
            cur = parent;
        }
        return ancestors;
    }

    private CellStyle buildHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    /**
     * Export 결과 — byte 본문 + 파일명. controller 가 Content-Disposition 헤더 빌드 시 사용.
     */
    public record ExportResult(byte[] data, String fileName) {}
}