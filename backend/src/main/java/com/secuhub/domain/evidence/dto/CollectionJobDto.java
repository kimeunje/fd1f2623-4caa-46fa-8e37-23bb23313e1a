package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.CollectionJob;
import com.secuhub.domain.evidence.entity.JobExecution;
import lombok.*;

import java.util.List;

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
        private Long evidenceTypeId;
        private String evidenceTypeName;
        private String scheduleCron;
        private Boolean isActive;
        private ExecutionSummary lastExecution;
        private String createdAt;

        public static Response from(CollectionJob entity, JobExecution lastExec) {
            return Response.builder()
                    .id(entity.getId())
                    .name(entity.getName())
                    .description(entity.getDescription())
                    .jobType(entity.getJobType().name())
                    .scriptId(entity.getScript() != null ? entity.getScript().getId() : null)
                    .scriptPath(entity.getScriptPath())
                    .evidenceTypeId(entity.getEvidenceType() != null ? entity.getEvidenceType().getId() : null)
                    .evidenceTypeName(entity.getEvidenceType() != null ? entity.getEvidenceType().getName() : null)
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
        private Long evidenceTypeId;
        private String evidenceTypeName;
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