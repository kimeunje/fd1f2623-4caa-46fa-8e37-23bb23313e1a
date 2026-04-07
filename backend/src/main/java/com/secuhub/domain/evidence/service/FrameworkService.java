package com.secuhub.domain.evidence.service;

import com.secuhub.common.exception.ResourceNotFoundException;
import com.secuhub.domain.evidence.dto.FrameworkDto;
import com.secuhub.domain.evidence.entity.Framework;
import com.secuhub.domain.evidence.repository.FrameworkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FrameworkService {

    private final FrameworkRepository frameworkRepository;

    @Transactional(readOnly = true)
    public List<FrameworkDto.Response> findAll() {
        return frameworkRepository.findAll().stream()
                .map(FrameworkDto.Response::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public FrameworkDto.Response findById(Long id) {
        Framework framework = frameworkRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("프레임워크", id));
        return FrameworkDto.Response.from(framework);
    }

    @Transactional
    public FrameworkDto.Response create(FrameworkDto.CreateRequest request) {
        Framework framework = Framework.builder()
                .name(request.getName())
                .description(request.getDescription())
                .build();
        return FrameworkDto.Response.from(frameworkRepository.save(framework));
    }

    @Transactional
    public FrameworkDto.Response update(Long id, FrameworkDto.UpdateRequest request) {
        Framework framework = frameworkRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("프레임워크", id));
        framework.update(request.getName(), request.getDescription());
        return FrameworkDto.Response.from(framework);
    }

    @Transactional
    public void delete(Long id) {
        if (!frameworkRepository.existsById(id)) {
            throw new ResourceNotFoundException("프레임워크", id);
        }
        frameworkRepository.deleteById(id);
    }
}
