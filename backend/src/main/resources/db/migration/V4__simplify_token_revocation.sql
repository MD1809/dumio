ALTER TABLE users
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_users_token_version CHECK (token_version >= 0);

-- Existing refresh tokens belong to the previous model and require a new login.
-- Keep their hashes/history; do not remove user or OTP data.
UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE revoked_at IS NULL;

DROP INDEX uk_refresh_tokens_available_family;
DROP INDEX ix_refresh_tokens_family;
ALTER TABLE refresh_tokens DROP COLUMN family_id;
