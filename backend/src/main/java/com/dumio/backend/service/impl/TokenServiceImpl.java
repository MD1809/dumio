package com.dumio.backend.service.impl;

import com.dumio.backend.config.JwtConfig;
import com.dumio.backend.dto.response.TokenResponse;
import com.dumio.backend.entity.RefreshToken;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.ErrorCode;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.exception.AuthException;
import com.dumio.backend.mapper.UserMapper;
import com.dumio.backend.repository.RefreshTokenRepository;
import com.dumio.backend.repository.UserRepository;
import com.dumio.backend.service.TokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenServiceImpl implements TokenService {

    private static final Pattern REFRESH_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final JwtEncoder encoder;
    private final UserMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public TokenServiceImpl(RefreshTokenRepository tokens, UserRepository users, JwtEncoder encoder, UserMapper mapper) {
        this.tokens = tokens;
        this.users = users;
        this.encoder = encoder;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public TokenPair issue(User user) {
        requireActive(user);
        return createTokens(user);
    }

    @Override
    @Transactional
    public TokenPair rotate(String refreshToken) {
        if (!hasValidFormat(refreshToken)) {
            throw new AuthException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        RefreshToken stored = tokens.findByTokenHash(hash(refreshToken))
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_REFRESH_TOKEN));
        Instant now = Instant.now();
        if (stored.getConsumedAt() != null || stored.getRevokedAt() != null || !stored.getExpiresAt().isAfter(now)) {
            throw new AuthException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        User user = stored.getUser();
        requireActive(user);
        if (tokens.consumeIfUsable(stored.getId(), now) != 1) {
            throw new AuthException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        // Both consumption and insertion roll back if signing or saving the replacement fails.
        return createTokens(user);
    }

    @Override
    @Transactional
    public void revokeForLogout(String refreshToken) {
        if (!hasValidFormat(refreshToken)) {
            return;
        }
        var found = tokens.findByTokenHash(hash(refreshToken));
        if (found.isEmpty() || found.get().getRevokedAt() != null) {
            return;
        }
        RefreshToken stored = found.get();
        Instant now = Instant.now();
        // Claim this logout once, including for a consumed/expired token. No explicit row locks.
        if (tokens.revokeIfNotRevoked(stored.getId(), now) == 0) {
            return;
        }
        var userId = stored.getUser().getId();
        tokens.revokeByUserId(userId, now);
        if (users.incrementTokenVersion(userId, now) != 1) {
            throw new DataAccessResourceFailureException("Account revocation could not be completed");
        }
    }

    private TokenPair createTokens(User user) {
        Instant now = Instant.now();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var claims = JwtClaimsSet.builder().subject(user.getId().toString())
                .issuer(JwtConfig.ISSUER).audience(List.of(JwtConfig.AUDIENCE))
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(JwtConfig.ACCESS_TOKEN_SECONDS))
                .claim("roles", List.of(user.getRole().name())).claim("token_version", user.getTokenVersion())
                .build();
        String access = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();

        RefreshToken stored = new RefreshToken();
        stored.setUser(user);
        stored.setTokenHash(hash(refresh));
        stored.setCreatedAt(now);
        stored.setExpiresAt(now.plusSeconds(JwtConfig.REFRESH_TOKEN_SECONDS));
        tokens.saveAndFlush(stored);
        return new TokenPair(new TokenResponse(access, "Bearer", JwtConfig.ACCESS_TOKEN_SECONDS,
                mapper.toResponse(user)), refresh);
    }

    private static void requireActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE || user.getEmailVerifiedAt() == null) {
            throw new AuthException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    private static boolean hasValidFormat(String token) {
        return token != null && REFRESH_FORMAT.matcher(token).matches();
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Token hashing unavailable");
        }
    }
}
