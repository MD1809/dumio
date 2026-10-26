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
import com.dumio.backend.entity.PendingRegistration;
import com.dumio.backend.enums.ErrorCode;
import com.dumio.backend.enums.OtpPurpose;
import com.dumio.backend.enums.UserRole;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.event.PasswordChangedEvent;
import com.dumio.backend.exception.AuthException;
import com.dumio.backend.mapper.UserMapper;
import com.dumio.backend.repository.UserRepository;
import com.dumio.backend.repository.PendingRegistrationRepository;
import com.dumio.backend.service.AuthService;
import com.dumio.backend.service.OtpService;
import com.dumio.backend.service.TokenService;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
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
    private final PendingRegistrationRepository registrations;

    public AuthServiceImpl(UserRepository users, OtpService otpService, PasswordEncoder passwords, UserMapper mapper,
            AuthenticationManager authenticationManager, TokenService tokenService, ApplicationEventPublisher events,
            PendingRegistrationRepository registrations) {
        this.users = users;
        this.otpService = otpService;
        this.passwords = passwords;
        this.mapper = mapper;
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.events = events;
        this.registrations = registrations;
    }

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (users.existsByEmail(request.email())) {
            throw new AuthException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        Instant now = Instant.now();
        registrations.deleteExpired(now);
        PendingRegistration registration = new PendingRegistration();
        registration.setId(UUID.randomUUID());
        registration.setUsername(request.username());
        registration.setEmail(request.email());
        registration.setPasswordHash(passwords.encode(request.password()));
        registration.setCreatedAt(now);
        registration.setExpiresAt(now.plusSeconds(OtpConfig.OTP_TTL_SECONDS));
        registration = registrations.saveAndFlush(registration);
        otpService.issue(registration);
        return new RegisterResponse(registration.getId(), true, OtpConfig.OTP_TTL_SECONDS);
    }

    @Override
    @Transactional
    public MessageResponse verifyEmail(VerifyEmailRequest request, String registrationToken) {
        PendingRegistration registration = findRegistration(registrationToken)
                .filter(value -> value.getEmail().equals(request.email()) && value.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_OTP));
        if (users.existsByEmail(request.email())) throw new AuthException(ErrorCode.EMAIL_ALREADY_EXISTS);
        otpService.consume(registration, request.code());
        User user = new User();
        user.setUsername(registration.getUsername());
        user.setEmail(registration.getEmail());
        user.setPasswordHash(registration.getPasswordHash());
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        users.saveAndFlush(user);
        registrations.delete(registration);
        registrations.flush();
        return new MessageResponse("Thao tác thành công");
    }

    @Override
    @Transactional
    public OtpRequestResponse resendVerification(ResendVerificationRequest request, String registrationToken) {
        findRegistration(registrationToken)
                .filter(value -> value.getEmail().equals(request.email()) && value.getExpiresAt().isAfter(Instant.now()))
                .filter(value -> !users.existsByEmail(value.getEmail()))
                .ifPresent(value -> {
                    value.setExpiresAt(Instant.now().plusSeconds(OtpConfig.OTP_TTL_SECONDS));
                    registrations.saveAndFlush(value);
                    otpService.issue(value);
                });
        return new OtpRequestResponse("Hệ thống sẽ gửi mã qua email",
                OtpConfig.OTP_TTL_SECONDS);
    }

    @Override
    @Transactional
    public void cancelRegistration(String registrationToken) {
        findRegistration(registrationToken).ifPresent(value -> {
            registrations.delete(value);
            registrations.flush();
        });
    }

    private Optional<PendingRegistration> findRegistration(String token) {
        if (token == null) return Optional.empty();
        final UUID id;
        try { id = UUID.fromString(token); }
        catch (IllegalArgumentException ex) { return Optional.empty(); }
        return registrations.findForUpdate(id);
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

}
