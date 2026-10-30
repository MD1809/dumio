package com.dumio.backend.repository;

import com.dumio.backend.entity.VocabularySetItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VocabularySetItemRepository extends JpaRepository<VocabularySetItem, UUID> {
    long countByVocabularySetIdAndRemovedAtIsNull(UUID setId);

    long countByVocabularySetIdAndRemovedAtIsNullAndWordArchivedAtIsNull(UUID setId);

    boolean existsByVocabularySetIdAndWordIdAndRemovedAtIsNull(UUID setId, UUID wordId);

    Page<VocabularySetItem> findByWordIdAndRemovedAtIsNull(UUID wordId, Pageable pageable);

    @Query("""
            select i from VocabularySetItem i join fetch i.word w
            where i.vocabularySet.id = :setId and i.removedAt is null
            order by lower(w.term), w.id
            """)
    List<VocabularySetItem> findCurrentMembers(@Param("setId") UUID setId);

    @Query("""
            select count(i) > 0 from VocabularySetItem i
            where i.word.id = :wordId and i.removedAt is null
                and i.vocabularySet.archivedAt is null
                and i.vocabularySet.publicationStatus = com.dumio.backend.enums.VocabularyPublicationStatus.PUBLISHED
            """)
    boolean isUsedByPublishedSet(@Param("wordId") UUID wordId);
}
