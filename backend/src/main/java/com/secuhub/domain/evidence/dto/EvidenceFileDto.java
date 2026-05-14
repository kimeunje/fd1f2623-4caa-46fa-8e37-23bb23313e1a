package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.EvidenceAsset;
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

    /**
     * 기존 asset link 요청 (v18.6a 신규) — POST /evidence-files/link 의 body shape.
     *
     * <p>화면 mockup 의 [기존 파일에서 선택] 흐름 → 사용자가 검색 다이얼로그에서 asset 선택
     * → 본 request 로 link 생성 (multipart 재전송 없음).</p>
     *
     * <p>{@code fileName} 정책 (Q6 = link 단위 보존):</p>
     * <ul>
     *   <li>null 또는 빈 문자열 → asset.originalFileName 사용</li>
     *   <li>명시값 → 본 link 의 EvidenceFile.fileName 으로 보존 (asset 의 다른 link 와 별개)</li>
     * </ul>
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkRequest {
        private Long evidenceTypeId;
        private Long assetId;
        private String fileName;
        private String submitNote;
    }

    /**
     * v11 Phase 5-2 + 5-4 — 증빙 파일 응답 DTO.
     *
     * <h3>v15 Phase 5-15c (v15.7) — Q7-narrow 결정 적용 비대상</h3>
     * <p>JSON 필드 {@code controlCode} / {@code controlName} 은 v15.7 Q7-narrow
     * 결정에 의해 보존 (HANDOFF v15.7 §E 의 명시 범위 = MyTaskItem 만). 향후 wire shape
     * 일관성 확장 시 별도 phase 에서 {@code nodeCode} / {@code nodeName} 으로 rename
     * 검토. 단 {@link #from(EvidenceFile)} 안의 자바 측 {@code getControl()} 호출은
     * Q1=B 정합으로 {@code getControlNode()} 로 일괄 변경됨.</p>
     *
     * <h3>v18.6a — assetId 추가</h3>
     * <p>{@code asset_id} FK 가 있으면 매핑된 asset 의 id, 없으면 null (transitional,
     * 옛 데이터). v18.6b 마이그레이션 후 모든 row 가 not-null.</p>
     */
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

        /**
         * v18.6a — Evidence Asset 신규 채널. asset_id 있으면 매핑된 asset, 없으면 null
         * (transitional, 옛 데이터 또는 옛 filePath 직접 등록).
         */
        private Long assetId;

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
                    // v15.7 Q1=B: getControl() → getControlNode() (자바 측 cascade)
                    .controlCode(entity.getEvidenceType().getControlNode().getCode())
                    .controlName(entity.getEvidenceType().getControlNode().getName())
                    // v18.6a — asset 매핑 (없으면 null)
                    .assetId(entity.getAsset() != null ? entity.getAsset().getId() : null)
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

    /**
     * 파일 업로드 응답 (v18.6a 신규) — POST /evidence-files/upload 의 통합 응답 shape.
     *
     * <p>응답 status 분기 (Q1=b / Q4=a):</p>
     * <ul>
     *   <li>{@code "created"} — 정상 신규 등록 ({@code forceUpload=true} 또는 sha256 unique)</li>
     *   <li>{@code "duplicate_detected"} — 같은 sha256 발견, link 미생성. FE 가 confirm
     *       dialog 노출 → 사용자 선택 후 POST /link (기존 사용) 또는
     *       POST /upload?forceUpload=true (새로 등록) 재호출</li>
     * </ul>
     *
     * <p>FE 의 분기: {@code response.status === 'duplicate_detected' ?
     * showConfirmDialog(response.existingAsset) : ...}. L_FE_BE_SYNC_GREP 정합.</p>
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadResponse {
        private String status;
        private Response evidenceFile;
        private EvidenceAssetDto.Response existingAsset;

        public static UploadResponse created(EvidenceFile file) {
            return UploadResponse.builder()
                    .status("created")
                    .evidenceFile(Response.from(file))
                    .build();
        }

        public static UploadResponse duplicateDetected(EvidenceAsset asset, long usedInCount) {
            return UploadResponse.builder()
                    .status("duplicate_detected")
                    .existingAsset(EvidenceAssetDto.Response.from(asset, usedInCount))
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