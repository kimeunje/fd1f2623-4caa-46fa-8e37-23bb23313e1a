package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.Control;
import com.secuhub.domain.evidence.entity.EvidenceType;
import lombok.*;

import java.util.List;

public class ControlDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @jakarta.validation.constraints.NotBlank(message = "통제항목 코드는 필수입니다.")
        private String code;
        private String domain;
        @jakarta.validation.constraints.NotBlank(message = "통제항목 이름은 필수입니다.")
        private String name;
        private String description;
        private List<EvidenceTypeRequest> evidenceTypes;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String code;
        private String domain;
        private String name;
        private String description;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceTypeRequest {
        @jakarta.validation.constraints.NotBlank(message = "증빙 유형 이름은 필수입니다.")
        private String name;
        private String description;
    }

    /**
     * 통제항목 목록/생성/수정 응답 DTO.
     *
     * <p>v11 Phase 5-9 에서 Framework 상세 페이지의 행 단위 "검토 대기 N건" 배지를 위해
     * {@code pendingReviewCount} 필드를 추가했다. FrameworkDto.Response 와 동일한 규약:
     * 집계가 필요 없는 경로(생성/수정 직후)에서는 0 으로 채워진다.</p>
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private Long frameworkId;
        private String code;
        private String domain;
        private String name;
        private String description;
        private int evidenceTotal;
        private int evidenceCollected;
        private String status;
        private String createdAt;

        // v11 Phase 5-9 — 통제항목 행 단위 검토 대기 배지
        private long pendingReviewCount;

        /**
         * 기존 2-인자 팩토리 유지 (생성/수정 경로 호환). pendingReviewCount=0 으로 채움.
         */
        public static Response from(Control entity, int collected) {
            return from(entity, collected, 0L);
        }

        /**
         * v11 Phase 5-9 — 집계 포함 팩토리. 목록 조회에서 사용.
         */
        public static Response from(Control entity, int collected, long pendingReviewCount) {
            int total = entity.getEvidenceTypes() != null ? entity.getEvidenceTypes().size() : 0;
            String status;
            if (total == 0) status = "미수집";
            else if (collected >= total) status = "완료";
            else if (collected > 0) status = "진행중";
            else status = "미수집";

            return Response.builder()
                    .id(entity.getId())
                    .frameworkId(entity.getFramework().getId())
                    .code(entity.getCode())
                    .domain(entity.getDomain())
                    .name(entity.getName())
                    .description(entity.getDescription())
                    .evidenceTotal(total)
                    .evidenceCollected(collected)
                    .status(status)
                    .createdAt(entity.getCreatedAt() != null ?
                            entity.getCreatedAt().toString() : null)
                    .pendingReviewCount(pendingReviewCount)
                    .build();
        }
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class DetailResponse {
        private Long id;
        private Long frameworkId;
        private String code;
        private String domain;
        private String name;
        private String description;
        private int evidenceTotal;
        private int evidenceCollected;
        private String status;
        private List<EvidenceTypeResponse> evidenceTypes;
        private String createdAt;
    }

    /**
     * 증빙 유형 응답 DTO.
     *
     * <p>v11 Phase 5-12 (백엔드 owner DTO 보강, 2026-04) — 담당자/마감일 정보를 노출하여
     * 프론트 상세 페이지(EvidenceTypeDetailView)의 "담당자 미지정"이 실제 이름으로 표시되도록
     * 한다. 프론트 타입(EvidenceTypeResponse)은 Phase 5-12 에서 옵셔널로 미리 준비됨.</p>
     *
     * <p>ownerUser / dueDate 는 모두 nullable. 미배정 증빙 유형도 정상 동작하도록 null-safe
     * 매핑한다. EvidenceType.ownerUser 는 LAZY 페치이므로 이 DTO 변환은 반드시
     * {@code @Transactional} 내부에서 이뤄져야 한다 (현재 ControlService.findDetail 이
     * 해당 조건을 충족).</p>
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class EvidenceTypeResponse {
        private Long id;
        private String name;
        private String description;
        private boolean collected;
        private List<EvidenceFileDto.Response> files;

        // v11 Phase 5-12 — 담당자·마감일
        private Long ownerUserId;
        private String ownerUserName;
        private String ownerUserTeam;
        private String dueDate;

        public static EvidenceTypeResponse from(EvidenceType et, List<EvidenceFileDto.Response> files) {
            return EvidenceTypeResponse.builder()
                    .id(et.getId())
                    .name(et.getName())
                    .description(et.getDescription())
                    .collected(files != null && !files.isEmpty())
                    .files(files)
                    // 담당자 (null-safe)
                    .ownerUserId(et.getOwnerUser() != null ? et.getOwnerUser().getId() : null)
                    .ownerUserName(et.getOwnerUser() != null ? et.getOwnerUser().getName() : null)
                    .ownerUserTeam(et.getOwnerUser() != null ? et.getOwnerUser().getTeam() : null)
                    // 마감일 (LocalDate → String, null-safe)
                    .dueDate(et.getDueDate() != null ? et.getDueDate().toString() : null)
                    .build();
        }
    }
}