package com.dumio.backend.entity;

import com.dumio.backend.enums.MediaAssetStatus;
import com.dumio.backend.enums.MediaType;
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
@Table(name = "media_assets")
@Getter
@Setter
public class MediaAsset extends VocabularyEntity {
    @Column(name = "storage_key", nullable = false, unique = true, updatable = false, columnDefinition = "text")
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 10)
    private MediaType mediaType;

    @Column(name = "detected_content_type", nullable = false, length = 100)
    private String detectedContentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "original_name", nullable = false, columnDefinition = "text")
    private String originalName;

    @Column(name = "image_width")
    private Integer imageWidth;

    @Column(name = "image_height")
    private Integer imageHeight;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(columnDefinition = "text")
    private String source;

    @Column(name = "usage_rights", columnDefinition = "text")
    private String usageRights;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MediaAssetStatus status = MediaAssetStatus.TEMPORARY;

    @Column(name = "delete_attempts", nullable = false)
    private int deleteAttempts;

    @Column(name = "next_cleanup_at")
    private Instant nextCleanupAt;

    @Version
    @Column(nullable = false)
    private long version;

    @PrePersist
    @PreUpdate
    private void normalizeText() {
        originalName = VocabularyInputNormalizer.text(originalName);
        source = VocabularyInputNormalizer.text(source);
        usageRights = VocabularyInputNormalizer.text(usageRights);
    }
}
