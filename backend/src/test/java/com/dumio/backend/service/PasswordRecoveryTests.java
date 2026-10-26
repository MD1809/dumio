package com.dumio.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dumio.backend.dto.request.ForgotPasswordRequest;
import com.dumio.backend.dto.request.LoginRequest;
import com.dumio.backend.dto.request.ResetPasswordRequest;
import com.dumio.backend.entity.EmailOtpCode;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.ErrorCode;
import com.dumio.backend.enums.OtpPurpose;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.event.OtpMailEvent;
import com.dumio.backend.event.PasswordChangedEvent;
import com.dumio.backend.exception.AuthException;
import com.dumio.backend.repository.EmailOtpCodeRepository;
import com.dumio.backend.repository.RefreshTokenRepository;
import com.dumio.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "spring.mail.username=test@example.com", "spring.mail.password=unused"})
@AutoConfigureMockMvc
@RecordApplicationEvents
@Transactional
class PasswordRecoveryTests {

    @Autowired AuthService auth;
    @Autowired OtpService otp;
    @Autowired UserRepository users;
    @Autowired EmailOtpCodeRepository codes;
    @Autowired RefreshTokenRepository tokens;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtDecoder decoder;
    @Autowired ApplicationEvents events;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired @Qualifier("otpHmacKey") SecretKey key;
    @MockitoBean JavaMailSender sender;

