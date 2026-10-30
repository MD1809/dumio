package com.dumio.backend.service.impl;

import com.dumio.backend.dto.request.CreateVocabularyTopicRequest;
import com.dumio.backend.dto.request.RestoreVocabularyTopicRequest;
import com.dumio.backend.dto.request.UpdateVocabularyTopicRequest;
import com.dumio.backend.dto.request.VocabularyTopicQuery;
import com.dumio.backend.dto.response.VocabularyPageResponse;
import com.dumio.backend.dto.response.VocabularyTopicResponse;
import com.dumio.backend.entity.VocabularyTopic;
import com.dumio.backend.enums.ErrorCode;
import com.dumio.backend.exception.VocabularyException;
import com.dumio.backend.repository.VocabularyTopicRepository;
import com.dumio.backend.service.VocabularyTopicService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class VocabularyTopicServiceImpl implements VocabularyTopicService {
    private final VocabularyTopicRepository topics;

    public VocabularyTopicServiceImpl(VocabularyTopicRepository topics) {
        this.topics = topics;
    }

    @Override
    public VocabularyPageResponse<VocabularyTopicResponse> list(VocabularyTopicQuery query) {
        Specification<VocabularyTopic> filter = (root, criteria, builder) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (query.archived().equals("false")) predicates.add(builder.isNull(root.get("archivedAt")));
            if (query.archived().equals("true")) predicates.add(builder.isNotNull(root.get("archivedAt")));
            if (query.q() != null) {
                String escaped = query.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                predicates.add(builder.like(builder.lower(root.get("name")),
                        builder.lower(builder.literal("%" + escaped + "%")), '\\'));
            }
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        // JPA offsets are int, but valid distant pages still return the real count and an empty list.
        Page<VocabularyTopic> result = query.pageable().getOffset() > Integer.MAX_VALUE
                ? new PageImpl<>(List.of(), query.pageable(), topics.count(filter))
                : topics.findAll(filter, query.pageable());
        return VocabularyPageResponse.from(result.map(VocabularyTopicResponse::from));
    }

    @Override
    public VocabularyTopicResponse get(UUID id) {
        return VocabularyTopicResponse.from(topics.findById(id)
                .orElseThrow(() -> new VocabularyException(ErrorCode.VOCABULARY_NOT_FOUND)));
    }

    @Override
    @Transactional
    public VocabularyTopicResponse create(CreateVocabularyTopicRequest input) {
        VocabularyTopic topic = new VocabularyTopic();
        topic.setName(input.name());
        topic.setDescription(input.description());
        // The partial unique index is authoritative, including concurrent inserts/restores.
        return VocabularyTopicResponse.from(topics.saveAndFlush(topic));
    }

    @Override
    @Transactional
    public VocabularyTopicResponse update(UUID id, UpdateVocabularyTopicRequest input) {
        VocabularyTopic topic = current(id, input.version());
        if (topic.getArchivedAt() != null) throw new VocabularyException(ErrorCode.VOCABULARY_ARCHIVED);
        topic.setName(input.name());
        topic.setDescription(input.description());
        // Even submitting unchanged content consumes this version.
        topic.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return VocabularyTopicResponse.from(topics.saveAndFlush(topic));
    }

    @Override
    @Transactional
    public VocabularyTopicResponse archive(UUID id, long version) {
        VocabularyTopic topic = current(id, version);
        if (topic.getArchivedAt() != null) throw new VocabularyException(ErrorCode.VOCABULARY_STATE_CONFLICT);
        topic.setArchivedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        // Keep all existing set references and publication states unchanged.
        return VocabularyTopicResponse.from(topics.saveAndFlush(topic));
    }

    @Override
    @Transactional
    public VocabularyTopicResponse restore(UUID id, RestoreVocabularyTopicRequest input) {
        VocabularyTopic topic = current(id, input.version());
        if (topic.getArchivedAt() == null) throw new VocabularyException(ErrorCode.VOCABULARY_STATE_CONFLICT);
        if (input.name() != null) topic.setName(input.name());
        topic.setArchivedAt(null);
        // Name change and restoration succeed or roll back together under the unique index.
        return VocabularyTopicResponse.from(topics.saveAndFlush(topic));
    }

    private VocabularyTopic current(UUID id, long version) {
        // Later set operations must use this topic lock when validating active-topic selection/publication.
        VocabularyTopic topic = topics.findForUpdate(id)
                .orElseThrow(() -> new VocabularyException(ErrorCode.VOCABULARY_NOT_FOUND));
        if (topic.getVersion() != version) throw new VocabularyException(ErrorCode.VOCABULARY_VERSION_CONFLICT);
        return topic;
    }
}
