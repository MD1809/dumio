package com.dumio.backend.service.impl;

import com.dumio.backend.config.OtpConfig;
import com.dumio.backend.dto.request.RegisterRequest;
import com.dumio.backend.dto.request.ResendVerificationRequest;
import com.dumio.backend.dto.request.VerifyEmailRequest;
import com.dumio.backend.dto.response.MessageResponse;
import com.dumio.backend.dto.response.OtpRequestResponse;
import com.dumio.backend.dto.response.RegisterResponse;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.ErrorCode;
import com.dumio.backend.enums.OtpPurpose;
import com.dumio.backend.enums.UserRole;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.exception.AuthException;
import com.dumio.backend.mapper.UserMapper;
import com.dumio.backend.repository.UserRepository;
import com.dumio.backend.service.AuthService;
import com.dumio.backend.service.OtpService;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository users;
    private final OtpService otpService;
    private final PasswordEncoder passwords;
    private final UserMapper mapper;

    public AuthServiceImpl(UserRepository users, OtpService otpService, PasswordEncoder passwords, UserMapper mapper) {
        this.users = users;
        this.otpService = otpService;
        this.passwords = passwords;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (users.existsByEmail(request.email())) {
            throw new AuthException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(passwords.encode(request.password()));
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user = users.saveAndFlush(user);
        otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION);
        return new RegisterResponse(mapper.toResponse(user), true, OtpConfig.OTP_TTL_SECONDS);
    }

    @Override
    @Transactional
    public MessageResponse verifyEmail(VerifyEmailRequest request) {
        User user = users.findByEmail(request.email())
                .filter(this::isPending)
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_OTP));
        otpService.consume(user, OtpPurpose.EMAIL_VERIFICATION, request.code());
        if (users.activatePendingUser(user.getId(), Instant.now()) != 1) {
            // Roll back OTP consumption as well if the account no longer qualifies.
            throw new AuthException(ErrorCode.INVALID_OTP);
        }
        return new MessageResponse("Thao tác thành công");
    }

    @Override
    @Transactional
    public OtpRequestResponse resendVerification(ResendVerificationRequest request) {
        users.findByEmail(request.email()).filter(this::isPending)
                .ifPresent(user -> otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION));
        return new OtpRequestResponse("Hệ thống sẽ gửi mã qua email",
                OtpConfig.OTP_TTL_SECONDS);
    }

    private boolean isPending(User user) {
        return user.getStatus() == UserStatus.PENDING_VERIFICATION && user.getEmailVerifiedAt() == null;
    }
}
