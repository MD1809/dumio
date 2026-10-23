CREATE TABLE email_otp_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    code_digest VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_email_otp_codes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_email_otp_codes_purpose CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    CONSTRAINT ck_email_otp_codes_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_email_otp_codes_user_purpose_created
    ON email_otp_codes (user_id, purpose, created_at DESC);

CREATE UNIQUE INDEX uk_email_otp_codes_available
    ON email_otp_codes (user_id, purpose)
    WHERE used_at IS NULL AND invalidated_at IS NULL;
