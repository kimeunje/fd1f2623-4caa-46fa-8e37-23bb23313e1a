package com.secuhub.domain.user.entity;

import com.secuhub.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 사용자별 이메일 알림 설정 (v11 신규)
 *
 * User 와 1:1 관계. PK = user_id = users.id (MapsId).
 * 레코드가 없으면 "모두 기본값(true, 일일요약만 false)"으로 간주.
 */
@Entity
@Table(name = "notification_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class NotificationPreference extends BaseEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)   // dev/test의 ddl-auto: create 환경에서도 ON DELETE CASCADE 보장 (V2 SQL과 의미 일치)
    private User user;

    @Column(name = "email_on_rejection", nullable = false)
    @Builder.Default
    private Boolean emailOnRejection = true;

    @Column(name = "email_on_approval", nullable = false)
    @Builder.Default
    private Boolean emailOnApproval = true;

    @Column(name = "email_on_new_assignment", nullable = false)
    @Builder.Default
    private Boolean emailOnNewAssignment = true;

    @Column(name = "email_on_due_reminder", nullable = false)
    @Builder.Default
    private Boolean emailOnDueReminder = true;

    @Column(name = "email_daily_digest", nullable = false)
    @Builder.Default
    private Boolean emailDailyDigest = false;

    public void update(Boolean emailOnRejection,
                       Boolean emailOnApproval,
                       Boolean emailOnNewAssignment,
                       Boolean emailOnDueReminder,
                       Boolean emailDailyDigest) {
        if (emailOnRejection != null) this.emailOnRejection = emailOnRejection;
        if (emailOnApproval != null) this.emailOnApproval = emailOnApproval;
        if (emailOnNewAssignment != null) this.emailOnNewAssignment = emailOnNewAssignment;
        if (emailOnDueReminder != null) this.emailOnDueReminder = emailOnDueReminder;
        if (emailDailyDigest != null) this.emailDailyDigest = emailDailyDigest;
    }
}