package com.dumio.backend.dto.response;

import com.dumio.backend.entity.VocabularyTopic;
import java.time.Instant;
import java.util.UUID;

public record VocabularyTopicResponse(UUID id, String name, String description, Instant archivedAt,
        long version, Instant createdAt, Instant updatedAt) {
    public static VocabularyTopicResponse from(VocabularyTopic topic) {
        return new VocabularyTopicResponse(topic.getId(), topic.getName(), topic.getDescription(),
                topic.getArchivedAt(), topic.getVersion(), topic.getCreatedAt(), topic.getUpdatedAt());
    }
}
