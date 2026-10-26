package com.dumio.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.dumio.backend.dto.request.*;
import com.dumio.backend.dto.response.RegisterResponse;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.*;
import com.dumio.backend.event.OtpMailEvent;
import com.dumio.backend.exception.AuthException;
import com.dumio.backend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
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

@SpringBootTest(properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=", "spring.mail.username=test@example.com", "spring.mail.password=unused"})
@AutoConfigureMockMvc
@RecordApplicationEvents
@Transactional
class RegistrationTests {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired PendingRegistrationRepository registrations;
    @Autowired EmailOtpCodeRepository codes;
    @Autowired PasswordEncoder passwords;
    @Autowired ApplicationEvents events;
    @Autowired EntityManager em;
    @Autowired @Qualifier("otpHmacKey") SecretKey key;
    @MockitoBean JavaMailSender sender;

    @Test
    void registerCreatesOnlyTemporaryDataAndVerifyCreatesAnActiveAccount() throws Exception {
        var csrf = csrf();
        String email = UUID.randomUUID() + "@example.com";
        var result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "  Nguyễn   An  ", "email", email.toUpperCase(Locale.ROOT), "password", "Student1!", "confirmPassword", "Student1!")))
                .cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("user").doesNotExist()).andExpect(jsonPath("registrationId").doesNotExist())
                .andExpect(jsonPath("verificationRequired").value(true)).andExpect(jsonPath("expiresInSeconds").value(300)).andReturn();
        assertTrue(users.findByEmail(email).isEmpty());
        Cookie cookie = result.getResponse().getCookie("DUMIO_REGISTRATION");
        assertNotNull(cookie); assertTrue(cookie.isHttpOnly()); assertEquals("Lax", cookie.getAttribute("SameSite"));
        var pending = registrations.findById(UUID.fromString(cookie.getValue())).orElseThrow();
        assertEquals("Nguyễn An", pending.getUsername()); assertTrue(passwords.matches("Student1!", pending.getPasswordHash()));
        var mail = lastMail();
        assertNotEquals(mail.code(), codes.findById(mail.otpId()).orElseThrow().getCodeDigest());
        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "code", mail.code())))
                .cookie(csrf.cookie(), cookie).header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isOk()).andExpect(cookie().maxAge("DUMIO_REGISTRATION", 0));
        em.clear();
        User user = users.findByEmail(email).orElseThrow();
        assertEquals(UserStatus.ACTIVE, user.getStatus()); assertEquals(UserRole.USER, user.getRole());
        assertNotNull(user.getEmailVerifiedAt()); assertEquals("Nguyễn An", user.getUsername());
        assertTrue(passwords.matches("Student1!", user.getPasswordHash()));
        assertTrue(registrations.findById(pending.getId()).isEmpty()); assertTrue(codes.findById(mail.otpId()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong", "expired", "used", "invalidated", "other-registration", "other-purpose", "other-issuance"})
    void invalidCodeNeverCreatesAUser(String scenario) throws Exception {
        var fixture = register(); var code = codes.findById(fixture.mail().otpId()).orElseThrow(); String value = fixture.mail().code();
        switch (scenario) {
            case "wrong" -> value = value.equals("000000") ? "000001" : "000000";
            case "expired" -> em.createNativeQuery("UPDATE email_otp_codes SET created_at=CURRENT_TIMESTAMP-INTERVAL '10 minutes', expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=:id").setParameter("id", code.getId()).executeUpdate();
            case "used" -> codes.consumeRegistrationCode(code.getId(), fixture.response().registrationId(), Instant.now());
            case "invalidated" -> codes.invalidateIfUnused(code.getId(), Instant.now());
            case "other-registration" -> code.setCodeDigest(digest(UUID.randomUUID(), code.getPurpose(), code.getId(), value));
            case "other-purpose" -> code.setCodeDigest(digest(fixture.response().registrationId(), OtpPurpose.PASSWORD_RESET, code.getId(), value));
            case "other-issuance" -> code.setCodeDigest(digest(fixture.response().registrationId(), code.getPurpose(), UUID.randomUUID(), value));
            default -> fail("Unknown scenario");
        }
        codes.flush(); em.clear(); String submitted = value;
        assertEquals(ErrorCode.INVALID_OTP, assertThrows(AuthException.class, () -> auth.verifyEmail(new VerifyEmailRequest(fixture.email(), submitted), fixture.token())).getErrorCode());
        assertTrue(users.findByEmail(fixture.email()).isEmpty());
    }

    @Test
    void cancelDeletesTemporaryDataAndOldCodeCanNeverCreateAnAccount() {
        var fixture = register(); auth.cancelRegistration(fixture.token()); em.clear();
        assertTrue(users.findByEmail(fixture.email()).isEmpty()); assertTrue(registrations.findById(fixture.response().registrationId()).isEmpty());
        assertTrue(codes.findById(fixture.mail().otpId()).isEmpty()); auth.cancelRegistration(fixture.token());
        assertEquals(ErrorCode.INVALID_OTP, assertThrows(AuthException.class, () -> auth.verifyEmail(new VerifyEmailRequest(fixture.email(), fixture.mail().code()), fixture.token())).getErrorCode());
    }

    @Test
    void correctCodeRequiresTheMatchingCookieAndEmail() {
        var fixture = register();
        for (String token : Arrays.asList(null, "malformed", UUID.randomUUID().toString())) {
            assertEquals(ErrorCode.INVALID_OTP, assertThrows(AuthException.class, () -> auth.verifyEmail(new VerifyEmailRequest(fixture.email(), fixture.mail().code()), token)).getErrorCode());
        }
        assertEquals(ErrorCode.INVALID_OTP, assertThrows(AuthException.class, () -> auth.verifyEmail(new VerifyEmailRequest("other@example.com", fixture.mail().code()), fixture.token())).getErrorCode());
        assertTrue(users.findByEmail(fixture.email()).isEmpty());
    }

    @Test
    void cancelEndpointClearsCookieAndInvalidatesTheRegistration() throws Exception {
        var fixture = register(); var csrf = csrf();
        mvc.perform(post("/api/v1/auth/cancel-registration")
                .cookie(csrf.cookie(), new Cookie("DUMIO_REGISTRATION", fixture.token())).header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("DUMIO_REGISTRATION", 0));
        em.clear();
        assertTrue(registrations.findById(fixture.response().registrationId()).isEmpty());
        assertTrue(users.findByEmail(fixture.email()).isEmpty());
    }

    @Test
    void resendCreatesNoUserAndVerificationAcceptsLeadingZerosOnce() throws Exception {
        var fixture = register(); auth.resendVerification(new ResendVerificationRequest(fixture.email()), fixture.token()); var replacement = lastMail(); em.clear();
        assertNotNull(codes.findById(fixture.mail().otpId()).orElseThrow().getInvalidatedAt()); assertTrue(users.findByEmail(fixture.email()).isEmpty());
        var code = codes.findById(replacement.otpId()).orElseThrow(); code.setCodeDigest(digest(fixture.response().registrationId(), OtpPurpose.EMAIL_VERIFICATION, code.getId(), "012345")); codes.saveAndFlush(code);
        auth.verifyEmail(new VerifyEmailRequest(fixture.email(), "012345"), fixture.token()); em.clear();
        assertEquals(UserStatus.ACTIVE, users.findByEmail(fixture.email()).orElseThrow().getStatus());
        assertEquals(ErrorCode.INVALID_OTP, assertThrows(AuthException.class, () -> auth.verifyEmail(new VerifyEmailRequest(fixture.email(), "012345"), fixture.token())).getErrorCode());
    }

    @Test
    void pendingRegistrationCanRestartWithoutOverwritingAnotherAttempt() {
        var fixture = register(); var second = auth.register(new RegisterRequest("Different", fixture.email(), "Different1!", "Different1!"));
        assertNotEquals(fixture.response().registrationId(), second.registrationId());
        assertEquals("Student", registrations.findById(fixture.response().registrationId()).orElseThrow().getUsername());
        assertTrue(users.findByEmail(fixture.email()).isEmpty());
    }

    @Test
    void expiredRegistrationRejectsEvenTheCorrectCode() {
        var fixture = register();
        em.createNativeQuery("UPDATE pending_registrations SET created_at=CURRENT_TIMESTAMP-INTERVAL '10 minutes', expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=:id").setParameter("id", fixture.response().registrationId()).executeUpdate(); em.clear();
        assertEquals(ErrorCode.INVALID_OTP, assertThrows(AuthException.class, () -> auth.verifyEmail(new VerifyEmailRequest(fixture.email(), fixture.mail().code()), fixture.token())).getErrorCode());
        assertTrue(users.findByEmail(fixture.email()).isEmpty());
    }

    private Fixture register() {
        String email = UUID.randomUUID() + "@example.com";
        var response = auth.register(new RegisterRequest("Student", email, "Student1!", "Student1!")); return new Fixture(email, response, lastMail());
    }
    private OtpMailEvent lastMail() { return events.stream(OtpMailEvent.class).toList().getLast(); }
    private String digest(UUID subject, OtpPurpose purpose, UUID id, String code) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(key); return HexFormat.of().formatHex(mac.doFinal((subject + ":" + purpose + ":" + id + ":" + code).getBytes(StandardCharsets.UTF_8)));
    }
    private CsrfPair csrf() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf")).andReturn(); return new CsrfPair(result.getResponse().getCookie("XSRF-TOKEN"), json.readTree(result.getResponse().getContentAsString()).get("token").asText());
    }
    private record CsrfPair(Cookie cookie, String token) {}
    private record Fixture(String email, RegisterResponse response, OtpMailEvent mail) { String token() { return response.registrationId().toString(); } }
}
