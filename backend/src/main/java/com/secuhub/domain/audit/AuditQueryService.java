package com.secuhub.domain.audit;

import com.secuhub.domain.audit.dto.AuditLogPageResponse;
import com.secuhub.domain.audit.dto.AuditLogResponse;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 감사 로그 조회 (AUDIT-2, admin 전용). 기록(AuditService)과 책임 분리.
 *
 * <p>필터: keyword(이메일/IP/대상명 부분일치) / action / result / 기간(from~to) — 모두 optional, 조합 가능.
 * 정렬 고정 created_at DESC. 페이지네이션(page/size, size 상한 {@value #MAX_SIZE}).</p>
 *
 * <p>v19.14 — actorUserId 단일 필터를 통합 검색어(keyword)로 교체. actorEmail / clientIp /
 * targetName 에 대해 대소문자 무시 LIKE.</p>
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    static final int MAX_SIZE = 100;
    private static final int DEFAULT_SIZE = 20;

    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public AuditLogPageResponse search(String keyword, AuditAction action, AuditResult result,
                                       LocalDateTime from, LocalDateTime to, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<AuditLog> result_ =
                auditLogRepository.findAll(buildSpec(keyword, action, result, from, to), pageable);

        List<AuditLogResponse> content = result_.getContent().stream()
                .map(AuditLogResponse::from)
                .toList();

        return new AuditLogPageResponse(
                content,
                result_.getNumber(),
                result_.getSize(),
                result_.getTotalElements(),
                result_.getTotalPages(),
                result_.hasNext());
    }

    /** optional 필터 조합 → Criteria Specification (null 안전, enum 안전). */
    private Specification<AuditLog> buildSpec(String keyword, AuditAction action, AuditResult result,
                                              LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                // 이메일 / IP / 대상명(파일명 등) 부분일치 (대소문자 무시).
                // LIKE 대상이 NULL 인 행은 해당 disjunct 에서 자연 제외(에러 없음).
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("actorEmail")), like),
                        cb.like(cb.lower(root.get("clientIp")), like),
                        cb.like(cb.lower(root.get("targetName")), like)
                ));
            }
            if (action != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (result != null) {
                predicates.add(cb.equal(root.get("result"), result));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}