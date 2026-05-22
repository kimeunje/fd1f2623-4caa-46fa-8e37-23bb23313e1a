package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.CollectionJob;
import com.secuhub.domain.evidence.entity.JobExecution;
import lombok.*;

import java.util.List;

/**
 * 수집 작업 DTO.
 *
 * <h3>v18.9 — 양방향 navigation 필드 추가</h3>
 * <p>{@link Response} / {@link DetailResponse} 에 EvidenceType 의 부모 사슬 정보 4 필드 노출:
 * {@code frameworkId / controlNodeId / controlNodeCode / controlNodeName}.</p>
 *
 * <p>FE JobsView 의 작업 row 가 pathline ("1.1.2 보호대책 요구사항 > 출입 기록 월간 보고서")
 * 표시 + 클릭 시 EvidenceTypeDetailView 의 route param
 * ({@code /controls/:frameworkId/:controlId/evidence-types/:evidenceTypeId}) 조립.</p>
 *
 * <p>명명 정합 — {@link com.secuhub.domain.mytasks.dto.MyTasksDto.Item} 의 wire shape rename (v15.7,
 * {@code controlId → nodeId / controlCode → nodeCode}) 와 비교 시 본 DTO 는 entity 명칭
 * {@code controlNode} 그대로 채택 ({@code controlNodeId / controlNodeCode / controlNodeName}).
 * 이유 — MyTasksDto 의 rename 은 노드 표시 일반화 의도였고, JobDto 는 도메인 관계 (Job → EvidenceType
 * → ControlNode) 명시가 더 자연. {@link com.secuhub.domain.evidence.dto.EvidenceFileDto.Response} 의
 * 옛 {@code controlCode/controlName} 명명은 v15.7 이전 산물 — 본 DTO 의 정합 기준으로 삼지 않음.</p>
 */
public class CollectionJobDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @jakarta.validation.constraints.NotBlank(message = "작업명은 필수입니다.")
        private String name;
        private String description;
        @jakarta.validation.constraints.NotBlank(message = "작업 유형은 필수입니다.")
        private String jobType;
        /** v18.8.2 — 신규 작업은 scriptId 활용 (script 신규 작성 또는 기존 선택). */
        private Long scriptId;
        /** legacy — 옛 script_path 작업도 보존 (Q2=A). scriptId 없을 때만 사용. */
        private String scriptPath;
        private Long evidenceTypeId;
        private String scheduleCron;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String name;
        private String description;
        /** v18.8.2 — scriptId 또는 scriptPath 갱신 (둘 중 하나만 의미). */
        private Long scriptId;
        private String scriptPath;
        private String scheduleCron;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private String name;
        private String description;
        private String jobType;
        /** v18.8.2 — Script entity id (NULL 이면 legacy scriptPath 활용). */
        private Long scriptId;
        /** legacy — scriptId NULL 시 fallback. */
        private String scriptPath;

        // ── EvidenceType 식별/표시 (옛부터) ──
        private Long evidenceTypeId;
        private String evidenceTypeName;

        // ── v18.9 — 양방향 navigation (FE pathline + router.push) ──
        /** route param {@code :frameworkId} 조립용. evidenceType 없거나 controlNode/framework null 시 NULL. */
        private Long frameworkId;
        /** route param {@code :controlId} 조립용 + pathline display. */
        private Long controlNodeId;
        /** pathline display ("1.1.2 보호대책 요구사항" 의 코드 부분). */
        private String controlNodeCode;
        /** pathline display ("1.1.2 보호대책 요구사항" 의 이름 부분). */
        private String controlNodeName;

        private String scheduleCron;
        private Boolean isActive;
        private ExecutionSummary lastExecution;
        private String createdAt;

        public static Response from(CollectionJob entity, JobExecution lastExec) {
            // v18.9 — controlNode / framework 안전 traverse (entity.evidenceType nullable, transitional)
            Long fwId = null;
            Long cnId = null;
            String cnCode = null;
            String cnName = null;
            if (entity.getEvidenceType() != null && entity.getEvidenceType().getControlNode() != null) {
                var cn = entity.getEvidenceType().getControlNode();
                cnId = cn.getId();
                cnCode = cn.getCode();
                cnName = cn.getName();
                if (cn.getFramework() != null) {
                    fwId = cn.getFramework().getId();
                }
            }

            return Response.builder()
                    .id(entity.getId())
                    .name(entity.getName())
                    .description(entity.getDescription())
                    .jobType(entity.getJobType().name())
                    .scriptId(entity.getScript() != null ? entity.getScript().getId() : null)
                    .scriptPath(entity.getScriptPath())
                    .evidenceTypeId(entity.getEvidenceType() != null ? entity.getEvidenceType().getId() : null)
                    .evidenceTypeName(entity.getEvidenceType() != null ? entity.getEvidenceType().getName() : null)
                    // v18.9
                    .frameworkId(fwId)
                    .controlNodeId(cnId)
                    .controlNodeCode(cnCode)
                    .controlNodeName(cnName)
                    .scheduleCron(entity.getScheduleCron())
                    .isActive(entity.getIsActive())
                    .lastExecution(lastExec != null ? ExecutionSummary.from(lastExec) : null)
                    .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null)
                    .build();
        }
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class DetailResponse {
        private Long id;
        private String name;
        private String description;
        private String jobType;
        /** v18.8.2 — Script entity id. */
        private Long scriptId;
        /** legacy. */
        private String scriptPath;

        // ── EvidenceType 식별/표시 (옛부터) ──
        private Long evidenceTypeId;
        private String evidenceTypeName;

        // ── v18.9 — 양방향 navigation (상세 패널의 pathline + router.push) ──
        private Long frameworkId;
        private Long controlNodeId;
        private String controlNodeCode;
        private String controlNodeName;

        private String scheduleCron;
        private Boolean isActive;
        private List<ExecutionSummary> executions;
        private String createdAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class ExecutionSummary {
        private Long id;
        private String status;
        private String startedAt;
        private String finishedAt;
        private String errorMessage;
        /**
         * v18.7 — selenium wrapper 산출 _diagnosis.json 의 전체 내용 (JSON String).
         * FE 의 parseDiagnosis(execution.errorDiagnosis) helper 가 파싱.
         */
        private String errorDiagnosis;
        private String createdAt;

        public static ExecutionSummary from(JobExecution exec) {
            return ExecutionSummary.builder()
                    .id(exec.getId())
                    .status(exec.getStatus().name())
                    .startedAt(exec.getStartedAt() != null ? exec.getStartedAt().toString() : null)
                    .finishedAt(exec.getFinishedAt() != null ? exec.getFinishedAt().toString() : null)
                    .errorMessage(exec.getErrorMessage())
                    .errorDiagnosis(exec.getErrorDiagnosis())
                    .createdAt(exec.getCreatedAt() != null ? exec.getCreatedAt().toString() : null)
                    .build();
        }
    }
}