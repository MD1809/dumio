package com.dumio.backend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dumio.backend.entity.EmailOtpCode;
import com.dumio.backend.entity.RefreshToken;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.OtpPurpose;
import com.dumio.backend.enums.UserRole;
import com.dumio.backend.enums.UserStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AuthPersistenceTests {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode("Student1!");

    @Autowired
    private UserRepository users;

    @Autowired
    private EmailOtpCodeRepository codes;

    @Autowired
    private RefreshTokenRepository tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsUserDefaultsAndFindsNormalizedEmail() {
        User user = newUser();

        assertNotNull(user.getId());
        assertNotNull(user.getCreatedAt());
        assertNotNull(user.getUpdatedAt());
        assertEquals(UserRole.USER, user.getRole());
        assertEquals(UserStatus.PENDING_VERIFICATION, user.getStatus());
        assertEquals(user.getId(), users.findByEmail(user.getEmail()).orElseThrow().getId());
        assertTrue(users.existsByEmail(user.getEmail()));
    }

    @Test
    void rejectsDuplicateEmailAfterNormalization() {
        User first = newUser();
        User duplicate = userWithEmail((" " + first.getEmail().toUpperCase(Locale.ROOT) + " ")
                .trim().toLowerCase(Locale.ROOT));

        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(duplicate));
    }

    @Test
    void rejectsEmailThatBypassesLowercaseNormalization() {
        User user = userWithEmail("NotNormalized@example.com");

        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(user));
    }

    @Test
    void rejectsBlankUsername() {
        User user = userWithEmail(UUID.randomUUID() + "@example.com");
        user.setUsername("   ");

        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(user));
    }

    @Test
    void rejectsUnknownRoleAtDatabaseLevel() {
        User user = newUser();

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE users SET role = 'OWNER' WHERE id = ?", user.getId()));
    }

    @Test
    void rejectsActiveAccountWithoutVerifiedEmail() {
        User user = userWithEmail(UUID.randomUUID() + "@example.com");
        user.setStatus(UserStatus.ACTIVE);

        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(user));
    }

    @Test
    void permitsSeparateOtpPurposesButRejectsDuplicateAvailablePurpose() {
        User user = newUser();
        newCode(user, OtpPurpose.EMAIL_VERIFICATION, Instant.now().plusSeconds(300));
        newCode(user, OtpPurpose.PASSWORD_RESET, Instant.now().plusSeconds(300));

        assertTrue(codes.findByUserIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(
                user.getId(), OtpPurpose.EMAIL_VERIFICATION).isPresent());
        assertTrue(codes.findByUserIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(
                user.getId(), OtpPurpose.PASSWORD_RESET).isPresent());
        assertThrows(DataIntegrityViolationException.class,
                () -> newCode(user, OtpPurpose.EMAIL_VERIFICATION, Instant.now().plusSeconds(300)));
    }

    @Test
    void consumesOtpOnlyOnceForItsOwnerAndPurpose() {
        User user = newUser();
        EmailOtpCode code = newCode(user, OtpPurpose.EMAIL_VERIFICATION,
                Instant.now().plusSeconds(300));
        Instant now = Instant.now();

        assertEquals(0, codes.consumeIfUsable(code.getId(), UUID.randomUUID(),
                OtpPurpose.EMAIL_VERIFICATION, now));
        assertEquals(0, codes.consumeIfUsable(code.getId(), user.getId(),
                OtpPurpose.PASSWORD_RESET, now));
        assertEquals(1, codes.consumeIfUsable(code.getId(), user.getId(),
                OtpPurpose.EMAIL_VERIFICATION, now));
        assertEquals(0, codes.consumeIfUsable(code.getId(), user.getId(),
                OtpPurpose.EMAIL_VERIFICATION, now));
    }

    @Test
    void refusesExpiredOtp() {
        User user = newUser();
        EmailOtpCode code = codeFor(user, OtpPurpose.EMAIL_VERIFICATION,
                Instant.now().minusSeconds(60));
        code.setCreatedAt(Instant.now().minusSeconds(600));
        codes.saveAndFlush(code);

        assertEquals(0, codes.consumeIfUsable(code.getId(), user.getId(),
                OtpPurpose.EMAIL_VERIFICATION, Instant.now()));
    }

    @Test
    void invalidatesOnlyTheRequestedOtpPurposeBeforeReplacement() {
        User user = newUser();
        EmailOtpCode verification = newCode(user, OtpPurpose.EMAIL_VERIFICATION,
                Instant.now().plusSeconds(300));
        EmailOtpCode reset = newCode(user, OtpPurpose.PASSWORD_RESET,
                Instant.now().plusSeconds(300));
        Instant now = Instant.now();

        assertEquals(1, codes.invalidateAvailableByUserAndPurpose(user.getId(),
                OtpPurpose.EMAIL_VERIFICATION, now));
        newCode(user, OtpPurpose.EMAIL_VERIFICATION, now.plusSeconds(300));
        assertEquals(0, codes.consumeIfUsable(verification.getId(), user.getId(),
                OtpPurpose.EMAIL_VERIFICATION, now));
        assertEquals(1, codes.consumeIfUsable(reset.getId(), user.getId(),
                OtpPurpose.PASSWORD_RESET, now));
    }

    @Test
    void mailFailureInvalidationDoesNotInvalidateTheReplacementCode() {
        User user = newUser();
        EmailOtpCode first = newCode(user, OtpPurpose.EMAIL_VERIFICATION,
                Instant.now().plusSeconds(300));
        codes.invalidateIfUnused(first.getId(), Instant.now());
        EmailOtpCode replacement = newCode(user, OtpPurpose.EMAIL_VERIFICATION,
                Instant.now().plusSeconds(300));

        assertEquals(0, codes.invalidateIfUnused(first.getId(), Instant.now()));
        assertEquals(1, codes.consumeIfUsable(replacement.getId(), user.getId(),
                OtpPurpose.EMAIL_VERIFICATION, Instant.now()));
    }

    @Test
    void rejectsOtpExpiryBeforeCreation() {
        User user = newUser();
        EmailOtpCode code = codeFor(user, OtpPurpose.EMAIL_VERIFICATION,
                Instant.now().minusSeconds(300));

        assertThrows(DataIntegrityViolationException.class, () -> codes.saveAndFlush(code));
    }

    @Test
    void consumedRefreshRemainsFindableWhileReplacementMakesFamilyUsable() {
        User user = activeUser();
        UUID familyId = UUID.randomUUID();
        RefreshToken original = newToken(user, familyId);
        Instant now = Instant.now();

        assertEquals(1, tokens.consumeIfUsable(original.getId(), now));
        assertEquals(0, tokens.consumeIfUsable(original.getId(), now));
        assertFalse(tokens.existsUsableFamily(user.getId(), familyId, UserRole.USER, now));
        RefreshToken replacement = newToken(user, familyId);
        entityManager.clear();

        assertNotNull(tokens.findByTokenHash(original.getTokenHash()).orElseThrow().getConsumedAt());
        assertTrue(tokens.findByTokenHash(replacement.getTokenHash()).isPresent());
        assertTrue(tokens.existsUsableFamily(user.getId(), familyId, UserRole.USER, now));
    }

    @Test
    void rejectsTwoUnconsumedRefreshTokensInTheSameFamily() {
        User user = activeUser();
        UUID familyId = UUID.randomUUID();
        newToken(user, familyId);

        assertThrows(DataIntegrityViolationException.class, () -> newToken(user, familyId));
    }

    @Test
    void rejectsDuplicateRefreshHashes() {
        User user = activeUser();
        RefreshToken first = newToken(user, UUID.randomUUID());
        RefreshToken second = tokenFor(user, UUID.randomUUID());
        second.setTokenHash(first.getTokenHash());

        assertThrows(DataIntegrityViolationException.class, () -> tokens.saveAndFlush(second));
    }

    @Test
    void refusesExpiredRefreshAndRoleOrOwnerMismatch() {
        User user = activeUser();
        UUID familyId = UUID.randomUUID();
        RefreshToken token = newToken(user, familyId);
        Instant now = Instant.now();

        assertFalse(tokens.existsUsableFamily(user.getId(), familyId, UserRole.ADMIN, now));
        assertFalse(tokens.existsUsableFamily(UUID.randomUUID(), familyId, UserRole.USER, now));
        assertFalse(tokens.existsUsableFamily(user.getId(), familyId, UserRole.USER,
                now.plus(8, ChronoUnit.DAYS)));
        assertEquals(0, tokens.consumeIfUsable(token.getId(), now.plus(8, ChronoUnit.DAYS)));
    }

    @Test
    void disabledUserHasNoUsableFamily() {
        User user = activeUser();
        UUID familyId = UUID.randomUUID();
        newToken(user, familyId);
        user.setStatus(UserStatus.DISABLED);
        users.saveAndFlush(user);

        assertFalse(tokens.existsUsableFamily(user.getId(), familyId, UserRole.USER, Instant.now()));
    }

    @Test
    void revokesOneFamilyIncludingConsumedHistoryWithoutRevokingOtherLogins() {
        User user = activeUser();
        UUID familyId = UUID.randomUUID();
        RefreshToken original = newToken(user, familyId);
        tokens.consumeIfUsable(original.getId(), Instant.now());
        RefreshToken replacement = newToken(user, familyId);
        RefreshToken otherLogin = newToken(user, UUID.randomUUID());
        Instant now = Instant.now();

        assertEquals(2, tokens.revokeByFamilyId(familyId, now));
        assertEquals(0, tokens.revokeByFamilyId(familyId, now));
        assertEquals(0, tokens.consumeIfUsable(replacement.getId(), now));
        assertFalse(tokens.existsUsableFamily(user.getId(), familyId, UserRole.USER, now));
        assertTrue(tokens.existsUsableFamily(user.getId(), otherLogin.getFamilyId(), UserRole.USER, now));
    }

    @Test
    void revokesAllUserLoginsWithoutAffectingAnotherUser() {
        User user = activeUser();
        RefreshToken first = newToken(user, UUID.randomUUID());
        RefreshToken second = newToken(user, UUID.randomUUID());
        User otherUser = activeUser();
        RefreshToken otherToken = newToken(otherUser, UUID.randomUUID());
        Instant now = Instant.now();

        assertEquals(2, tokens.revokeByUserId(user.getId(), now));
        assertEquals(0, tokens.consumeIfUsable(first.getId(), now));
        assertEquals(0, tokens.consumeIfUsable(second.getId(), now));
        assertTrue(tokens.existsUsableFamily(otherUser.getId(), otherToken.getFamilyId(),
                UserRole.USER, now));
    }

    @Test
    void rejectsOrphanOtpAndCascadesDeletionOnlyForItsOwner() {
        User user = newUser();
        newCode(user, OtpPurpose.EMAIL_VERIFICATION, Instant.now().plusSeconds(300));
        newToken(user, UUID.randomUUID());

        jdbc.update("DELETE FROM users WHERE id = ?", user.getId());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_otp_codes WHERE user_id = ?",
                Integer.class, user.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?",
                Integer.class, user.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO email_otp_codes (id, user_id, purpose, code_digest, expires_at)
                VALUES (?, ?, 'EMAIL_VERIFICATION', ?, CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                """, UUID.randomUUID(), UUID.randomUUID(), "0".repeat(64)));
    }

    private User newUser() {
        return users.saveAndFlush(userWithEmail(UUID.randomUUID() + "@example.com"));
    }

    private User activeUser() {
        User user = userWithEmail(UUID.randomUUID() + "@example.com");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        return users.saveAndFlush(user);
    }

    private User userWithEmail(String email) {
        User user = new User();
        user.setUsername("Student");
        user.setEmail(email);
        user.setPasswordHash(PASSWORD_HASH);
        return user;
    }

    private EmailOtpCode newCode(User user, OtpPurpose purpose, Instant expiresAt) {
        return codes.saveAndFlush(codeFor(user, purpose, expiresAt));
    }

    private EmailOtpCode codeFor(User user, OtpPurpose purpose, Instant expiresAt) {
        EmailOtpCode code = new EmailOtpCode();
        code.setUser(user);
        code.setPurpose(purpose);
        code.setCodeDigest("0".repeat(64));
        code.setExpiresAt(expiresAt);
        return code;
    }

    private RefreshToken newToken(User user, UUID familyId) {
        return tokens.saveAndFlush(tokenFor(user, familyId));
    }

    private RefreshToken tokenFor(User user, UUID familyId) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setFamilyId(familyId);
        token.setTokenHash(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        token.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        return token;
    }
}
