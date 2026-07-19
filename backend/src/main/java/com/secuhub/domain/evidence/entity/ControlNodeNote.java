package com.secuhub.domain.evidence.entity;

import com.secuhub.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * v19.27 — 관리 항목 인수인계 노트 (누적 로그).
 *
 * <p>관리 항목({@link ControlNode}) 당 여러 개의 노트를 시간순으로 누적한다. 기존
 * 관리자 이임 시 다음 관리자가 "이 항목의 증빙을 왜/어떻게 뽑았는지" 맥락을 인계받는
 * 용도. 편집 시 덮어쓰지 않고 새 노트가 쌓인다(1:N).</p>
 *
 * <h3>author_name — 로그인 계정과 무관한 수동 입력</h3>
 * <p>관리자 계정이 공용일 수 있어(여러 사람이 하나의 admin 계정 사용) 실제 작성자
 * 이름을 작성 시 직접 입력한다. FK 아님 — 작성 시점 이름을 그대로 박제.</p>
 *
 * <h3>CASCADE</h3>
 * <p>{@code @OnDelete(CASCADE)} — 관리 항목 삭제 시 매달린 노트 일괄 삭제.
 * EvidenceType.controlNode 와 동일 패턴(v18.3 L_SPEC_SCHEMA_MISMATCH 정합).
 * ddl-auto(dev/test) + Flyway {@code V_v19_27}(prod) 양쪽에서 동등 동작.</p>
 *
 * <h3>권한</h3>
 * <p>노트 CRUD 는 관리자 전용. 심사원(reviewer)은 인수인계 노트를 볼 수 없다 —
 * 내부 관리 맥락이므로 심사원 뷰(/review)에는 노출하지 않는다.</p>
 */
@Entity
@Table(name = "control_node_notes", indexes = {
        @Index(name = "idx_cnn_node", columnList = "control_node_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ControlNodeNote extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 소속 관리 항목. 항목 삭제 시 ON DELETE CASCADE 로 자동 삭제.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "control_node_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ControlNode controlNode;

    /**
     * 작성 시 직접 입력한 작성자 이름(박제). 로그인 계정과 무관.
     */
    @Column(name = "author_name", nullable = false, length = 100)
    private String authorName;

    /**
     * 노트 본문(마크다운).
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    /**
     * 노트 수정 — author_name / body 부분 수정 (null 이면 미변경).
     * 관리자 전용 화면에서만 호출되며, 오탈자 정정 등을 위해 작성자 이름도 수정 가능.
     */
    public void update(String authorName, String body) {
        if (authorName != null) this.authorName = authorName;
        if (body != null) this.body = body;
    }

    /** package-private — 양방향 없이 생성 시 소속 항목 지정용. */
    void setControlNode(ControlNode controlNode) {
        this.controlNode = controlNode;
    }
}