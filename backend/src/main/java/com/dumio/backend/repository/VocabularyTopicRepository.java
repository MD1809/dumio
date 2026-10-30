package com.dumio.backend.repository;

import com.dumio.backend.entity.VocabularyTopic;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VocabularyTopicRepository extends JpaRepository<VocabularyTopic, UUID> {
    boolean existsByNameIgnoreCaseAndArchivedAtIsNull(String name);

    Page<VocabularyTopic> findByArchivedAtIsNull(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from VocabularyTopic t where t.id = :id")
    Optional<VocabularyTopic> findForUpdate(@Param("id") UUID id);
}
