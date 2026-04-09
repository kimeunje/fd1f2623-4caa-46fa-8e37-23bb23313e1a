package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.EvidenceFile;
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
