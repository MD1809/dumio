package com.dumio.backend.service;

import com.dumio.backend.dto.request.RegisterRequest;
import com.dumio.backend.dto.request.ResendVerificationRequest;
import com.dumio.backend.dto.request.VerifyEmailRequest;
import com.dumio.backend.dto.response.MessageResponse;
import com.dumio.backend.dto.response.OtpRequestResponse;
import com.dumio.backend.dto.response.RegisterResponse;

public interface AuthService {

    RegisterResponse register(RegisterRequest request);

    MessageResponse verifyEmail(VerifyEmailRequest request);

    OtpRequestResponse resendVerification(ResendVerificationRequest request);
}
