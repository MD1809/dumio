package com.dumio.backend.service.impl;

import com.dumio.backend.config.OtpConfig;
import com.dumio.backend.dto.request.ForgotPasswordRequest;
import com.dumio.backend.dto.request.LoginRequest;
import com.dumio.backend.dto.request.RegisterRequest;
import com.dumio.backend.dto.request.ResetPasswordRequest;
import com.dumio.backend.dto.request.ResendVerificationRequest;
import com.dumio.backend.dto.request.VerifyEmailRequest;
import com.dumio.backend.dto.response.MessageResponse;
import com.dumio.backend.dto.response.OtpRequestResponse;
import com.dumio.backend.dto.response.RegisterResponse;
import com.dumio.backend.dto.response.UserResponse;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.ErrorCode;
import com.dumio.backend.enums.OtpPurpose;
import com.dumio.backend.enums.UserRole;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.event.PasswordChangedEvent;
import com.dumio.backend.exception.AuthException;
import com.dumio.backend.mapper.UserMapper;
import com.dumio.backend.repository.UserRepository;
import com.dumio.backend.service.AuthService;
import com.dumio.backend.service.OtpService;
import com.dumio.backend.service.TokenService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository users;
    private final OtpService otpService;
    private final PasswordEncoder passwords;
    private final UserMapper mapper;
    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final ApplicationEventPublisher events;

    public AuthServiceImpl(UserRepository users, OtpService otpService, PasswordEncoder passwords, UserMapper mapper,
            AuthenticationManager authenticationManager, TokenService tokenService, ApplicationEventPublisher events) {
        this.users = users;
        this.otpService = otpService;
        this.passwords = passwords;
        this.mapper = mapper;
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.events = events;
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

    @Override
    @Transactional
    public TokenService.TokenPair login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        } catch (BadCredentialsException | AccountStatusException ex) {
            throw new AuthException(ErrorCode.AUTHENTICATION_FAILED);
        }
        User user = users.findByEmail(request.email())
                .filter(account -> account.getStatus() == UserStatus.ACTIVE && account.getEmailVerifiedAt() != null)
                .orElseThrow(() -> new AuthException(ErrorCode.AUTHENTICATION_FAILED));
        return tokenService.issue(user);
    }

    @Override
    @Transactional
    public TokenService.TokenPair refresh(String refreshToken) {
        return tokenService.rotate(refreshToken);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return users.findById(userId)
                .filter(account -> account.getStatus() == UserStatus.ACTIVE && account.getEmailVerifiedAt() != null)
                .map(mapper::toResponse).orElseThrow(() -> new AuthException(ErrorCode.INVALID_ACCESS_TOKEN));
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        tokenService.revokeForLogout(refreshToken);
    }

    @Override
    @Transactional
    public OtpRequestResponse forgotPassword(ForgotPasswordRequest request) {
        users.findByEmail(request.email()).filter(this::isActiveVerified)
                .ifPresent(user -> otpService.issue(user, OtpPurpose.PASSWORD_RESET));
        return new OtpRequestResponse("Nếu tài khoản đủ điều kiện, hệ thống sẽ gửi mã qua email",
                OtpConfig.OTP_TTL_SECONDS);
    }

    @Override
    @Transactional
    public MessageResponse resetPassword(ResetPasswordRequest request) {
        User user = users.findByEmail(request.email()).filter(this::isActiveVerified)
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_OTP));
        otpService.consume(user, OtpPurpose.PASSWORD_RESET, request.code());
        if (users.updatePasswordIfActive(user.getId(), passwords.encode(request.newPassword()), Instant.now()) != 1) {
            throw new AuthException(ErrorCode.INVALID_OTP);
        }
        otpService.invalidateAvailable(user.getId(), OtpPurpose.PASSWORD_RESET);
        tokenService.revokeAllForUser(user.getId());
        events.publishEvent(new PasswordChangedEvent(user.getEmail()));
        return new MessageResponse("Thao tác thành công");
    }

    private boolean isActiveVerified(User user) {
        return user.getStatus() == UserStatus.ACTIVE && user.getEmailVerifiedAt() != null;
    }

    private boolean isPending(User user) {
        return user.getStatus() == UserStatus.PENDING_VERIFICATION && user.getEmailVerifiedAt() == null;
    }
}
