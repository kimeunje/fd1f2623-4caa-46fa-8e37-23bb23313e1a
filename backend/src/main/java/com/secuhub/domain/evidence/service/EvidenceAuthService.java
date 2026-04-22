package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.domain.evidence.entity.EvidenceFile;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 증빙 접근 권한 판단 서비스 (Phase 5-2 신규)
 *
 * <h3>권한 규칙</h3>
 * <ul>
 *   <li><b>admin</b> — 모든 증빙에 무조건 접근 허용</li>
 *   <li><b>담당자</b> — {@code permission_evidence=true} AND
 *       {@code evidence_types.owner_user_id = currentUser.id} 인 경우에만 접근 허용</li>
 *   <li>그 외 — 차단</li>
 * </ul>
 *
 * <h3>의도</h3>
 * <p>Controller와 Service 양쪽에서 동일한 규칙으로 판단하기 위한 단일 진실 원천.
 * 향후 Phase 5-4 (승인 API), Phase 5-5 ("내 할 일" API) 에서도 재사용된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceAuthService {

    private final UserRepository userRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final EvidenceFileRepository evidenceFileRepository;

    // ------------------------------------------------------------------
    // 공개 메서드 — boolean 버전 (분기 판단용)
    // ------------------------------------------------------------------

    /**
     * 주어진 EvidenceType 에 접근할 수 있는지 판단.
     * EvidenceType 이 존재하지 않으면 false 반환 (존재 여부 검증은 호출자 책임).
     */
    @Transactional(readOnly = true)
    public boolean canAccessEvidenceType(Long evidenceTypeId, UserPrincipal principal) {
        if (principal == null) return false;
        if (isAdmin(principal)) return true;

        EvidenceType et = evidenceTypeRepository.findById(evidenceTypeId).orElse(null);
        if (et == null) return false;

        return isOwnerWithPermission(et, principal);
    }

    /**
     * 주어진 EvidenceFile 에 접근할 수 있는지 판단.
     * 소속 EvidenceType 의 owner 기준으로 판정.
     */
    @Transactional(readOnly = true)
    public boolean canAccessFile(Long fileId, UserPrincipal principal) {
        if (principal == null) return false;
        if (isAdmin(principal)) return true;

        EvidenceFile file = evidenceFileRepository.findById(fileId).orElse(null);
        if (file == null) return false;

        return isOwnerWithPermission(file.getEvidenceType(), principal);
    }

    // ------------------------------------------------------------------
    // 공개 메서드 — assert 버전 (API 진입점 가드)
    // ------------------------------------------------------------------

    /**
     * 접근 불가 시 {@link AccessDeniedException} 을 던진다.
     * 자원이 존재하지 않는 경우에도 AccessDeniedException 을 던져
     * 존재 여부를 유출하지 않도록 한다 (admin 경로는 서비스에서 404 처리됨).
     */
    @Transactional(readOnly = true)
    public void assertCanAccessEvidenceType(Long evidenceTypeId, UserPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("인증 정보가 없습니다.");
        }
        if (isAdmin(principal)) return;

        EvidenceType et = evidenceTypeRepository.findById(evidenceTypeId)
                .orElseThrow(() -> new AccessDeniedException("해당 증빙에 접근 권한이 없습니다."));

        if (!isOwnerWithPermission(et, principal)) {
            log.info("증빙 유형 접근 거부: evidenceTypeId={}, userId={}, role={}",
                    evidenceTypeId, principal.getUserId(), principal.getRole());
            throw new AccessDeniedException("해당 증빙에 접근 권한이 없습니다.");
        }
    }

    @Transactional(readOnly = true)
    public void assertCanAccessFile(Long fileId, UserPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("인증 정보가 없습니다.");
        }
        if (isAdmin(principal)) return;

        EvidenceFile file = evidenceFileRepository.findById(fileId)
                .orElseThrow(() -> new AccessDeniedException("해당 파일에 접근 권한이 없습니다."));

        if (!isOwnerWithPermission(file.getEvidenceType(), principal)) {
            log.info("증빙 파일 접근 거부: fileId={}, userId={}, role={}",
                    fileId, principal.getUserId(), principal.getRole());
            throw new AccessDeniedException("해당 파일에 접근 권한이 없습니다.");
        }
    }

    // ------------------------------------------------------------------
    // 내부 헬퍼
    // ------------------------------------------------------------------

    public boolean isAdmin(UserPrincipal principal) {
        return principal != null && "admin".equalsIgnoreCase(principal.getRole());
    }

    /**
     * EvidenceType 의 소유자이고 permission_evidence=true 인지 확인.
     * User 엔티티를 조회해 permission_evidence 플래그를 확인한다 (JWT 에는 없음).
     */
    private boolean isOwnerWithPermission(EvidenceType et, UserPrincipal principal) {
        // 소유자 매칭
        User owner = et.getOwnerUser();
        if (owner == null) return false;
        if (!owner.getId().equals(principal.getUserId())) return false;

        // permission_evidence 플래그 확인 (DB 조회)
        return userRepository.findById(principal.getUserId())
                .map(User::getPermissionEvidence)
                .orElse(false);
    }
}