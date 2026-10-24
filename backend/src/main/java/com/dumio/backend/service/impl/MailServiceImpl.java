package com.dumio.backend.service.impl;

import com.dumio.backend.enums.OtpPurpose;
import com.dumio.backend.event.OtpMailEvent;
import com.dumio.backend.service.MailService;
import com.dumio.backend.service.OtpService;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Service
public class MailServiceImpl implements MailService {

    private static final String OTP_BODY = """
            Xin chào,

            Mã %s cho tài khoản Dumio của bạn là:

            %s

            Mã có hiệu lực trong 5 phút kể từ khi được tạo và chỉ sử dụng một lần.
            Không chia sẻ mã này với người khác.

            Nếu bạn không yêu cầu thao tác này, hãy bỏ qua email.

            Dumio
            """;

    private final JavaMailSender sender;
    private final OtpService otpService;
    private final String from;

    public MailServiceImpl(JavaMailSender sender, OtpService otpService,
            @Value("${spring.mail.username}") String from) {
        this.sender = sender;
        this.otpService = otpService;
        this.from = from;
    }

    @Override
    @Async("otpMailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendOtp(OtpMailEvent event) {
        try {
            if (!event.expiresAt().isAfter(Instant.now())) {
                otpService.invalidateAfterDeliveryFailure(event.otpId());
                return;
            }
            String purpose = event.purpose() == OtpPurpose.EMAIL_VERIFICATION
                    ? "xác minh email" : "đặt lại mật khẩu";
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(event.email());
            message.setSubject("Dumio - Mã " + purpose);
            message.setText(OTP_BODY.formatted(purpose, event.code()));
            sender.send(message);
        } catch (MailException ex) {
            log.warn("OTP email delivery failed: {}", ex.getClass().getSimpleName());
            invalidateFailedDelivery(event);
        } catch (RuntimeException ex) {
            // Avoid async's default exception logger printing sensitive nested exception messages.
            log.error("OTP email processing failed: {}", ex.getClass().getSimpleName());
            invalidateFailedDelivery(event);
        }
    }

    private void invalidateFailedDelivery(OtpMailEvent event) {
        try {
            otpService.invalidateAfterDeliveryFailure(event.otpId());
        } catch (RuntimeException ex) {
            // If storage is unavailable, expiry/resend still makes the code unusable later.
            log.error("OTP delivery failure could not be recorded: {}", ex.getClass().getSimpleName());
        }
    }
}
