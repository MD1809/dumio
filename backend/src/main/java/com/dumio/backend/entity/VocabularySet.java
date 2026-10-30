package com.dumio.backend.entity;

import com.dumio.backend.enums.VocabularyPublicationStatus;
import com.dumio.backend.validation.VocabularyInputNormalizer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "vocabulary_sets")
@Getter
@Setter
public class VocabularySet extends VocabularyEntity {
    @Column(nullable = false, length = 150)
    private String title;

    @Column(length = 2000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_id")
    private VocabularyTopic topic;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_asset_id")
    private MediaAsset coverAsset;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", nullable = false, length = 20)
    private VocabularyPublicationStatus publicationStatus = VocabularyPublicationStatus.DRAFT;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "updated_by", nullable = false)
    private User updatedBy;

    @PrePersist
    @PreUpdate
    private void normalizeText() {
        title = VocabularyInputNormalizer.text(title);
        description = VocabularyInputNormalizer.text(description);
    }
}
