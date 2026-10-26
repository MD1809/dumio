package com.dumio.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "pending_registrations")
@Getter
@Setter
public class PendingRegistration {
    @Id
    private UUID id;
    @Column(nullable = false, length = 100)
    private String username;
    @Column(nullable = false, length = 254)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @OneToMany(mappedBy = "registration", cascade = CascadeType.REMOVE)
    private List<EmailOtpCode> codes = new ArrayList<>();
}
