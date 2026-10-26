package com.dumio.backend.repository;

import com.dumio.backend.entity.PendingRegistration;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PendingRegistration r where r.id = :id")
    Optional<PendingRegistration> findForUpdate(@Param("id") UUID id);

    @Modifying(flushAutomatically = true)
    @Query("delete from PendingRegistration r where r.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
