CREATE TABLE pending_registrations (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_pending_registration_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_pending_registrations_expiry ON pending_registrations (expires_at);

ALTER TABLE email_otp_codes ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE email_otp_codes ADD COLUMN registration_id UUID;
ALTER TABLE email_otp_codes ADD CONSTRAINT fk_otp_pending_registration
    FOREIGN KEY (registration_id) REFERENCES pending_registrations(id) ON DELETE CASCADE;
ALTER TABLE email_otp_codes ADD CONSTRAINT ck_otp_owner
    CHECK ((user_id IS NOT NULL AND registration_id IS NULL)
        OR (user_id IS NULL AND registration_id IS NOT NULL AND purpose = 'EMAIL_VERIFICATION'));

CREATE UNIQUE INDEX uk_otp_registration_available ON email_otp_codes (registration_id)
    WHERE registration_id IS NOT NULL AND used_at IS NULL AND invalidated_at IS NULL;
