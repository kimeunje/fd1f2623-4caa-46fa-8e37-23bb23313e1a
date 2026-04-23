package com.secuhub.domain.mytasks.service;

import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.config.jwt.UserPrincipal;
import com.secuhub.domain.evidence.entity.EvidenceFile;
import com.secuhub.domain.evidence.entity.EvidenceType;
import com.secuhub.domain.evidence.entity.ReviewStatus;
import com.secuhub.domain.evidence.repository.EvidenceFileRepository;
import com.secuhub.domain.evidence.repository.EvidenceTypeRepository;
import com.secuhub.domain.mytasks.dto.MyTasksDto;
import com.secuhub.domain.user.entity.User;
import com.secuhub.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 담당자 "내 할 일" 서비스 (Phase 5-5 신규).
 *
 * <h3>권한 규칙</h3>
 * <ul>
 *   <li>인증 필요 — 미인증 시 {@link AccessDeniedException} </li>
 *   <li>{@code permission_evidence=true} 여야 함 — false 면 403</li>
 *   <li>admin 이어도 본인이 {@code owner_user_id} 로 지정된 증빙 유형만 반환
 *       (admin 에게 전체 증빙을 보여주면 기획서의 "담당자 전용" 의도와 어긋나므로)</li>
 * </ul>
 *
 * <h3>섹션 분류 규칙 (최신 파일 기준)</h3>
 * <pre>
 * for et in 본인 소유 evidence_types:
 *   latest = max version 파일
 *   if latest == null:
 *     if dueDate != null AND daysUntilDue <= 7:
 *       → dueSoon       (음수 D-day 포함: 지났어도 여전히 마감임박 섹션에서 강조)
 *     else:
 *       → notSubmitted
 *   else:
 *     switch latest.reviewStatus:
 *       rejected        → rejected
 *       pending         → inReview
 *       approved        → completed
 *       auto_approved   → completed
 * </pre>
 *
 * <p>완료 섹션은 최근 승인순으로 정렬 후 <b>10건 제한</b>. 전체 카운트는 별도 필드로 제공.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyTasksService {

    public static final int COMPLETED_SECTION_LIMIT = 10;
    public static final int DUE_SOON_WINDOW_DAYS = 7;

    private final UserRepository userRepository;
    private final EvidenceTypeRepository evidenceTypeRepository;
    private final EvidenceFileRepository evidenceFileRepository;

    /**
     * 테스트에서 시간 고정을 위해 주입 가능한 Clock.
     * 기본은 시스템 디폴트 (운영).
     */
    private Clock clock = Clock.systemDefaultZone();

    /** 테스트 전용 setter. */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 목록 — 5섹션 묶음 응답
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MyTasksDto.Response findMyTasks(UserPrincipal principal) {
        User user = requirePermittedUser(principal);

        List<EvidenceType> myTypes = evidenceTypeRepository.findByOwnerUserId(user.getId());
        LocalDate today = LocalDate.now(clock);

        List<MyTasksDto.Item> rejected = new ArrayList<>();
        List<MyTasksDto.Item> dueSoon = new ArrayList<>();
        List<MyTasksDto.Item> notSubmitted = new ArrayList<>();
        List<MyTasksDto.Item> inReview = new ArrayList<>();
        List<MyTasksDto.Item> completedAll = new ArrayList<>();

        for (EvidenceType et : myTypes) {
            EvidenceFile latest = findLatestFile(et.getId());
            MyTasksDto.Item.ItemBuilder b = MyTasksDto.Item.baseBuilder(et, today);

            if (latest == null) {
                if (isDueSoon(et.getDueDate(), today)) {
                    dueSoon.add(b.build());
                } else {
                    notSubmitted.add(b.build());
                }
                continue;
            }

            MyTasksDto.Item.withLatestFile(b, latest);
            ReviewStatus status = latest.getReviewStatus();

            if (status == ReviewStatus.rejected) {
                rejected.add(b.build());
            } else if (status == ReviewStatus.pending) {
                inReview.add(b.build());
            } else if (status == ReviewStatus.approved || status == ReviewStatus.auto_approved) {
                completedAll.add(b.build());
            } else {
                // 예외적으로 reviewStatus 가 null 인 레거시 데이터 — 완료로 취급
                completedAll.add(b.build());
            }
        }

        // 정렬
        // 반려: 반려 시각 최신순
        rejected.sort(Comparator.comparing(MyTasksDto.Item::getReviewedAt, nullsLast()).reversed());
        // 마감 임박: dueDate 빠른순 (daysUntilDue 가 작을수록 먼저)
        dueSoon.sort(Comparator.comparing(MyTasksDto.Item::getDaysUntilDue, nullsLast()));
        // 미제출: dueDate 있으면 빠른순, 없으면 뒤로
        notSubmitted.sort(Comparator.comparing(MyTasksDto.Item::getDaysUntilDue, nullsLast()));
        // 검토중: 제출 시각 오래된순 (먼저 검토되어야 하는 것이 위)
        inReview.sort(Comparator.comparing(MyTasksDto.Item::getSubmittedAt, nullsLast()));
        // 완료: 최근 승인순
        completedAll.sort(Comparator.comparing(MyTasksDto.Item::getReviewedAt, nullsLast()).reversed());

        List<MyTasksDto.Item> completedLimited = completedAll.size() > COMPLETED_SECTION_LIMIT
                ? completedAll.subList(0, COMPLETED_SECTION_LIMIT)
                : completedAll;

        MyTasksDto.Counts counts = MyTasksDto.Counts.builder()
                .rejected(rejected.size())
                .dueSoon(dueSoon.size())
                .notSubmitted(notSubmitted.size())
                .inReview(inReview.size())
                .completed(completedAll.size())   // 총 건수 (limit 과 무관)
                .build();

        return MyTasksDto.Response.builder()
                .rejected(rejected)
                .dueSoon(dueSoon)
                .notSubmitted(notSubmitted)
                .inReview(inReview)
                .completed(completedLimited)
                .counts(counts)
                .build();
    }

    // ------------------------------------------------------------------
    // 상세 — 재제출 페이지용
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MyTasksDto.DetailResponse findMyTaskDetail(Long evidenceTypeId, UserPrincipal principal) {
        User user = requirePermittedUser(principal);

        EvidenceType et = evidenceTypeRepository.findById(evidenceTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("증빙 유형", evidenceTypeId));

        // 소유자 검증 — 본인 담당이 아니면 403
        if (et.getOwnerUser() == null || !et.getOwnerUser().getId().equals(user.getId())) {
            log.info("내 할 일 상세 접근 거부: evidenceTypeId={}, userId={}",
                    evidenceTypeId, user.getId());
            throw new AccessDeniedException("해당 증빙에 접근 권한이 없습니다.");
        }

        LocalDate today = LocalDate.now(clock);
        List<EvidenceFile> files = evidenceFileRepository
                .findByEvidenceTypeIdOrderByVersionDesc(evidenceTypeId);

        String currentStatus;
        String rejectReason = null;
        String rejectedByName = null;
        String rejectedAt = null;

        if (files.isEmpty()) {
            currentStatus = "not_submitted";
        } else {
            EvidenceFile latest = files.get(0);
            currentStatus = latest.getReviewStatus() != null
                    ? latest.getReviewStatus().name()
                    : "unknown";

            if (latest.getReviewStatus() == ReviewStatus.rejected) {
                rejectReason = latest.getReviewNote();
                rejectedByName = latest.getReviewedBy() != null ? latest.getReviewedBy().getName() : null;
                rejectedAt = latest.getReviewedAt() != null ? latest.getReviewedAt().toString() : null;
            }
        }

        List<MyTasksDto.FileHistoryEntry> history = files.stream()
                .map(MyTasksDto.FileHistoryEntry::from)
                .toList();

        Long daysUntilDue = null;
        String dueStr = null;
        if (et.getDueDate() != null) {
            dueStr = et.getDueDate().toString();
            daysUntilDue = java.time.temporal.ChronoUnit.DAYS.between(today, et.getDueDate());
        }

        return MyTasksDto.DetailResponse.builder()
                .evidenceTypeId(et.getId())
                .evidenceTypeName(et.getName())
                .description(et.getDescription())
                .controlId(et.getControl() != null ? et.getControl().getId() : null)
                .controlCode(et.getControl() != null ? et.getControl().getCode() : null)
                .controlName(et.getControl() != null ? et.getControl().getName() : null)
                .frameworkId(et.getControl() != null && et.getControl().getFramework() != null
                        ? et.getControl().getFramework().getId() : null)
                .frameworkName(et.getControl() != null && et.getControl().getFramework() != null
                        ? et.getControl().getFramework().getName() : null)
                .dueDate(dueStr)
                .daysUntilDue(daysUntilDue != null ? daysUntilDue.intValue() : null)
                .currentStatus(currentStatus)
                .rejectReason(rejectReason)
                .rejectedByName(rejectedByName)
                .rejectedAt(rejectedAt)
                .history(history)
                .build();
    }

    // ------------------------------------------------------------------
    // 내부 헬퍼
    // ------------------------------------------------------------------

    /**
     * 인증 + permission_evidence 체크. 두 조건 중 하나라도 실패 시 AccessDenied.
     * admin 이어도 permission_evidence=false 면 차단하지 않음 — 운영 계정 안전성을 위해
     * admin 은 DB 플래그와 무관하게 항상 허용한다. (기획서 §4.1: admin 전체 권한)
     */
    private User requirePermittedUser(UserPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("인증 정보가 없습니다.");
        }
        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new AccessDeniedException("인증된 사용자를 찾을 수 없습니다."));

        boolean isAdmin = "admin".equalsIgnoreCase(principal.getRole());
        if (!isAdmin && !Boolean.TRUE.equals(user.getPermissionEvidence())) {
            log.info("내 할 일 접근 거부 (permission_evidence=false): userId={}", user.getId());
            throw new AccessDeniedException("증빙 수집 권한이 없습니다.");
        }
        return user;
    }

    private EvidenceFile findLatestFile(Long evidenceTypeId) {
        List<EvidenceFile> files = evidenceFileRepository
                .findByEvidenceTypeIdOrderByVersionDesc(evidenceTypeId);
        return files.isEmpty() ? null : files.get(0);
    }

    private boolean isDueSoon(LocalDate dueDate, LocalDate today) {
        if (dueDate == null) return false;
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, dueDate);
        return days <= DUE_SOON_WINDOW_DAYS;
    }

    private static <T extends Comparable<? super T>> Comparator<T> nullsLast() {
        return Comparator.nullsLast(Comparator.naturalOrder());
    }
}