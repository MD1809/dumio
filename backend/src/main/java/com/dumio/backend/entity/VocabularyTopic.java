package com.dumio.backend.entity;

import com.dumio.backend.validation.VocabularyInputNormalizer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "vocabulary_topics")
@Getter
@Setter
public class VocabularyTopic extends VocabularyEntity {
    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @PrePersist
    @PreUpdate
    private void normalizeText() {
        name = VocabularyInputNormalizer.text(name);
        description = VocabularyInputNormalizer.text(description);
    }
}
