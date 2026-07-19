package com.secuhub.domain.evidence.dto;

import com.secuhub.domain.evidence.entity.ControlNodeNote;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * v19.27 — 관리 항목 인수인계 노트 응답 DTO.
 */
public class ControlNodeNoteDto {

    @Getter
    @Builder
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private String authorName;
        private String body;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        /** 작성 후 한 번이라도 수정됐는지 — FE 의 "(수정됨)" 표시용. */
        private boolean edited;

        public static Response from(ControlNodeNote note) {
            LocalDateTime created = note.getCreatedAt();
            LocalDateTime updated = note.getUpdatedAt();
            boolean edited = created != null && updated != null && !Objects.equals(created, updated);
            return Response.builder()
                    .id(note.getId())
                    .authorName(note.getAuthorName())
                    .body(note.getBody())
                    .createdAt(created)
                    .updatedAt(updated)
                    .edited(edited)
                    .build();
        }
    }
}