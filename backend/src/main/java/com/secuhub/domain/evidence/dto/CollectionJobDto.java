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
        private String createdAt;

        public static ExecutionSummary from(JobExecution exec) {
            return ExecutionSummary.builder()
                    .id(exec.getId())
                    .status(exec.getStatus().name())
                    .startedAt(exec.getStartedAt() != null ? exec.getStartedAt().toString() : null)
                    .finishedAt(exec.getFinishedAt() != null ? exec.getFinishedAt().toString() : null)
                    .errorMessage(exec.getErrorMessage())
                    .createdAt(exec.getCreatedAt() != null ? exec.getCreatedAt().toString() : null)
                    .build();
        }
    }
}
