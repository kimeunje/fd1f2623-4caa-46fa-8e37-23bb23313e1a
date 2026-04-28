package com.secuhub.domain.evidence.service;

import com.secuhub.domain.evidence.dto.ImpactSummaryDto;
import com.secuhub.domain.evidence.repository.CollectionJobRepository;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 5-14e — control_node 단위 운영 데이터 조회 서비스.
 *
 * <p>현재는 impact-summary 한 메서드만 보유. 5-14f / v15 에서 ControlNode 단위
 * 운영 메서드 (예: leaf 의 evidence_types 집계, 통제 단위 통계) 가 추가될 자리.</p>
 *
 * <p>5-14a 의 {@link com.secuhub.domain.evidence.entity.ControlNode} 엔티티 +
 * 5-14c 의 {@link com.secuhub.domain.evidence.repository.ControlNodeRepository} 와
 * 별개. impact-summary 는 leaf 단위 운영 데이터 (evidence_files / collection_jobs /
 * review 이력) 카운트 만 다루므로 {@link ControlNodeRepository} 의존성 없음
 * (5-14a 의 메서드 5종 + 5-14c 의 1종 그대로 재활용 가능, 본 서비스에서 사용 안 함).</p>
 *
 * <p>spec §3.3.1.5 정합. 응답 shape: {@link ImpactSummaryDto}
 * { evidenceFileCount, jobCount, reviewCount }.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlNodeService {

    private final EvidenceFileRepository evidenceFileRepository;
    private final CollectionJobRepository collectionJobRepository;

    /**
     * leaf 통제 코드 변경 사전 경고 다이얼로그 (5-14h FE) 가 호출.
     *
     * <p>입력 {@code controlId} 는 leaf control_node.id (5-14e Q1=A 결정 — spec §3.3.1.5).
     * 5-14h FE 가 ControlNode.id 로 호출. service 는 받은 id 로 evidence_files /
     * collection_jobs 의 control 매칭 카운트만 리턴. ControlNode 존재 검증 없음
     * — 매칭 0 면 모두 0 리턴 (404 아님). 단순함이 핵심 (5-14h FE 가 leaf 만 호출).</p>
     *
     * <p>5-14e 시점 환경별 동작:</p>
     * <ul>
     *   <li><b>prod V6 후</b>: {@code evidence_types.control_id == leaf control_node.id}
     *       (V6 Step 3b 이주). 자연 매칭, 의미 있는 카운트.</li>
     *   <li><b>dev/test (V6 미실행)</b>: {@code Control} 과 {@code ControlNode} 가 별개
     *       sequence. 클라이언트가 ControlNode.id 로 호출 → 매칭 0 자연 결과
     *       (5-14f 매핑 이주 전까지 의도된 동작).</li>
     * </ul>
     *
     * <p>{@code reviewCount} 의 의미 (Q4 결정): {@code reviewed_at IS NOT NULL}
     * — 관리자가 명시 검토한 횟수. pending / auto_approved 제외, approved / rejected 포함.
     * spec §3.3.1.5 의 "검토 이력 N건" 표현 정합.</p>
     *
     * @param controlId leaf control_node.id (외부 클라이언트가 호출하는 식별자)
     * @return {evidenceFileCount, jobCount, reviewCount} — 각 카운트 0 가능
     */
    @Transactional(readOnly = true)
    public ImpactSummaryDto getImpactSummary(Long controlId) {
        long fileCount = evidenceFileRepository.countByControlId(controlId);
        long jobCount = collectionJobRepository.countByControlId(controlId);
        long reviewCount = evidenceFileRepository.countReviewedByControlId(controlId);

        return ImpactSummaryDto.builder()
                .evidenceFileCount(fileCount)
                .jobCount(jobCount)
                .reviewCount(reviewCount)
                .build();
    }
}