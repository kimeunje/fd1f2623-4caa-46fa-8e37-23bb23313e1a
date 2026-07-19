package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.ControlNodeNoteDto;
import com.secuhub.domain.evidence.entity.ControlNode;
import com.secuhub.domain.evidence.entity.ControlNodeNote;
import com.secuhub.domain.evidence.repository.ControlNodeNoteRepository;
import com.secuhub.domain.evidence.repository.ControlNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * v19.27 — 관리 항목 인수인계 노트 서비스.
 *
 * <p>트리 PATCH(낙관적 락)와 완전히 분리된 즉시 반영 CRUD. 노트 추가/수정/삭제는
 * 각각 독립 트랜잭션이며 트리 version 과 무관하다.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ControlNodeNoteService {

    private final ControlNodeNoteRepository noteRepository;
    private final ControlNodeRepository controlNodeRepository;

    /**
     * 특정 관리 항목의 노트 목록(작성순).
     */
    public List<ControlNodeNoteDto.Response> list(Long nodeId) {
        // 존재하지 않는 노드 조회는 404
        if (!controlNodeRepository.existsById(nodeId)) {
            throw new ResourceNotFoundException("관리 항목", nodeId);
        }
        return noteRepository.findByControlNodeIdOrderByCreatedAtAsc(nodeId).stream()
                .map(ControlNodeNoteDto.Response::from)
                .toList();
    }

    /**
     * 노트 추가.
     *
     * @param nodeId     소속 관리 항목 id
     * @param authorName 작성자 이름(직접 입력)
     * @param body       노트 본문(마크다운)
     */
    @Transactional
    public ControlNodeNoteDto.Response create(Long nodeId, String authorName, String body) {
        ControlNode node = controlNodeRepository.findById(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("관리 항목", nodeId));

        ControlNodeNote note = ControlNodeNote.builder()
                .controlNode(node)
                .authorName(authorName)
                .body(body)
                .build();
        return ControlNodeNoteDto.Response.from(noteRepository.save(note));
    }

    /**
     * 노트 수정 — author_name / body 부분 수정.
     *
     * <p>{@code noteId} 가 {@code nodeId} 소속이 아니면 404 (경로 정합 검증).</p>
     */
    @Transactional
    public ControlNodeNoteDto.Response update(Long nodeId, Long noteId, String authorName, String body) {
        ControlNodeNote note = getOwnedNote(nodeId, noteId);
        note.update(authorName, body);
        return ControlNodeNoteDto.Response.from(note);
    }

    /**
     * 노트 삭제.
     */
    @Transactional
    public void delete(Long nodeId, Long noteId) {
        ControlNodeNote note = getOwnedNote(nodeId, noteId);
        noteRepository.delete(note);
    }

    /**
     * noteId 를 로드하고 nodeId 소속인지 검증. 불일치/미존재 시 404.
     * URL 의 nodeId 와 실제 노트의 소속이 다르면 잘못된 요청이므로 노출하지 않는다.
     */
    private ControlNodeNote getOwnedNote(Long nodeId, Long noteId) {
        ControlNodeNote note = noteRepository.findById(noteId)
                .orElseThrow(() -> new ResourceNotFoundException("인수인계 노트", noteId));
        if (note.getControlNode() == null || !note.getControlNode().getId().equals(nodeId)) {
            throw new ResourceNotFoundException("인수인계 노트", noteId);
        }
        return note;
    }
}