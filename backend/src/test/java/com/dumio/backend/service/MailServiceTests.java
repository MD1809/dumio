package com.dumio.backend.service;

import static org.mockito.Mockito.*;

import com.dumio.backend.enums.OtpPurpose;
import com.dumio.backend.event.OtpMailEvent;
import com.dumio.backend.service.impl.MailServiceImpl;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class MailServiceTests {

    @Test
    void deliveryFailureInvalidatesOnlyTheIssuanceThatFailed() {
        JavaMailSender sender = mock(JavaMailSender.class);
        OtpService otp = mock(OtpService.class);
        var service = new MailServiceImpl(sender, otp, "sender@example.com");
        var event = new OtpMailEvent(UUID.randomUUID(), "recipient@example.com", "012345",
                OtpPurpose.EMAIL_VERIFICATION, Instant.now().plusSeconds(300));
        doThrow(new MailSendException("private-smtp-detail")).when(sender).send(any(SimpleMailMessage.class));
        service.sendOtp(event);
        verify(otp).invalidateAfterDeliveryFailure(event.otpId());
        verifyNoMoreInteractions(otp);
    }

    @Test
    void doesNotSendAlreadyExpiredCode() {
        JavaMailSender sender = mock(JavaMailSender.class);
        OtpService otp = mock(OtpService.class);
        var service = new MailServiceImpl(sender, otp, "sender@example.com");
        var event = new OtpMailEvent(UUID.randomUUID(), "recipient@example.com", "012345",
                OtpPurpose.EMAIL_VERIFICATION, Instant.now().minusSeconds(1));
        service.sendOtp(event);
        verifyNoInteractions(sender);
        verify(otp).invalidateAfterDeliveryFailure(event.otpId());
    }
}
