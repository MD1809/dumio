package com.dumio.backend.service;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dumio.backend.dto.request.*;
import com.dumio.backend.event.OtpMailEvent;
import com.dumio.backend.exception.AuthException;
import com.dumio.backend.repository.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.context.event.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=", "spring.mail.username=test@example.com", "spring.mail.password=unused"})
@RecordApplicationEvents
class RegistrationTransactionTests {
    @Autowired AuthService auth;
    @Autowired EmailOtpCodeRepository codes;
    @Autowired PendingRegistrationRepository registrations;
    @Autowired ApplicationEvents events;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean JavaMailSender sender;
    @MockitoSpyBean UserRepository users;
    private final List<String> emails = new ArrayList<>();
    private final List<UUID> pendingIds = new ArrayList<>();

    @AfterEach void cleanup() {
        for (UUID id : pendingIds) auth.cancelRegistration(id.toString());
        for (String email : emails) users.findByEmail(email).ifPresent(user -> users.deleteById(user.getId()));
    }

    @Test void mailRunsAfterTemporaryDataCommitsWithoutCreatingAUser() {
        var request = request(); var observed = new AtomicBoolean();
        doAnswer(invocation -> { SimpleMailMessage message = invocation.getArgument(0); observed.set(Thread.currentThread().getName().startsWith("otp-mail-") && users.findByEmail(request.email()).isEmpty() && message.getText().matches("(?s).*\\b[0-9]{6}\\b.*")); return null; }).when(sender).send(any(SimpleMailMessage.class));
        var result = auth.register(request); pendingIds.add(result.registrationId());
        await().atMost(Duration.ofSeconds(5)).untilTrue(observed);
        assertTrue(registrations.findById(result.registrationId()).isPresent()); assertTrue(codes.findById(lastMail().otpId()).isPresent());
    }

    @Test void rollbackLeavesNoAccountNoTemporaryDataAndNoEmail() {
        var request = request();
        new TransactionTemplate(transactions).executeWithoutResult(status -> { var result = auth.register(request); pendingIds.add(result.registrationId()); status.setRollbackOnly(); });
        assertTrue(users.findByEmail(request.email()).isEmpty()); assertTrue(registrations.findById(pendingIds.getFirst()).isEmpty()); assertTrue(codes.findById(lastMail().otpId()).isEmpty()); verifyNoInteractions(sender);
    }

    @Test void mailFailureInvalidatesCodeWithoutCreatingAUser() {
        doThrow(new MailAuthenticationException("private-smtp-detail")).when(sender).send(any(SimpleMailMessage.class));
        var request = request(); var result = auth.register(request); pendingIds.add(result.registrationId()); UUID otpId = lastMail().otpId();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertNotNull(codes.findById(otpId).orElseThrow().getInvalidatedAt()));
        assertTrue(users.findByEmail(request.email()).isEmpty());
    }

    @Test void accountInsertFailureRollsBackOtpConsumption() {
        var request = request(); var result = auth.register(request); pendingIds.add(result.registrationId()); var mail = lastMail();
        verify(sender, timeout(5000)).send(any(SimpleMailMessage.class));
        doThrow(new DataAccessResourceFailureException("storage unavailable")).when(users).saveAndFlush(any());
        assertThrows(DataAccessResourceFailureException.class, () -> auth.verifyEmail(new VerifyEmailRequest(request.email(), mail.code()), result.registrationId().toString()));
        assertNull(codes.findById(mail.otpId()).orElseThrow().getUsedAt()); assertTrue(registrations.findById(result.registrationId()).isPresent()); assertTrue(users.findByEmail(request.email()).isEmpty());
    }

    @Test void concurrentVerificationsCreateExactlyOneAccount() throws Exception {
        var request = request(); var result = auth.register(request); pendingIds.add(result.registrationId()); var mail = lastMail(); verify(sender, timeout(5000)).send(any(SimpleMailMessage.class));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> verify = () -> { start.await(); try { auth.verifyEmail(new VerifyEmailRequest(request.email(), mail.code()), result.registrationId().toString()); return true; } catch (AuthException failure) { return false; } };
            var first = executor.submit(verify); var second = executor.submit(verify); start.countDown(); assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertTrue(users.findByEmail(request.email()).isPresent()); assertTrue(registrations.findById(result.registrationId()).isEmpty());
    }

    private RegisterRequest request() { String email = "registration-tx-" + UUID.randomUUID() + "@example.com"; emails.add(email); return new RegisterRequest("Student", email, "Student1!", "Student1!"); }
    private OtpMailEvent lastMail() { return events.stream(OtpMailEvent.class).toList().getLast(); }
}