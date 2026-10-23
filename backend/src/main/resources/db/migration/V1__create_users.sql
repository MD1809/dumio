CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    email_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_username CHECK (username = btrim(username) AND username <> ''),
    CONSTRAINT ck_users_email_normalized CHECK (
        email = lower(btrim(email)) AND email <> '' AND email !~ '[[:space:]]'
    ),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_email_verification CHECK (
        (status = 'ACTIVE' AND email_verified_at IS NOT NULL)
        OR (status = 'PENDING_VERIFICATION' AND email_verified_at IS NULL)
        OR status = 'DISABLED'
    )
);
