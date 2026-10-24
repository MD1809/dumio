package com.dumio.backend.config;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OtpConfig {

    public static final long OTP_TTL_SECONDS = 300;

    @Bean
    SecretKey otpHmacKey(@Value("${app.otp.secret}") String encodedSecret) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("OTP_SECRET must be valid Base64");
        }
        if (bytes.length < 32) {
            throw new IllegalArgumentException("OTP_SECRET must contain at least 32 random bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
