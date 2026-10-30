package com.dumio.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@MappedSuperclass
@Getter
@Setter
public abstract class VocabularyEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    private void initializeTimestamps() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void updateTimestamp() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
