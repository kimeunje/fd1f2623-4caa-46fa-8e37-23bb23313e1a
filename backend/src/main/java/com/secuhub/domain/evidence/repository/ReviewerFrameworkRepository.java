package com.secuhub.domain.evidence.repository;

import com.secuhub.domain.evidence.entity.ReviewerFramework;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * v19.25 — 심사원 프레임워크 배정 영속화.
 */
public interface ReviewerFrameworkRepository extends JpaRepository<ReviewerFramework, Long> {

    /** 심사원에게 배정된 프레임워크 id 목록. */
    @Query("SELECT rf.frameworkId FROM ReviewerFramework rf WHERE rf.userId = :userId")
    List<Long> findFrameworkIdsByUserId(@Param("userId") Long userId);

    /** 스코프 검증 — 이 심사원이 해당 프레임워크를 열람할 수 있는가. */
    boolean existsByUserIdAndFrameworkId(Long userId, Long frameworkId);

    /**
     * 배정 교체(replace-set)의 사전 정리.
     *
     * <p><b>벌크 @Modifying DELETE 필수</b>: 파생 {@code deleteByUserId}(엔티티 로드 후 em.remove)는
     * 삭제가 커밋 시점으로 지연되는데, Hibernate 는 같은 flush 안에서 INSERT 를 DELETE 보다 먼저
     * 실행한다. 그러면 교체 저장 시 새 (user, framework) INSERT 가 옛 행 DELETE 보다 앞서 나가
     * {@code uq_reviewer_framework} unique 제약에 걸린다. 벌크 DML 은 호출 시점에 즉시 실행되어
     * 이 순서 문제를 피한다.</p>
     */
    @Modifying
    @Query("DELETE FROM ReviewerFramework rf WHERE rf.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}