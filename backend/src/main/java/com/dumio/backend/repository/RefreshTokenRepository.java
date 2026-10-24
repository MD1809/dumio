package com.dumio.backend.repository;

import com.dumio.backend.entity.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // Includes consumed/revoked rows to reject reuse and identify idempotent logout.
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshToken t set t.consumedAt = :now
            where t.id = :id and t.consumedAt is null
              and t.revokedAt is null and t.expiresAt > :now
            """)
    int consumeIfUsable(@Param("id") UUID id, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now
            where t.id = :id and t.revokedAt is null
            """)
    int revokeIfNotRevoked(@Param("id") UUID id, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now
            where t.user.id = :userId and t.revokedAt is null
            """)
    int revokeByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
