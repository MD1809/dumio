package com.dumio.backend.repository;

import com.dumio.backend.entity.RefreshToken;
import com.dumio.backend.enums.UserRole;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // Includes consumed/revoked rows for replay detection and idempotent logout.
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Query("""
            select (count(t) > 0) from RefreshToken t
            where t.user.id = :userId and t.familyId = :familyId
              and t.user.status = com.dumio.backend.enums.UserStatus.ACTIVE
              and t.user.emailVerifiedAt is not null and t.user.role = :role
              and t.consumedAt is null and t.revokedAt is null and t.expiresAt > :now
            """)
    boolean existsUsableFamily(@Param("userId") UUID userId, @Param("familyId") UUID familyId,
            @Param("role") UserRole role, @Param("now") Instant now);

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
            where t.familyId = :familyId and t.revokedAt is null
            """)
    int revokeByFamilyId(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now
            where t.user.id = :userId and t.revokedAt is null
            """)
    int revokeByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
