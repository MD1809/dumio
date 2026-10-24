package com.dumio.backend.service;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dumio.backend.dto.request.RegisterRequest;
import com.dumio.backend.dto.request.VerifyEmailRequest;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.event.OtpMailEvent;
import com.dumio.backend.repository.EmailOtpCodeRepository;
import com.dumio.backend.repository.UserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
class RegistrationTransactionTests {

    @Autowired AuthService auth;
    @Autowired EmailOtpCodeRepository codes;
    @Autowired ApplicationEvents events;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean JavaMailSender sender;
    @MockitoSpyBean UserRepository users;

    private final List<String> testEmails = new ArrayList<>();

    @AfterEach
    void cleanOnlyAccountsCreatedByThisTest() {
        for (String email : testEmails) {
            users.findByEmail(email).ifPresent(user -> users.deleteById(user.getId()));
        }
    }

    @Test
    void mailIsSentOnWorkerOnlyAfterAccountAndOtpAreCommitted() {
        RegisterRequest request = request();
        AtomicBoolean observedCommittedState = new AtomicBoolean();
        doAnswer(invocation -> {
            SimpleMailMessage message = invocation.getArgument(0);
            var user = users.findByEmail(request.email()).orElseThrow();
            var otp = codes.findByUserIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(user.getId(),
                    com.dumio.backend.enums.OtpPurpose.EMAIL_VERIFICATION).orElseThrow();
            observedCommittedState.set(Thread.currentThread().getName().startsWith("otp-mail-")
                    && otp.getExpiresAt().isAfter(java.time.Instant.now())
                    && message.getText().matches("(?s).*\\b[0-9]{6}\\b.*")
                    && List.of(message.getTo()).contains(request.email()));
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));

        auth.register(request);
        await().atMost(Duration.ofSeconds(5)).untilTrue(observedCommittedState);
        verify(sender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void rollbackDoesNotPersistAccountOtpOrSendMail() {
        RegisterRequest request = request();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            auth.register(request);
            status.setRollbackOnly();
        });
        assertTrue(users.findByEmail(request.email()).isEmpty());
        assertTrue(codes.findById(lastMail().otpId()).isEmpty());
        verifyNoInteractions(sender);
    }

    @Test
    void mailAuthenticationFailureInvalidatesCodeButPreservesPendingAccount() {
        doThrow(new MailAuthenticationException("private-smtp-detail"))
                .when(sender).send(any(SimpleMailMessage.class));
        RegisterRequest request = request();
        var result = auth.register(request);
        UUID otpId = lastMail().otpId();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertNotNull(codes.findById(otpId).orElseThrow().getInvalidatedAt()));
        assertEquals(UserStatus.PENDING_VERIFICATION, users.findById(result.user().id()).orElseThrow().getStatus());
    }

    @Test
    void accountActivationFailureRollsBackOtpConsumption() {
        RegisterRequest request = request();
        var result = auth.register(request);
        OtpMailEvent mail = lastMail();
        verify(sender, timeout(5000)).send(any(SimpleMailMessage.class));
        doThrow(new DataAccessResourceFailureException("storage unavailable"))
                .when(users).activatePendingUser(eq(result.user().id()), any());

        assertThrows(DataAccessResourceFailureException.class,
                () -> auth.verifyEmail(new VerifyEmailRequest(request.email(), mail.code())));
        assertNull(codes.findById(mail.otpId()).orElseThrow().getUsedAt());
        var user = users.findById(result.user().id()).orElseThrow();
        assertEquals(UserStatus.PENDING_VERIFICATION, user.getStatus());
        assertNull(user.getEmailVerifiedAt());
    }

    private RegisterRequest request() {
        String email = "be05-" + UUID.randomUUID() + "@example.com";
        testEmails.add(email);
        return new RegisterRequest("Student", email, "Student1!", "Student1!");
    }

    private OtpMailEvent lastMail() {
        return events.stream(OtpMailEvent.class).toList().getLast();
    }
}
