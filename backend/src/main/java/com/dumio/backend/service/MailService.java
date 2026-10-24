package com.dumio.backend.service;

import com.dumio.backend.event.OtpMailEvent;

public interface MailService {

    void sendOtp(OtpMailEvent event);
}
