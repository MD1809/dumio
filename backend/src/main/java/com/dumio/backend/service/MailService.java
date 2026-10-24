package com.dumio.backend.service;

import com.dumio.backend.event.OtpMailEvent;
import com.dumio.backend.event.PasswordChangedEvent;

public interface MailService {

    void sendOtp(OtpMailEvent event);

    void sendPasswordChanged(PasswordChangedEvent event);
}
