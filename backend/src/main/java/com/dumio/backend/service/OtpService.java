package com.dumio.backend.service;

import com.dumio.backend.entity.User;
import com.dumio.backend.entity.PendingRegistration;
import com.dumio.backend.enums.OtpPurpose;
import java.util.UUID;

public interface OtpService {

    void issue(PendingRegistration registration);

    void consume(PendingRegistration registration, String code);

    void issue(User user, OtpPurpose purpose);

    void consume(User user, OtpPurpose purpose, String code);

    void invalidateAfterDeliveryFailure(UUID otpId);

    void invalidateAvailable(UUID userId, OtpPurpose purpose);
}
