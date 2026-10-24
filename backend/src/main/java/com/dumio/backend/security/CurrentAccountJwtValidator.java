package com.dumio.backend.security;

import com.dumio.backend.enums.ErrorCode;
import com.dumio.backend.enums.UserRole;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/** Checks application claims and the current account without replacing JWT verification. */
@Component
public class CurrentAccountJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_TOKEN =
            new OAuth2Error("invalid_token", "Access token is invalid or expired", null);

    private final UserRepository users;

    public CurrentAccountJwtValidator(UserRepository users) {
        this.users = users;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        UUID userId;
        UserRole role;
        long version;
        try {
            userId = UUID.fromString(token.getSubject());
            Object roles = token.getClaims().get("roles");
            if (!(roles instanceof List<?> values) || values.size() != 1
                    || !(values.getFirst() instanceof String value)) {
                return invalid();
            }
            role = UserRole.valueOf(value);
            Object rawVersion = token.getClaims().get("token_version");
            if (!(rawVersion instanceof Long || rawVersion instanceof Integer)) {
                return invalid();
            }
            version = ((Number) rawVersion).longValue();
            Instant issued = token.getIssuedAt();
            Instant notBefore = token.getNotBefore();
            Instant expires = token.getExpiresAt();
            if (version < 0 || issued == null || notBefore == null || expires == null
                    || !expires.isAfter(issued) || notBefore.isAfter(expires)
                    || issued.isAfter(Instant.now().plusSeconds(30))) {
                return invalid();
            }
        } catch (IllegalArgumentException | NullPointerException ex) {
            return invalid();
        }

        try {
            return users.findById(userId)
                    .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                    .filter(user -> user.getEmailVerifiedAt() != null)
                    .filter(user -> user.getRole() == role && user.getTokenVersion() == version)
                    .map(user -> OAuth2TokenValidatorResult.success())
                    .orElseGet(CurrentAccountJwtValidator::invalid);
        } catch (DataAccessException | TransactionException ex) {
            // A distinct error lets the entry point return 503, not a credential error.
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    ErrorCode.SERVICE_UNAVAILABLE.getCode(), "Authentication storage unavailable", null));
        }
    }

    private static OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
    }
}
