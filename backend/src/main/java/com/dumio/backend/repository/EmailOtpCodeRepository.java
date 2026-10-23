package com.dumio.backend.repository;

import com.dumio.backend.entity.EmailOtpCode;
import com.dumio.backend.enums.OtpPurpose;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EmailOtpCodeRepository extends JpaRepository<EmailOtpCode, UUID> {

    Optional<EmailOtpCode> findByUserIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(
            UUID userId, OtpPurpose purpose);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            update EmailOtpCode c set c.usedAt = :now
            where c.id = :id and c.user.id = :userId and c.purpose = :purpose
              and c.usedAt is null and c.invalidatedAt is null and c.expiresAt > :now
            """)
    int consumeIfUsable(@Param("id") UUID id, @Param("userId") UUID userId,
            @Param("purpose") OtpPurpose purpose, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            update EmailOtpCode c set c.invalidatedAt = :now
            where c.user.id = :userId and c.purpose = :purpose
              and c.usedAt is null and c.invalidatedAt is null
            """)
    int invalidateAvailableByUserAndPurpose(@Param("userId") UUID userId,
            @Param("purpose") OtpPurpose purpose, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            update EmailOtpCode c set c.invalidatedAt = :now
            where c.id = :id and c.usedAt is null and c.invalidatedAt is null
            """)
    int invalidateIfUnused(@Param("id") UUID id, @Param("now") Instant now);
}
