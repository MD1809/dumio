package com.dumio.backend.repository;

import com.dumio.backend.entity.MediaAsset;
import com.dumio.backend.enums.MediaAssetStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {
    Page<MediaAsset> findByStatusAndCreatedAtBefore(MediaAssetStatus status, Instant cutoff, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MediaAsset m where m.id = :id")
    Optional<MediaAsset> findForUpdate(@Param("id") UUID id);

    @Query("""
            select m from MediaAsset m
            where m.status = com.dumio.backend.enums.MediaAssetStatus.PENDING_DELETION
                and (m.nextCleanupAt is null or m.nextCleanupAt <= :now)
            order by m.createdAt, m.id
            """)
    List<MediaAsset> findPendingCleanup(@Param("now") Instant now, Pageable pageable);

    // Archived records still hold references; do not filter them out during cleanup.
    @Query(value = """
            select exists(select 1 from vocabulary_sets where cover_asset_id = :id)
                or exists(select 1 from vocabulary_words where image_asset_id = :id or audio_asset_id = :id)
            """, nativeQuery = true)
    boolean isReferenced(@Param("id") UUID id);
}
