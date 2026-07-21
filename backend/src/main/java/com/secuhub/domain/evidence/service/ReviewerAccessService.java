package com.secuhub.domain.evidence.service;

import com.secuhub.domain.evidence.entity.ReviewerFramework;
import com.secuhub.domain.evidence.repository.ReviewerFrameworkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * v19.25 — 심사원 프레임워크 배정 관리(admin 전용).
 *
 * <p>계정 설정에서 심사원에게 열어줄 프레임워크를 지정/변경한다. 배정은 replace-set 방식
 * (기존 전부 삭제 후 새 목록 삽입)이라 멱등적이다.</p>
 */
@Service
@RequiredArgsConstructor
public class ReviewerAccessService {

    private final ReviewerFrameworkRepository reviewerFrameworkRepository;

    @Transactional(readOnly = true)
    public List<Long> getAssignedFrameworkIds(Long userId) {
        return reviewerFrameworkRepository.findFrameworkIdsByUserId(userId);
    }

    /**
     * 배정 교체. null/중복 id 는 무시. 빈 목록이면 전부 해제(심사원이 아무것도 못 봄).
     */
    @Transactional
    public void setAssignedFrameworks(Long userId, List<Long> frameworkIds) {
        reviewerFrameworkRepository.deleteByUserId(userId);
        if (frameworkIds == null || frameworkIds.isEmpty()) {
            return;
        }
        frameworkIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .forEach(fid -> reviewerFrameworkRepository.save(
                        ReviewerFramework.builder().userId(userId).frameworkId(fid).build()));
    }
}