package com.dumio.backend.repository;

import com.dumio.backend.entity.User;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    // Call together with refresh revocation in the enclosing service transaction.
    // Reload a managed User before issuing another JWT after this bulk update.
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            update User u set u.tokenVersion = u.tokenVersion + 1, u.updatedAt = :now
            where u.id = :id
            """)
    int incrementTokenVersion(@Param("id") UUID id, @Param("now") Instant now);
}
