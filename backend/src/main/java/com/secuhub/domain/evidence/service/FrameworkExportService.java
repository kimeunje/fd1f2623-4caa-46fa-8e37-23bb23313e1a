package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.BusinessException;
import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.entity.NodeType;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
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
 * Phase 5-14e — Framework 의 control_nodes 트리를 엑셀로 export.
 *
 * <p>spec §6.4 의 Import 포맷 + 계층 경로 컬럼 추가:</p>
 * <pre>
 * | 코드 | 영역 | 항목명 | 설명 | 필요 증빙 | 계층 경로 |
 * </pre>
 *
 * <p>각 행은 leaf 통제 ({@code node_type='control'}) 1개. category 는 행으로 안 나옴 — 영역 /
 * 계층 경로 컬럼에 녹여 표현. depth=N 의 leaf 도 한 행으로 표현됨.</p>
 *
 * <h3>핵심 컬럼 의미</h3>
 * <ul>
 *   <li><b>코드</b>: leaf 의 code (예: {@code "1.1.1"})</li>
 *   <li><b>영역</b>: leaf 의 depth=1 ancestor 의 name. depth=1 leaf 라면 빈 문자열.
 *       Import 포맷의 "영역" (= 구 controls.domain) 과 호환.</li>
 *   <li><b>항목명</b>: leaf 의 name</li>
 *   <li><b>설명</b>: leaf 의 description (null → 빈 문자열)</li>
 *   <li><b>필요 증빙</b>: leaf 의 evidence_types[].name 을 {@code ", "} 로 연결.
 *       5-14e 시점 dev/test 에서는 매핑 미통합으로 빈 문자열 자연 (impact-summary 와 같은
 *       패턴 — 5-14f 후 정상 작동).</li>
 *   <li><b>계층 경로</b>: 모든 ancestors[].code + leaf.code 를 {@code " > "} 로 연결
 *       (예: {@code "1 > 1.1 > 1.1.1"}). spec §3.4.0 의 "ancestors 헤더 서브텍스트"
 *       와 의미 일치.</li>
 * </ul>
 *
 * <h3>Lazy load 전략</h3>
 * <p>{@link ControlNodeRepository#findByFrameworkIdOrderByDepthAscDisplayOrderAsc(Long)}
 * 가 5-14c 에서 {@code LEFT JOIN FETCH cn.parent} 를 적용해 첫 단계 parent 는 즉시 load.
 * 다단계 ancestors traversal 시 parent.parent 는 lazy 일 수 있어 byId 맵으로 in-memory
 * traversal 수행. 같은 영속성 컨텍스트 안에서 같은 entity 재참조 — N+1 차단.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrameworkExportService {

    private final FrameworkRepository frameworkRepository;
    private final ControlNodeRepository controlNodeRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;

    /** Excel 컬럼 인덱스 — Import 포맷 + 계층 경로. */
    private static final String[] HEADERS = {
            "코드", "영역", "항목명", "설명", "필요 증빙", "계층 경로"
    };

    /**
     * Framework 의 트리를 엑셀 byte[] 로 export.
     *
     * <p>Framework 검증 (404) 후 모든 노드 조회 → leaf 만 추출 → byId 맵으로 ancestors 빌드 →
     * POI XSSFWorkbook 생성. 빈 트리도 헤더만 있는 시트 정상 export (Import 템플릿용).</p>
     *
     * @param frameworkId Framework id
     * @return ExportResult { data, fileName }
     * @throws ResourceNotFoundException Framework 미존재
     * @throws BusinessException IO 에러
     */
    @Transactional(readOnly = true)
    public ExportResult export(Long frameworkId) {
        Framework framework = frameworkRepository.findById(frameworkId)
                .orElseThrow(() -> new ResourceNotFoundException("프레임워크", frameworkId));

        // 1. 모든 노드 조회 (5-14c 의 LEFT JOIN FETCH cn.parent + JVM-side 정렬은 트리 응답용,
        //    여기는 단순 평탄화 list 만 필요)
        List<ControlNode> allNodes = controlNodeRepository
                .findByFrameworkIdOrderByDepthAscDisplayOrderAsc(frameworkId);

        // 2. id → node 맵 (ancestors traversal 시 in-memory lookup 으로 lazy load 회피)
        Map<Long, ControlNode> byId = allNodes.stream()
                .collect(Collectors.toMap(ControlNode::getId, n -> n));

        // 3. leaf 만 추출 + 코드 ASC 정렬 (Import 포맷과 호환되는 순서)
        List<ControlNode> leafs = allNodes.stream()
                .filter(n -> n.getNodeType() == NodeType.control)
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

            // leaf 데이터 행
            int rowIdx = 1;
            for (ControlNode leaf : leafs) {
                Row row = sheet.createRow(rowIdx++);
                List<ControlNode> ancestors = buildAncestors(leaf, byId);

                row.createCell(0).setCellValue(leaf.getCode() != null ? leaf.getCode() : "");

                // 영역 = depth=1 ancestor 의 name. depth=1 leaf 면 빈 문자열.
                String domain = ancestors.isEmpty() ? "" : ancestors.get(0).getName();
                row.createCell(1).setCellValue(domain != null ? domain : "");

                row.createCell(2).setCellValue(leaf.getName() != null ? leaf.getName() : "");
                row.createCell(3).setCellValue(leaf.getDescription() != null ? leaf.getDescription() : "");

                // 필요 증빙 — leaf 의 evidence_types (5-14e 시점 dev/test 빈 문자열 자연,
                // prod V6 후 정상 매칭. 5-14f 매핑 이주 후 dev/test 도 정상)
                List<EvidenceType> ets = evidenceTypeRepository.findByControlId(leaf.getId());
                String etNames = ets.stream()
                        .map(EvidenceType::getName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining(", "));
                row.createCell(4).setCellValue(etNames);

                // 계층 경로 — ancestors[].code + leaf.code 를 " > " 로 연결
                List<String> pathCodes = new ArrayList<>(ancestors.size() + 1);
                for (ControlNode a : ancestors) pathCodes.add(a.getCode() != null ? a.getCode() : "");
                pathCodes.add(leaf.getCode() != null ? leaf.getCode() : "");
                row.createCell(5).setCellValue(String.join(" > ", pathCodes));
            }

            // 컬럼 너비 자동
            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            wb.write(out);
            log.info("Framework export 완료: id={}, leafs={}, bytes={}",
                    frameworkId, leafs.size(), out.size());

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
     * leaf 의 ancestors 를 root 부터 직계 부모 순서로 반환 (leaf 자기 자신 미포함).
     *
     * <p>byId 맵을 활용해 in-memory traversal — N+1 차단. 5-14c 의 LEFT JOIN FETCH 가
     * 첫 단계 parent 보장하지만, parent.parent 는 lazy 라 byId.get() 으로 직접 가져옴.
     * 무한 루프 방지를 위해 visited set 으로 가드 (이론적으로 사이클은 PATCH /tree 의
     * 검증 규칙 #10 에서 차단됐지만 안전망).</p>
     */
    private List<ControlNode> buildAncestors(ControlNode leaf, Map<Long, ControlNode> byId) {
        List<ControlNode> ancestors = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        ControlNode cur = leaf;
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