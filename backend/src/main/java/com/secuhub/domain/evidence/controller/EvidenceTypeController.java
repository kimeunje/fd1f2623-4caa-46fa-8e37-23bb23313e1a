package com.secuhub.domain.evidence.controller;

import com.secuhub.common.dto.ApiResponse;
import com.secuhub.domain.evidence.service.ControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * v15 Phase 5-15g (v15.11) — EvidenceType 단위 운영 API.
 *
 * <h3>도입 배경</h3>
 *
 * <p>v15.6 (5-15b R3) 에서 FE {@code evidenceTypesApi.delete}
 * (evidenceApi.ts:128-130) 신설 — 옛 {@code controlsApi.deleteEvidenceType}
 * 의 namespace 일치 이전 (path = {@code /api/v1/evidence-types/{id}}). 그러나
 * BE 측 controller 매핑은 누락된 채로 v15.6 종료. v15.5.1 의 leaf 클릭 회귀와
 * 동형 패턴 (FE caller 잔존 + BE endpoint 부재 → 운영 시 404).</p>
 *
 * <p>v15.8 ApiSurfaceTest 도입 시점에 known gap 으로 검출
 * ({@code SET_1_KNOWN_GAPS} 의 {@code DELETE /api/v1/evidence-types/{*}}).
 * v15.11 (5-15g) 에서 본 controller 신설로 회수, whitelist entry 삭제 →
 * SET_1_KNOWN_GAPS empty 도달 (v15.8 도입 이래 첫 0).</p>
 *
 * <h3>왜 ControlNodeController 안이 아니라 분리</h3>
 *
 * <p>본 controller 의 path namespace ({@code /api/v1/evidence-types/}) 와
 * RequestMapping prefix 정합 — {@code ControlNodeController}
 * ({@code /api/v1/control-nodes}) 와 prefix 가 다르므로 자연 분리. v15.6
 * 결정 §1.7-b ("FE 추상화 = path 일치") 의 BE 사이드 정합. 후속 phase 에서
 * EvidenceType CRUD (생성 / 갱신 / 단건 조회 등) 확장 시 본 controller 안에
 * 자연 추가.</p>
 *
 * <h3>service 위임 (신규 service 0)</h3>
 *
 * <p>{@link ControlService#deleteEvidenceType(Long)} 그대로 호출. 본 메서드는
 * v14 Phase 5-14f 부터 보존된 단순 경로 — {@code evidence_type.id} 미존재 시
 * {@link com.secuhub.common.exception.ResourceNotFoundException} (404),
 * 존재 시 {@code evidenceTypeRepository.deleteById}. 매달린 {@code evidence_files}
 * 는 FK CASCADE / orphanRemoval 로 자동 삭제.</p>
 *
 * <p>v14 5-14f javadoc ({@code ControlService.deleteEvidenceType}) 인용:
 * "5-14f 의 410 Gone 정책 본질이 'Control 엔티티 직접 사용 금지' 이지
 * 'evidence_type 삭제 금지' 가 아니므로 그대로 보존." → 본 controller 신설은
 * 정책 변경 0, 단순히 FE 호출경로의 BE 사이드 wire 복구.</p>
 *
 * <h3>권한</h3>
 *
 * <p>{@code @PreAuthorize("hasRole('ADMIN')")} 클래스 레벨 — 옛
 * {@code controlsApi.deleteEvidenceType} 시점 (v15.3 이전 ControlController)
 * 권한과 동일 (admin 전용). 담당자는 ControlsView 의 [통제 관리] 다이얼로그
 * 자체에 진입 불가 (admin only) → 본 endpoint 도 admin only 유지가 정합.</p>
 */
@RestController
@RequestMapping("/api/v1/evidence-types")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class EvidenceTypeController {

    private final ControlService controlService;

    /**
     * 증빙 유형 단건 삭제.
     *
     * <p>FE caller: {@code ControlsView.vue:344} (증빙 유형 우측 🗑 버튼,
     * {@code evidenceTypesApi.delete(etId)} 호출).</p>
     *
     * <p>응답 시나리오:</p>
     * <ul>
     *   <li><b>200 OK</b> — 삭제 성공 ({@code ApiResponse} 본문, 메시지)</li>
     *   <li><b>404 Not Found</b> — id 미존재 (GlobalExceptionHandler 가
     *       ResourceNotFoundException 매핑)</li>
     *   <li><b>401 Unauthorized</b> — 익명 호출 (Spring Security 기본 매핑)</li>
     *   <li><b>403 Forbidden</b> — 비-admin (담당자) 호출 — 운영 시 도달 0
     *       (UI 진입 자체가 admin only) 이지만 직접 호출 시 차단</li>
     * </ul>
     *
     * @param id evidence_type.id (PK)
     * @return 200 OK + 빈 데이터 응답
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        controlService.deleteEvidenceType(id);
        return ResponseEntity.ok(ApiResponse.ok("증빙 유형이 삭제되었습니다."));
    }
}