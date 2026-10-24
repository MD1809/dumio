package com.dumio.backend.event;

import com.dumio.backend.enums.OtpPurpose;
import java.time.Instant;
import java.util.UUID;

/** In-process payload only. Never return it from an API or log its contents. */
public record OtpMailEvent(UUID otpId, String email, String code, OtpPurpose purpose, Instant expiresAt) {

    @Override
    public String toString() {
        return "OtpMailEvent[redacted]";
    }
}
