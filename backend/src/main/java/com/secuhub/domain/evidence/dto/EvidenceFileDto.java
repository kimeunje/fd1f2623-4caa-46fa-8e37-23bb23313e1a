package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.EvidenceFile;
import lombok.*;

public class EvidenceFileDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadRequest {
        @jakarta.validation.constraints.NotNull(message = "증빙 유형 ID는 필수입니다.")
        private Long evidenceTypeId;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Response {
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
}
