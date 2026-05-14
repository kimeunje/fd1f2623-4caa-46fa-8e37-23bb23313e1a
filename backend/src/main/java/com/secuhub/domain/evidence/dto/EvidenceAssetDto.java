package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.EvidenceAsset;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * EvidenceAsset 의 DTO 모음. v18.6a 신규.
 *
 * <h3>중첩 클래스</h3>
 * <ul>
 *   <li>{@link Response} — 검색 결과 + 단건 조회 응답 shape</li>
 *   <li>{@link SearchRequest} — 검색 요청 (controller @RequestParam 묶음)</li>
 *   <li>{@link DuplicateDetectedResponse} — POST /evidence-files/upload 의
 *       중복 감지 응답 분기 (Q1=b)</li>
 * </ul>
 *
 * <h3>본 프로젝트 DTO 컨벤션 정합</h3>
 * <p>{@code EvidenceFileDto} / {@code ControlDto} 등 다른 DTO 와 같이 nested static
 * class 형식. v16.4a 의 L_ENTITY_TYPE_GREP 응용 — entity 필드 ↔ DTO 필드 1:1 매핑
 * 검증 + v16.4b FE 응용 (TypeScript 인터페이스 정합) 의 BE 측.</p>
 */
public class EvidenceAssetDto {

    /**
     * 검색 결과 + 단건 조회 응답.
     *
     * <p>{@code usedInCount} (Q11): 본 asset 을 참조하는 EvidenceFile (link) 의 수.
     * 화면 mockup 의 "사용 중 N 항목" 라벨에 사용. Service 가 batch query 로 채움
     * (N+1 회피, {@code EvidenceAssetRepository.countLinkedFilesByAssetIds}).</p>
     *
     * <p>{@code uploadedByName}: User entity 의 name 컬럼 (display). uploadedBy
     * SET NULL 상태 (Flyway V2 정책, 첫 업로더 user 삭제) 인 경우 null.</p>
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private String sha256;
        private String originalFileName;
        private Long fileSize;
        private Long uploadedByUserId;
        private String uploadedByName;
        private LocalDateTime createdAt;
        private Long usedInCount;

        /**
         * Entity + usedInCount 를 Response 로 매핑.
         *
         * <p>호출 측 (Service) 책임: usedInCount 를 batch query 로 사전 계산.
         * 본 메서드는 단순 매핑만 (추가 DB 호출 없음).</p>
         */
        public static Response from(EvidenceAsset asset, long usedInCount) {
            return Response.builder()
                    .id(asset.getId())
                    .sha256(asset.getSha256())
                    .originalFileName(asset.getOriginalFileName())
                    .fileSize(asset.getFileSize())
                    .uploadedByUserId(asset.getUploadedBy() != null ? asset.getUploadedBy().getId() : null)
                    .uploadedByName(asset.getUploadedBy() != null ? asset.getUploadedBy().getName() : null)
                    .createdAt(asset.getCreatedAt())
                    .usedInCount(usedInCount)
                    .build();
        }
    }

    /**
     * 검색 요청 — query string params 묶음.
     *
     * <p>Controller 에서 @RequestParam 으로 개별 파라미터 받은 후 본 DTO 로 묶어 service 에 전달.</p>
     *
     * <p>정규화 정책 (service 측):</p>
     * <ul>
     *   <li>{@code query == null} → "" (빈 문자열, 검색어 무시)</li>
     *   <li>{@code query} trim 적용</li>
     *   <li>{@code uploaderId / fromDate / toDate} 는 null 허용 — repository 가
     *       {@code IS NULL OR ...} 분기 처리</li>
     * </ul>
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchRequest {
        private String query;
        private Long uploaderId;
        private LocalDateTime fromDate;
        private LocalDateTime toDate;
    }

    /**
     * 중복 감지 응답 — POST /evidence-files/upload 의 응답 분기 (Q1=b / Q4=a 정합).
     *
     * <p>응답 status 분기:</p>
     * <ul>
     *   <li>{@code "duplicate_detected"} — 같은 sha256 의 기존 asset 발견. FE 가 confirm
     *       dialog 노출 후 [기존 사용] (POST /evidence-files/link) 또는 [새로 등록]
     *       (POST /evidence-files/upload?forceUpload=true) 호출 — Chat 2B 의
     *       EvidenceFileController 에서 정의</li>
     *   <li>{@code "created"} — 정상 신규 등록 (응답 shape 는
     *       {@code EvidenceFileDto.UploadResponse}, Chat 2B 에서 정의)</li>
     * </ul>
     *
     * <p>본 클래스는 {@code status="duplicate_detected"} 시점만. {@code status="created"} 는
     * Chat 2B 의 EvidenceFileDto 참조.</p>
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DuplicateDetectedResponse {
        private String status;
        private Response existingAsset;

        /**
         * Entity 와 usedInCount 로 duplicate detected 응답 조립.
         */
        public static DuplicateDetectedResponse of(EvidenceAsset existing, long usedInCount) {
            return DuplicateDetectedResponse.builder()
                    .status("duplicate_detected")
                    .existingAsset(Response.from(existing, usedInCount))
                    .build();
        }
    }
}