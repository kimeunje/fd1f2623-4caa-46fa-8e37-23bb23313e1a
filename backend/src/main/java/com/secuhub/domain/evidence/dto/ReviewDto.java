package com.secuhub.domain.evidence.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * v19.25 — 심사원(reviewer) 전용 응답 DTO.
 *
 * <p><b>DTO 단계 제외 원칙</b>: 관리자 트리 응답(TreeDto)과 달리, 심사원이 보는 것은
 * "관리 항목 + 항목별 최신 승인 파일 + 다운로드" 뿐이다. 스크립트·수집 작업·파일 이력·버전
 * 목록·인수인계 노트·승인 이력 필드는 <b>FE 숨김이 아니라 이 DTO 단계에서 아예 제외</b>한다
 * (심사원 API 는 관리자 API 와 물리적으로 분리 — {@code ReviewController} 참조).</p>
 *
 * <p>트리는 관리자 응답과 동일하게 <b>평탄화(flat) + parentId</b> 형태로 내려주고 FE 가 재구성한다.
 * 각 노드는 자신에게 매달린 증빙 유형 목록을 갖고, 각 증빙 유형은 최신 승인 파일 1건(없으면 null)을 갖는다.</p>
 */
public class ReviewDto {

    private ReviewDto() {}

    /** 심사원 랜딩의 프레임워크 선택 목록 항목 (active 만). */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class FrameworkSummary {
        private Long id;
        private String name;
    }

    /** {@code GET /api/v1/review/frameworks/{id}/tree} 응답. */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class TreeResponse {
        private FrameworkSummary framework;
        /** depth ASC, displayOrder ASC 로 정렬된 평탄화 노드 목록. */
        private List<Node> nodes;
    }

    /**
     * 트리 노드(평탄화). {@code parentId} 로 FE 가 계층 재구성.
     * hybrid 모델 정합 — nodeType 구분 없이, 증빙 유형이 매달린 노드면 {@code evidenceTypes} 가 채워진다.
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class Node {
        private Long id;
        private Long parentId;      // 루트는 null
        private String code;
        private String name;
        private Integer depth;
        /** 이 노드에 매달린 증빙 유형(없으면 빈 리스트). */
        private List<EvidenceTypeView> evidenceTypes;
    }

    /** 증빙 유형 + 최신 승인 파일 1건. */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class EvidenceTypeView {
        private Long id;
        private String name;
        /** 최신 승인 파일(approved/auto_approved 중 MAX version). 없으면 null → FE 다운로드 버튼 미표시. */
        private FileView latestFile;
    }

    /** 다운로드 대상 파일의 최소 식별자. 버전·크기·수집일 등 이력은 심사원에게 미노출. */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class FileView {
        private Long id;
        private String fileName;
    }
}