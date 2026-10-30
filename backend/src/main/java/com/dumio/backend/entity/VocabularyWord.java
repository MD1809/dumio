package com.dumio.backend.entity;

import com.dumio.backend.validation.VocabularyInputNormalizer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "vocabulary_words")
@Getter
@Setter
public class VocabularyWord extends VocabularyEntity {
    @Column(nullable = false, length = 150)
    private String term;

    @Column(name = "meaning_vi", nullable = false, length = 2000)
    private String meaningVi;

    @Column(length = 150)
    private String ipa;

    @Column(name = "part_of_speech", length = 50)
    private String partOfSpeech;

    @Column(name = "meaning_en", length = 2000)
    private String meaningEn;

    @Column(length = 2000)
    private String example;

    @Column(name = "example_translation", length = 2000)
    private String exampleTranslation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_asset_id")
    private MediaAsset imageAsset;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "audio_asset_id")
    private MediaAsset audioAsset;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] tags = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] synonyms = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] collocations = new String[0];

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
        term = VocabularyInputNormalizer.text(term);
        meaningVi = VocabularyInputNormalizer.text(meaningVi);
        ipa = VocabularyInputNormalizer.text(ipa);
        partOfSpeech = VocabularyInputNormalizer.text(partOfSpeech);
        meaningEn = VocabularyInputNormalizer.text(meaningEn);
        example = VocabularyInputNormalizer.text(example);
        exampleTranslation = VocabularyInputNormalizer.text(exampleTranslation);
        tags = VocabularyInputNormalizer.labels(tags);
        synonyms = VocabularyInputNormalizer.labels(synonyms);
        collocations = VocabularyInputNormalizer.labels(collocations);
    }
}
