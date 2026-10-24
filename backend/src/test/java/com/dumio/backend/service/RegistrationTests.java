package com.dumio.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dumio.backend.dto.request.RegisterRequest;
import com.dumio.backend.dto.request.ResendVerificationRequest;
import com.dumio.backend.dto.request.VerifyEmailRequest;
import com.dumio.backend.entity.EmailOtpCode;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.ErrorCode;
import com.dumio.backend.enums.OtpPurpose;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.event.OtpMailEvent;
import com.dumio.backend.exception.AuthException;
import com.dumio.backend.repository.EmailOtpCodeRepository;
import com.dumio.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
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
class RegistrationTests {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired AuthService auth;
    @Autowired OtpService otp;
    @Autowired UserRepository users;
    @Autowired EmailOtpCodeRepository codes;
    @Autowired PasswordEncoder passwords;
    @Autowired ApplicationEvents events;
    @Autowired EntityManager em;
    @Autowired @Qualifier("otpHmacKey") SecretKey key;
    @MockitoBean JavaMailSender sender;

    @Test
    void realRegistrationAndVerificationEndpointsFollowContract() throws Exception {
        var csrf = csrf();
        String email = UUID.randomUUID() + "@example.com";
        // Construct the wire payload explicitly; request DTO passwords are WRITE_ONLY.
        String body = "{\"username\":\"  Nguyễn   An  \",\"email\":\"  "
                + email.toUpperCase(java.util.Locale.ROOT) + "  \","
                + "\"password\":\"Student1!\",\"confirmPassword\":\"Student1!\"}";
        var result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)
                        .cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isCreated()).andExpect(jsonPath("user.username").value("Nguyễn An"))
                .andExpect(jsonPath("user.email").value(email)).andExpect(jsonPath("user.role").value("USER"))
                .andExpect(jsonPath("verificationRequired").value(true))
                .andExpect(jsonPath("expiresInSeconds").value(300))
                .andExpect(jsonPath("accessToken").doesNotExist()).andExpect(jsonPath("code").doesNotExist())
                .andExpect(jsonPath("user.passwordHash").doesNotExist()).andReturn();
        assertNull(result.getResponse().getCookie("DUMIO_REFRESH"));
        User user = users.findByEmail(email).orElseThrow();
        assertEquals(UserStatus.PENDING_VERIFICATION, user.getStatus());
        assertNull(user.getEmailVerifiedAt());
        assertTrue(passwords.matches("Student1!", user.getPasswordHash()));
        OtpMailEvent mail = lastMail();
        assertTrue(mail.code().matches("[0-9]{6}"));
        EmailOtpCode stored = codes.findById(mail.otpId()).orElseThrow();
        assertEquals(300, stored.getExpiresAt().getEpochSecond() - stored.getCreatedAt().getEpochSecond());
        assertEquals(64, stored.getCodeDigest().length());
        assertNotEquals(mail.code(), stored.getCodeDigest());
        assertFalse(mail.toString().contains(mail.code()));

        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + mail.code() + "\"}")
                        .cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("message").value("Thao tác thành công"));
        em.clear();
        assertEquals(UserStatus.ACTIVE, users.findById(user.getId()).orElseThrow().getStatus());
        assertNotNull(users.findById(user.getId()).orElseThrow().getEmailVerifiedAt());
        assertNotNull(codes.findById(stored.getId()).orElseThrow().getUsedAt());
        org.mockito.Mockito.verifyNoInteractions(sender); // The surrounding test transaction has not committed.
    }

    @Test
    void duplicatePendingRegistrationDoesNotOverwriteUserOrSendAnotherOtp() {
        var registered = register();
        User before = users.findById(registered.user().id()).orElseThrow();
        String originalHash = before.getPasswordHash();
        long mailCount = events.stream(OtpMailEvent.class).count();
        var failure = assertThrows(AuthException.class, () -> auth.register(new RegisterRequest("Overwrite",
                before.getEmail().toUpperCase(java.util.Locale.ROOT), "Different1!", "Different1!")));
        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, failure.getErrorCode());
        assertEquals(originalHash, before.getPasswordHash());
        assertEquals("Student", before.getUsername());
        assertEquals(mailCount, events.stream(OtpMailEvent.class).count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong", "expired", "used", "invalidated", "other-user", "other-purpose", "other-issuance"})
    void invalidOtpDoesNotActivateAccount(String scenario) throws Exception {
        var response = register();
        User user = users.findById(response.user().id()).orElseThrow();
        OtpMailEvent mail = lastMail();
        EmailOtpCode code = codes.findById(mail.otpId()).orElseThrow();
        String value = mail.code();
        switch (scenario) {
            case "wrong" -> value = value.equals("000000") ? "000001" : "000000";
            case "expired" -> {
                // created_at is immutable in JPA; set both timestamps only for this expiry fixture.
                em.createNativeQuery("""
                        UPDATE email_otp_codes
                        SET created_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes',
                            expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                        WHERE id = :id
                        """).setParameter("id", code.getId()).executeUpdate();
                em.refresh(code);
            }
            case "used" -> codes.consumeIfUsable(code.getId(), user.getId(), code.getPurpose(), Instant.now());
            case "invalidated" -> codes.invalidateIfUnused(code.getId(), Instant.now());
            case "other-user" -> code.setCodeDigest(digest(UUID.randomUUID(), code.getPurpose(), code.getId(), value));
            case "other-purpose" -> code.setCodeDigest(digest(user.getId(), OtpPurpose.PASSWORD_RESET, code.getId(), value));
            case "other-issuance" -> code.setCodeDigest(digest(user.getId(), code.getPurpose(), UUID.randomUUID(), value));
            default -> fail("Unknown scenario");
        }
        codes.flush();
        String submitted = value;
        assertEquals(ErrorCode.INVALID_OTP, assertThrows(AuthException.class,
                () -> auth.verifyEmail(new VerifyEmailRequest(user.getEmail(), submitted))).getErrorCode());
        assertEquals(UserStatus.PENDING_VERIFICATION, user.getStatus());
        assertNull(user.getEmailVerifiedAt());
    }

    @Test
    void acceptsLeadingZeroCodeAndRejectsItAfterVerification() throws Exception {
        var response = register();
        User user = users.findById(response.user().id()).orElseThrow();
        EmailOtpCode code = codes.findById(lastMail().otpId()).orElseThrow();
        code.setCodeDigest(digest(user.getId(), code.getPurpose(), code.getId(), "012345"));
        codes.saveAndFlush(code);
        auth.verifyEmail(new VerifyEmailRequest(user.getEmail(), "012345"));
        em.clear();
        assertNotNull(codes.findById(code.getId()).orElseThrow().getUsedAt());
        assertEquals(ErrorCode.INVALID_OTP, assertThrows(AuthException.class,
                () -> auth.verifyEmail(new VerifyEmailRequest(user.getEmail(), "012345"))).getErrorCode());
    }

    @Test
    void resendReplacesOnlyVerificationCodeAndReturnsSameResponseForIneligibleEmails() {
        var response = register();
        User user = users.findById(response.user().id()).orElseThrow();
        UUID firstId = lastMail().otpId();
        otp.issue(user, OtpPurpose.PASSWORD_RESET);
        UUID resetId = lastMail().otpId();
        var pendingResponse = auth.resendVerification(new ResendVerificationRequest(user.getEmail()));
        UUID replacementId = lastMail().otpId();
        em.clear();
        assertNotNull(codes.findById(firstId).orElseThrow().getInvalidatedAt());
        assertNull(codes.findById(resetId).orElseThrow().getInvalidatedAt());
        assertNull(codes.findById(replacementId).orElseThrow().getInvalidatedAt());
        long mailCount = events.stream(OtpMailEvent.class).count();
        assertEquals(pendingResponse, auth.resendVerification(new ResendVerificationRequest("absent@example.com")));
        user = users.findById(user.getId()).orElseThrow();
        user.setStatus(UserStatus.DISABLED);
        users.saveAndFlush(user);
        assertEquals(pendingResponse, auth.resendVerification(new ResendVerificationRequest(user.getEmail())));
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        users.saveAndFlush(user);
        assertEquals(pendingResponse, auth.resendVerification(new ResendVerificationRequest(user.getEmail())));
        assertEquals(mailCount, events.stream(OtpMailEvent.class).count());
    }

    @Test
    void disabledAccountCannotBeActivatedEvenWithCorrectCode() {
        var response = register();
        User user = users.findById(response.user().id()).orElseThrow();
        String code = lastMail().code();
        user.setStatus(UserStatus.DISABLED);
        users.saveAndFlush(user);
        assertEquals(ErrorCode.INVALID_OTP, assertThrows(AuthException.class,
                () -> auth.verifyEmail(new VerifyEmailRequest(user.getEmail(), code))).getErrorCode());
        assertNull(codes.findById(lastMail().otpId()).orElseThrow().getUsedAt());
    }

    @Test
    void invalidInputAndClientAssignedRoleAreRejectedByActualApiBeforePersistence() throws Exception {
        var csrf = csrf();
        for (String body : List.of("{}", """
                {"username":"Student","email":"test@example.com","password":"Student1!",
                 "confirmPassword":"Different1!"}
                """, """
                {"username":"Student","email":"test@example.com","password":"Student1!",
                 "confirmPassword":"Student1!","role":"ADMIN"}
                """)) {
            mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)
                            .cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        }
        assertEquals(0, events.stream(OtpMailEvent.class).count());
    }

    @Test
    void actualApiReturns409ForDuplicateEmailAnd400ForInvalidOtp() throws Exception {
        var response = register();
        String email = response.user().email();
        var csrf = csrf();
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Student\",\"email\":\"" + email + "\","
                                + "\"password\":\"Student1!\",\"confirmPassword\":\"Student1!\"}")
                        .cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("EMAIL_ALREADY_EXISTS"));
        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"absent@example.com\",\"code\":\"012345\"}")
                        .cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_OTP"));
        mvc.perform(post("/api/v1/auth/resend-verification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"absent@example.com\"}")
                        .cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("expiresInSeconds").value(300));
    }

    private com.dumio.backend.dto.response.RegisterResponse register() {
        return auth.register(new RegisterRequest("Student", UUID.randomUUID() + "@example.com", "Student1!", "Student1!"));
    }

    private OtpMailEvent lastMail() {
        return events.stream(OtpMailEvent.class).toList().getLast();
    }

    private String digest(UUID userId, OtpPurpose purpose, UUID id, String code) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        return HexFormat.of().formatHex(mac.doFinal((userId + ":" + purpose.name() + ":" + id + ":" + code)
                .getBytes(StandardCharsets.UTF_8)));
    }

    private CsrfPair csrf() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        return new CsrfPair(result.getResponse().getCookie("XSRF-TOKEN"),
                json.readTree(result.getResponse().getContentAsString()).get("token").asText());
    }

    private record CsrfPair(Cookie cookie, String token) {}
}
