package com.dumio.backend.repository;

import com.dumio.backend.entity.VocabularySet;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VocabularySetRepository extends JpaRepository<VocabularySet, UUID> {
    Page<VocabularySet> findByArchivedAtIsNull(Pageable pageable);

    // Service mutations must serialize membership/state checks using this parent lock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VocabularySet v where v.id = :id")
    Optional<VocabularySet> findForUpdate(@Param("id") UUID id);
}