    @Test
    void forgotReturnsSame202ForEveryEligibleOrIneligibleEmail() throws Exception {
        var csrf = csrf();
        String expectedBody = null;
        for (UserStatus status : UserStatus.values()) {
            User user = user(status);
            long before = events.stream(OtpMailEvent.class).count();
            var result = mvc.perform(post("/api/v1/auth/forgot-password").cookie(csrf.cookie())
                            .header("X-XSRF-TOKEN", csrf.value()).contentType("application/json")
                            .content(json.writeValueAsString(Map.of("email", " "
                                    + user.getEmail().toUpperCase(java.util.Locale.ROOT) + " "))))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("expiresInSeconds").value(300))
                    .andExpect(jsonPath("code").doesNotExist()).andExpect(jsonPath("user").doesNotExist()).andReturn();
            if (expectedBody == null) { expectedBody = result.getResponse().getContentAsString(); }
            assertEquals(expectedBody, result.getResponse().getContentAsString());
            assertEquals(before + (status == UserStatus.ACTIVE ? 1 : 0), events.stream(OtpMailEvent.class).count());
        }
        long before = events.stream(OtpMailEvent.class).count();
        var missing = mvc.perform(post("/api/v1/auth/forgot-password").cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.value()).contentType("application/json")
                        .content("{\"email\":\"absent@example.com\"}"))
                .andExpect(status().isAccepted()).andReturn();
        assertEquals(expectedBody, missing.getResponse().getContentAsString());
        assertEquals(before, events.stream(OtpMailEvent.class).count());
        verifyNoInteractions(sender); // No outer transaction commit, so no mail yet.
    }

    @Test
    void successfulResetChangesBcryptPasswordRevokesAllTokensAndDoesNotAutoLogin() throws Exception {
        User user = user(UserStatus.ACTIVE);
        var first = auth.login(new LoginRequest(user.getEmail(), "Student1!"));
        var second = auth.login(new LoginRequest(user.getEmail(), "Student1!"));
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        OtpMailEvent mail = lastMail();
        assertEquals(OtpPurpose.PASSWORD_RESET, mail.purpose());
        assertTrue(mail.code().matches("[0-9]{6}"));
        var csrf = csrf();
        var result = mvc.perform(post("/api/v1/auth/reset-password").cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.value()).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", user.getEmail(), "code", mail.code(),
                                "newPassword", "Newpass2!", "confirmPassword", "Newpass2!"))))
                .andExpect(status().isOk()).andExpect(jsonPath("message").value("Thao tác thành công"))
                .andExpect(jsonPath("accessToken").doesNotExist()).andReturn();
        assertEquals(0, result.getResponse().getCookie("DUMIO_REFRESH").getMaxAge());
        assertEquals("/api/v1/auth", result.getResponse().getCookie("DUMIO_REFRESH").getPath());
        assertEquals(0, result.getResponse().getCookie("XSRF-TOKEN").getMaxAge());
        em.clear();
        User changed = users.findById(user.getId()).orElseThrow();
        assertTrue(passwords.matches("Newpass2!", changed.getPasswordHash()));
        assertFalse(passwords.matches("Student1!", changed.getPasswordHash()));
        assertEquals(1, changed.getTokenVersion());
        assertEquals(UserStatus.ACTIVE, changed.getStatus());
        assertNotNull(codes.findById(mail.otpId()).orElseThrow().getUsedAt());
        assertNotNull(tokens.findByTokenHash(hash(first.refreshToken())).orElseThrow().getRevokedAt());
        assertNotNull(tokens.findByTokenHash(hash(second.refreshToken())).orElseThrow().getRevokedAt());
        assertThrows(JwtException.class, () -> decoder.decode(first.response().accessToken()));
        assertThrows(JwtException.class, () -> decoder.decode(second.response().accessToken()));
        assertThrows(AuthException.class, () -> auth.refresh(first.refreshToken()));
        assertThrows(AuthException.class, () -> auth.login(new LoginRequest(user.getEmail(), "Student1!")));
        assertNotNull(auth.login(new LoginRequest(user.getEmail(), "Newpass2!")).response().accessToken());
        assertEquals(1, events.stream(PasswordChangedEvent.class).count());
        verifyNoInteractions(sender);
    }

    @Test
    void forgotAndResendDoNotChangePasswordOrAuthenticationAndReplaceOnlyResetOtp() throws Exception {
        User user = user(UserStatus.ACTIVE);
        String originalHash = user.getPasswordHash();
        var login = auth.login(new LoginRequest(user.getEmail(), "Student1!"));
        otp.issue(user, OtpPurpose.EMAIL_VERIFICATION);
        UUID verificationId = lastMail().otpId();
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        UUID firstId = lastMail().otpId();
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        UUID replacementId = lastMail().otpId();
        em.clear();
        assertNotNull(codes.findById(firstId).orElseThrow().getInvalidatedAt());
        assertNull(codes.findById(verificationId).orElseThrow().getInvalidatedAt());
        assertNull(codes.findById(replacementId).orElseThrow().getInvalidatedAt());
        User unchanged = users.findById(user.getId()).orElseThrow();
        assertEquals(originalHash, unchanged.getPasswordHash());
        assertEquals(0, unchanged.getTokenVersion());
        assertNull(tokens.findByTokenHash(hash(login.refreshToken())).orElseThrow().getRevokedAt());
        assertNotNull(decoder.decode(login.response().accessToken()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong", "expired", "used", "invalidated", "verification-purpose",
            "other-user", "disabled", "pending", "missing-user"})
    void invalidOrIneligibleResetDoesNotChangePasswordOrVersion(String scenario) {
        User user = user(UserStatus.ACTIVE);
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        OtpMailEvent mail = lastMail();
        EmailOtpCode stored = codes.findById(mail.otpId()).orElseThrow();
        String submitted = mail.code();
        String email = user.getEmail();
        switch (scenario) {
            case "wrong" -> submitted = submitted.equals("000000") ? "000001" : "000000";
            case "expired" -> {
                em.createNativeQuery("""
                        UPDATE email_otp_codes SET created_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes',
                            expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id
                        """).setParameter("id", stored.getId()).executeUpdate();
                em.refresh(stored);
            }
            case "used" -> codes.consumeIfUsable(stored.getId(), user.getId(), OtpPurpose.PASSWORD_RESET, Instant.now());
            case "invalidated" -> codes.invalidateIfUnused(stored.getId(), Instant.now());
            case "verification-purpose" -> { stored.setPurpose(OtpPurpose.EMAIL_VERIFICATION); codes.saveAndFlush(stored); }
            case "other-user" -> email = user(UserStatus.ACTIVE).getEmail();
            case "disabled" -> { user.setStatus(UserStatus.DISABLED); users.saveAndFlush(user); }
            case "pending" -> {
                user.setStatus(UserStatus.PENDING_VERIFICATION);
                user.setEmailVerifiedAt(null);
                users.saveAndFlush(user);
            }
            case "missing-user" -> email = "absent@example.com";
            default -> fail("Unknown case");
        }
        ResetPasswordRequest request = new ResetPasswordRequest(email, submitted, "Newpass2!", "Newpass2!");
        assertEquals(ErrorCode.INVALID_OTP, assertThrows(AuthException.class, () -> auth.resetPassword(request)).getErrorCode());
        em.clear();
        User unchanged = users.findById(user.getId()).orElseThrow();
        assertTrue(passwords.matches("Student1!", unchanged.getPasswordHash()));
        assertEquals(0, unchanged.getTokenVersion());
        assertEquals(0, events.stream(PasswordChangedEvent.class).count());
    }

    @Test
    void leadingZeroResetCodeWorksOnceAndPasswordWhitespaceIsPreserved() throws Exception {
        User user = user(UserStatus.ACTIVE);
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        EmailOtpCode stored = codes.findById(lastMail().otpId()).orElseThrow();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        stored.setCodeDigest(HexFormat.of().formatHex(mac.doFinal((user.getId() + ":PASSWORD_RESET:"
                + stored.getId() + ":012345").getBytes(StandardCharsets.UTF_8))));
        codes.saveAndFlush(stored);
        var request = new ResetPasswordRequest(user.getEmail(), "012345", " Newpass2! ", " Newpass2! ");
        auth.resetPassword(request);
        em.clear();
        assertTrue(passwords.matches(" Newpass2! ", users.findById(user.getId()).orElseThrow().getPasswordHash()));
        assertEquals(ErrorCode.INVALID_OTP,
                assertThrows(AuthException.class, () -> auth.resetPassword(request)).getErrorCode());
    }

    @Test
    void invalidResetFieldsCannotConsumeOtpOrChangeAccount() throws Exception {
        User user = user(UserStatus.ACTIVE);
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        OtpMailEvent mail = lastMail();
        var csrf = csrf();
        var valid = Map.of("email", user.getEmail(), "code", mail.code(), "newPassword", "Newpass2!", "confirmPassword", "Newpass2!");
        for (String change : List.of("confirmPassword", "newPassword", "code", "verified")) {
            var body = new java.util.HashMap<String, Object>(valid);
            body.put(change, switch (change) {
                case "confirmPassword" -> "Different1!";
                case "newPassword" -> "weak";
                case "code" -> 123456;
                default -> true;
            });
            mvc.perform(post("/api/v1/auth/reset-password").cookie(csrf.cookie())
                            .header("X-XSRF-TOKEN", csrf.value()).contentType("application/json").content(json.writeValueAsString(body)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        }
        assertNull(codes.findById(mail.otpId()).orElseThrow().getUsedAt());
        assertTrue(passwords.matches("Student1!", user.getPasswordHash()));
        assertEquals(0, events.stream(PasswordChangedEvent.class).count());
    }

    private User user(UserStatus status) {
        User user = new User();
        user.setUsername("Student");
        user.setEmail("be07-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwords.encode("Student1!"));
        user.setStatus(status);
        if (status != UserStatus.PENDING_VERIFICATION) { user.setEmailVerifiedAt(Instant.now()); }
        return users.saveAndFlush(user);
    }

    private OtpMailEvent lastMail() { return events.stream(OtpMailEvent.class).toList().getLast(); }

    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
    }

    private CsrfPair csrf() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        return new CsrfPair(result.getResponse().getCookie("XSRF-TOKEN"),
                json.readTree(result.getResponse().getContentAsString()).get("token").asString());
    }

    private record CsrfPair(Cookie cookie, String value) {}
}
