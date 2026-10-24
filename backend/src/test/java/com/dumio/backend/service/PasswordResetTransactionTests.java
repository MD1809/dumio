package com.dumio.backend.service;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dumio.backend.dto.request.ForgotPasswordRequest;
import com.dumio.backend.dto.request.LoginRequest;
import com.dumio.backend.dto.request.ResetPasswordRequest;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.ErrorCode;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.event.OtpMailEvent;
import com.dumio.backend.exception.AuthException;
import com.dumio.backend.repository.EmailOtpCodeRepository;
import com.dumio.backend.repository.RefreshTokenRepository;
import com.dumio.backend.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "spring.mail.username=test@example.com", "spring.mail.password=unused"})
@RecordApplicationEvents
class PasswordResetTransactionTests {

    @Autowired AuthService auth;
    @Autowired EmailOtpCodeRepository codes;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtDecoder decoder;
    @Autowired ApplicationEvents events;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean RefreshTokenRepository tokens;
    @MockitoBean JavaMailSender sender;

    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void removeOnlyAccountsCreatedByThisTest() {
        for (UUID id : createdUsers) { users.deleteById(id); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"password-write", "refresh-revocation", "version-update", "account-no-longer-active"})
    void failuresRollBackPasswordOtpAndEveryTokenChange(String failure) throws Exception {
        User user = user();
        var pair = auth.login(new LoginRequest(user.getEmail(), "Student1!"));
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        OtpMailEvent mail = lastMail();
        verify(sender, timeout(5000)).send(any(SimpleMailMessage.class));
        switch (failure) {
            case "password-write" -> doThrow(storageFailure()).when(users).updatePasswordIfActive(eq(user.getId()), anyString(), any());
            case "refresh-revocation" -> doThrow(storageFailure()).when(tokens).revokeByUserId(eq(user.getId()), any());
            case "version-update" -> doThrow(storageFailure()).when(users).incrementTokenVersion(eq(user.getId()), any());
            case "account-no-longer-active" -> doReturn(0).when(users).updatePasswordIfActive(eq(user.getId()), anyString(), any());
            default -> fail("Unknown failure");
        }
        var request = new ResetPasswordRequest(user.getEmail(), mail.code(), "Newpass2!", "Newpass2!");
        if (failure.equals("account-no-longer-active")) {
            assertEquals(ErrorCode.INVALID_OTP, assertThrows(AuthException.class, () -> auth.resetPassword(request)).getErrorCode());
        } else {
            assertThrows(DataAccessResourceFailureException.class, () -> auth.resetPassword(request));
        }
        User unchanged = users.findById(user.getId()).orElseThrow();
        assertTrue(passwords.matches("Student1!", unchanged.getPasswordHash()));
        assertEquals(0, unchanged.getTokenVersion());
        assertNull(codes.findById(mail.otpId()).orElseThrow().getUsedAt());
        assertNull(tokens.findByTokenHash(hash(pair.refreshToken())).orElseThrow().getRevokedAt());
        assertNotNull(decoder.decode(pair.response().accessToken()));
        verify(sender, times(1)).send(any(SimpleMailMessage.class)); // Only the earlier OTP.
    }

    @Test
    void notificationRunsAfterCommitWithoutIncludingPasswordOrOtp() throws Exception {
        User user = user();
        var pair = auth.login(new LoginRequest(user.getEmail(), "Student1!"));
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        OtpMailEvent mail = lastMail();
        verify(sender, timeout(5000)).send(any(SimpleMailMessage.class));
        AtomicBoolean observedCommittedReset = new AtomicBoolean();
        doAnswer(invocation -> {
            SimpleMailMessage message = invocation.getArgument(0);
            if (message.getSubject().equals("Dumio - Mật khẩu đã được thay đổi")) {
                var changed = users.findById(user.getId()).orElseThrow();
                boolean revoked = tokens.findByTokenHash(hash(pair.refreshToken())).orElseThrow().getRevokedAt() != null;
                observedCommittedReset.set(Thread.currentThread().getName().startsWith("otp-mail-")
                        && passwords.matches("Newpass2!", changed.getPasswordHash()) && changed.getTokenVersion() == 1
                        && revoked && !message.getText().contains("Newpass2!")
                        && !message.getText().contains("Student1!") && !message.getText().contains(mail.code()));
            }
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));
        auth.resetPassword(new ResetPasswordRequest(user.getEmail(), mail.code(), "Newpass2!", "Newpass2!"));
        await().atMost(Duration.ofSeconds(5)).untilTrue(observedCommittedReset);
    }

    @Test
    void notificationFailureDoesNotUndoSuccessfullyChangedPassword() throws Exception {
        User user = user();
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        OtpMailEvent mail = lastMail();
        verify(sender, timeout(5000)).send(any(SimpleMailMessage.class));
        AtomicBoolean notificationAttempted = new AtomicBoolean();
        doAnswer(invocation -> {
            SimpleMailMessage message = invocation.getArgument(0);
            if (message.getSubject().equals("Dumio - Mật khẩu đã được thay đổi")) {
                notificationAttempted.set(true);
                throw new MailSendException("private-smtp-detail");
            }
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));
        assertEquals("Thao tác thành công", auth.resetPassword(
                new ResetPasswordRequest(user.getEmail(), mail.code(), "Newpass2!", "Newpass2!")).message());
        await().atMost(Duration.ofSeconds(5)).untilTrue(notificationAttempted);
        assertTrue(passwords.matches("Newpass2!", users.findById(user.getId()).orElseThrow().getPasswordHash()));
        assertNotNull(codes.findById(mail.otpId()).orElseThrow().getUsedAt());
        assertNotNull(auth.login(new LoginRequest(user.getEmail(), "Newpass2!")).response().accessToken());
    }

    @Test
    void rolledBackResetDoesNotSendChangeNotification() {
        User user = user();
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        OtpMailEvent mail = lastMail();
        verify(sender, timeout(5000)).send(any(SimpleMailMessage.class));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            auth.resetPassword(new ResetPasswordRequest(user.getEmail(), mail.code(), "Newpass2!", "Newpass2!"));
            status.setRollbackOnly();
        });
        assertTrue(passwords.matches("Student1!", users.findById(user.getId()).orElseThrow().getPasswordHash()));
        assertNull(codes.findById(mail.otpId()).orElseThrow().getUsedAt());
        verify(sender, times(1)).send(any(SimpleMailMessage.class));
    }

    private User user() {
        User user = new User();
        user.setUsername("Student");
        user.setEmail("be07-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwords.encode("Student1!"));
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user = users.saveAndFlush(user);
        createdUsers.add(user.getId());
        return user;
    }

    private OtpMailEvent lastMail() { return events.stream(OtpMailEvent.class).toList().getLast(); }

    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
    }

    private DataAccessResourceFailureException storageFailure() {
        return new DataAccessResourceFailureException("private-storage-detail");
    }
}
