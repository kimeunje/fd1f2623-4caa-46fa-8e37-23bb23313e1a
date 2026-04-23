package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.EvidenceFile;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.springframework.core.io.Resource;

public class EvidenceFileDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadRequest {
        @jakarta.validation.constraints.NotNull(message = "증빙 유형 ID는 필수입니다.")
        private Long evidenceTypeId;
    }

    /**
     * 승인 요청 DTO (Phase 5-4 신규)
     * reviewNote 는 선택. 제공되지 않으면 null 로 저장.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApproveRequest {
        private String reviewNote;
    }

    /**
     * 반려 요청 DTO (Phase 5-4 신규)
     * reviewNote 필수 — 빈 값이면 400 (GlobalExceptionHandler 가 변환).
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RejectRequest {
        @NotBlank(message = "반려 사유는 필수입니다.")
        private String reviewNote;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Response {
        // 기본 메타데이터
        private Long id;
        private Long evidenceTypeId;
        private String evidenceTypeName;
        private String controlCode;
        private String controlName;
        private String fileName;
        private String filePath;
        private Long fileSize;
        private Integer version;
        private String collectionMethod;
        private String collectedAt;
        private String createdAt;

        // v11 Phase 5-4: 업로더 정보
        private Long uploadedById;
        private String uploadedByName;
        private String submitNote;

        // v11 Phase 5-4: 검토 상태
        private String reviewStatus;         // pending / approved / rejected / auto_approved
        private Long reviewedById;
        private String reviewedByName;
        private String reviewNote;
        private String reviewedAt;

        public static Response from(EvidenceFile entity) {
            return Response.builder()
                    .id(entity.getId())
                    .evidenceTypeId(entity.getEvidenceType().getId())
                    .evidenceTypeName(entity.getEvidenceType().getName())
                    .controlCode(entity.getEvidenceType().getControl().getCode())
                    .controlName(entity.getEvidenceType().getControl().getName())
                    .fileName(entity.getFileName())
                    .filePath(entity.getFilePath())
                    .fileSize(entity.getFileSize())
                    .version(entity.getVersion())
                    .collectionMethod(entity.getCollectionMethod().name())
                    .collectedAt(entity.getCollectedAt() != null ? entity.getCollectedAt().toString() : null)
                    .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null)
                    // 업로더 (null-safe)
                    .uploadedById(entity.getUploadedBy() != null ? entity.getUploadedBy().getId() : null)
                    .uploadedByName(entity.getUploadedBy() != null ? entity.getUploadedBy().getName() : null)
                    .submitNote(entity.getSubmitNote())
                    // 검토 상태 (null-safe)
                    .reviewStatus(entity.getReviewStatus() != null ? entity.getReviewStatus().name() : null)
                    .reviewedById(entity.getReviewedBy() != null ? entity.getReviewedBy().getId() : null)
                    .reviewedByName(entity.getReviewedBy() != null ? entity.getReviewedBy().getName() : null)
                    .reviewNote(entity.getReviewNote())
                    .reviewedAt(entity.getReviewedAt() != null ? entity.getReviewedAt().toString() : null)
                    .build();
        }
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class StatsResponse {
        private long totalFiles;
        private long quarterFiles;
        private long totalSizeBytes;
        private int controlCoverage;
    }

    /**
     * 파일 다운로드 응답 DTO
     *
     * Controller에서 Resource와 메타정보를 함께 전달받아
     * Content-Disposition 헤더와 Content-Type을 정확하게 설정합니다.
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class DownloadResponse {
        private Resource resource;
        private String fileName;
        private String contentType;
        private Long fileSize;
    }
}