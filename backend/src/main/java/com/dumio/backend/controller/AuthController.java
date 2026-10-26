package com.dumio.backend.controller;

import com.dumio.backend.config.JwtConfig;
import com.dumio.backend.dto.request.ForgotPasswordRequest;
import com.dumio.backend.dto.request.LoginRequest;
import com.dumio.backend.dto.request.RegisterRequest;
import com.dumio.backend.dto.request.ResetPasswordRequest;
import com.dumio.backend.dto.request.ResendVerificationRequest;
import com.dumio.backend.dto.request.VerifyEmailRequest;
import com.dumio.backend.dto.response.CsrfResponse;
import com.dumio.backend.dto.response.MessageResponse;
import com.dumio.backend.dto.response.OtpRequestResponse;
import com.dumio.backend.dto.response.RegisterResponse;
import com.dumio.backend.dto.response.TokenResponse;
import com.dumio.backend.dto.response.UserResponse;
import com.dumio.backend.service.AuthService;
import com.dumio.backend.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_COOKIE = "DUMIO_REFRESH";
    private static final String REGISTRATION_COOKIE = "DUMIO_REGISTRATION";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final CookieCsrfTokenRepository csrfTokens;

    public AuthController(AuthService authService, CookieCsrfTokenRepository csrfTokens) {
        this.authService = authService;
        this.csrfTokens = csrfTokens;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest body,
            @CookieValue(name = REGISTRATION_COOKIE, required = false) String previousRegistration,
            HttpServletRequest request, HttpServletResponse response) {
        if (previousRegistration != null) authService.cancelRegistration(previousRegistration);
        RegisterResponse result = authService.register(body);
        setRegistrationCookie(result.registrationId().toString(), false, request, response);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/verify-email")
    public MessageResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest body,
            @CookieValue(name = REGISTRATION_COOKIE, required = false) String registration,
            HttpServletRequest request, HttpServletResponse response) {
        MessageResponse result = authService.verifyEmail(body, registration);
        setRegistrationCookie("", true, request, response);
        return result;
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<OtpRequestResponse> resendVerification(@Valid @RequestBody ResendVerificationRequest request,
            @CookieValue(name = REGISTRATION_COOKIE, required = false) String registration) {
        return ResponseEntity.accepted().body(authService.resendVerification(request, registration));
    }

    @PostMapping("/cancel-registration")
    public ResponseEntity<Void> cancelRegistration(
            @CookieValue(name = REGISTRATION_COOKIE, required = false) String registration,
            HttpServletRequest request, HttpServletResponse response) {
        authService.cancelRegistration(registration);
        setRegistrationCookie("", true, request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private void setRegistrationCookie(String value, boolean clear,
            HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(REGISTRATION_COOKIE, value)
                .httpOnly(true).secure(request.isSecure()).sameSite("Lax").path(COOKIE_PATH);
        if (clear) cookie.maxAge(0);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        // Return the framework's masked token; the cookie remains HttpOnly.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getToken(), token.getHeaderName()));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        TokenService.TokenPair tokens = authService.login(body);
        setRefreshCookie(tokens.refreshToken(), JwtConfig.REFRESH_TOKEN_SECONDS, request, response);
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.response());
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refresh,
            HttpServletRequest request, HttpServletResponse response) {
        TokenService.TokenPair tokens = authService.refresh(refresh);
        setRefreshCookie(tokens.refreshToken(), JwtConfig.REFRESH_TOKEN_SECONDS, request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.response());
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(authService.me(UUID.fromString(jwt.getSubject())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refresh,
            HttpServletRequest request, HttpServletResponse response) {
        authService.logout(refresh);
        setRefreshCookie("", 0, request, response);
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<OtpRequestResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest body) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(authService.forgotPassword(body));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        MessageResponse result = authService.resetPassword(body);
        setRefreshCookie("", 0, request, response);
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    private void setRefreshCookie(String value, long maxAge, HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true)
                .secure(request.isSecure()).sameSite("Lax").path(COOKIE_PATH).maxAge(maxAge).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
