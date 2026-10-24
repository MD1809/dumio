package com.dumio.backend.service.impl;

import com.dumio.backend.config.OtpConfig;
import com.dumio.backend.entity.EmailOtpCode;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.ErrorCode;
import com.dumio.backend.enums.OtpPurpose;
import com.dumio.backend.event.OtpMailEvent;
import com.dumio.backend.exception.AuthException;
import com.dumio.backend.repository.EmailOtpCodeRepository;
import com.dumio.backend.service.OtpService;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OtpServiceImpl implements OtpService {

    private final EmailOtpCodeRepository codes;
    private final ApplicationEventPublisher events;
    private final SecretKey hmacKey;
    private final SecureRandom random = new SecureRandom();

    public OtpServiceImpl(EmailOtpCodeRepository codes, ApplicationEventPublisher events,
            @Qualifier("otpHmacKey") SecretKey hmacKey) {
        this.codes = codes;
        this.events = events;
        this.hmacKey = hmacKey;
    }

    @Override
    @Transactional
    public void issue(User user, OtpPurpose purpose) {
        Instant now = Instant.now();
        codes.invalidateAvailableByUserAndPurpose(user.getId(), purpose, now);
        String value = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        EmailOtpCode otp = new EmailOtpCode();
        // Assign on the server before hashing, so the digest is bound to this issuance.
        otp.setId(UUID.randomUUID());
        otp.setUser(user);
        otp.setPurpose(purpose);
        otp.setCreatedAt(now);
        otp.setExpiresAt(now.plusSeconds(OtpConfig.OTP_TTL_SECONDS));
        otp.setCodeDigest(digest(user.getId(), purpose, otp.getId(), value));
        codes.saveAndFlush(otp);
        events.publishEvent(new OtpMailEvent(otp.getId(), user.getEmail(), value, purpose, otp.getExpiresAt()));
    }

    @Override
    @Transactional
    public void consume(User user, OtpPurpose purpose, String code) {
        if (code == null || !code.matches("[0-9]{6}")) {
            throw new AuthException(ErrorCode.INVALID_OTP);
        }
        EmailOtpCode otp = codes.findByUserIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(user.getId(), purpose)
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_OTP));
        Instant now = Instant.now();
        String expected = digest(user.getId(), purpose, otp.getId(), code);
        if (!otp.getExpiresAt().isAfter(now) || !MessageDigest.isEqual(
                otp.getCodeDigest().getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII))) {
            throw new AuthException(ErrorCode.INVALID_OTP);
        }
        if (codes.consumeIfUsable(otp.getId(), user.getId(), purpose, now) != 1) {
            throw new AuthException(ErrorCode.INVALID_OTP);
        }
    }

    @Override
    @Transactional
    public void invalidateAfterDeliveryFailure(UUID otpId) {
        // The async mail thread starts a fresh default transaction, after the issuing transaction committed.
        codes.invalidateIfUnused(otpId, Instant.now());
    }

    @Override
    @Transactional
    public void invalidateAvailable(UUID userId, OtpPurpose purpose) {
        codes.invalidateAvailableByUserAndPurpose(userId, purpose, Instant.now());
    }

    private String digest(UUID userId, OtpPurpose purpose, UUID otpId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            String input = userId + ":" + purpose.name() + ":" + otpId + ":" + code;
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("OTP signing unavailable");
        }
    }
}
