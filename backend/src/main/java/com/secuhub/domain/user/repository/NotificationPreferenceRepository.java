package com.secuhub.domain.user.repository;

import com.secuhub.domain.user.entity.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * NotificationPreference Repository (v11 신규)
 *
 * PK 가 userId 이므로 findById(userId) 로 조회.
 */
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {
}