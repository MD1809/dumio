package com.dumio.backend.repository;

import com.dumio.backend.entity.VocabularyWord;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VocabularyWordRepository extends JpaRepository<VocabularyWord, UUID> {
    Page<VocabularyWord> findByArchivedAtIsNull(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from VocabularyWord w where w.id = :id")
    Optional<VocabularyWord> findForUpdate(@Param("id") UUID id);
}
