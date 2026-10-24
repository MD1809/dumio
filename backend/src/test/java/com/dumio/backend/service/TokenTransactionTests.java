package com.dumio.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dumio.backend.dto.request.LoginRequest;
import com.dumio.backend.entity.RefreshToken;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.ErrorCode;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.exception.AuthException;
import com.dumio.backend.repository.RefreshTokenRepository;
import com.dumio.backend.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "spring.mail.username=test@example.com", "spring.mail.password=unused"})
@AutoConfigureMockMvc
class TokenTransactionTests {

    @Autowired AuthService auth;
    @Autowired PasswordEncoder passwords;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean RefreshTokenRepository tokens;
    @MockitoSpyBean JwtEncoder encoder;

    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void removeOnlyAccountsCreatedByThisTest() {
        for (UUID id : createdUsers) { users.deleteById(id); }
    }

    @Test
    void failedReplacementInsertRollsBackConsumptionAndAllowsRetry() throws Exception {
        var pair = login(user());
        UUID originalId = stored(pair.refreshToken()).getId();
        doThrow(new DataAccessResourceFailureException("private-storage-detail"))
                .when(tokens).saveAndFlush(any(RefreshToken.class));
        assertThrows(DataAccessResourceFailureException.class, () -> auth.refresh(pair.refreshToken()));
        assertNull(tokens.findById(originalId).orElseThrow().getConsumedAt());
        reset(tokens);
        var replacement = auth.refresh(pair.refreshToken());
        assertFalse(pair.refreshToken().equals(replacement.refreshToken()));
        assertNotNull(tokens.findById(originalId).orElseThrow().getConsumedAt());
    }

    @Test
    void failedJwtSigningRollsBackRotation() throws Exception {
        var pair = login(user());
        UUID originalId = stored(pair.refreshToken()).getId();
        doThrow(new JwtEncodingException("private-signing-detail")).when(encoder).encode(any());
        assertThrows(JwtEncodingException.class, () -> auth.refresh(pair.refreshToken()));
        assertNull(tokens.findById(originalId).orElseThrow().getConsumedAt());
    }

    @Test
    void conditionalConsumptionFailureDoesNotIssueReplacement() throws Exception {
        var pair = login(user());
        UUID originalId = stored(pair.refreshToken()).getId();
        doReturn(0).when(tokens).consumeIfUsable(eq(originalId), any());
        assertEquals(ErrorCode.INVALID_REFRESH_TOKEN,
                assertThrows(AuthException.class, () -> auth.refresh(pair.refreshToken())).getErrorCode());
        verify(tokens, times(1)).saveAndFlush(any(RefreshToken.class)); // Only the original login.
        assertNull(tokens.findById(originalId).orElseThrow().getConsumedAt());
    }

    @Test
    void storageFailureOnLoginReturns503InsteadOfIncorrectCredentials() throws Exception {
        String email = "be06-" + UUID.randomUUID() + "@example.com";
        doThrow(new DataAccessResourceFailureException("private-storage-detail"))
                .when(users).findByEmail(email);
        var csrf = csrf();
        var result = mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.value()).contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of("email", email, "password", "Student1!"))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("SERVICE_UNAVAILABLE"))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("private-storage-detail"));
        assertNull(result.getResponse().getCookie("DUMIO_REFRESH"));
    }

    @Test
    void failedLogoutRollsBackAllRevocationsDoesNotClearCookieAndCanBeRetried() throws Exception {
        User user = user();
        var first = login(user);
        var second = login(user);
        doThrow(new DataAccessResourceFailureException("private-storage-detail"))
                .when(users).incrementTokenVersion(eq(user.getId()), any());
        var csrf = csrf();
        var result = mvc.perform(post("/api/v1/auth/logout")
                        .cookie(csrf.cookie(), new Cookie("DUMIO_REFRESH", first.refreshToken()))
                        .header("X-XSRF-TOKEN", csrf.value()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("SERVICE_UNAVAILABLE"))
                .andReturn();
        assertNull(result.getResponse().getCookie("DUMIO_REFRESH"));
        assertNull(stored(first.refreshToken()).getRevokedAt());
        assertNull(stored(second.refreshToken()).getRevokedAt());
        assertEquals(0, users.findById(user.getId()).orElseThrow().getTokenVersion());
        reset(users);
        auth.logout(first.refreshToken());
        assertNotNull(stored(first.refreshToken()).getRevokedAt());
        assertNotNull(stored(second.refreshToken()).getRevokedAt());
        assertEquals(1, users.findById(user.getId()).orElseThrow().getTokenVersion());
    }

    @Test
    void refreshStorageFailureReturns503WithoutReplacingCookie() throws Exception {
        var pair = login(user());
        doThrow(new DataAccessResourceFailureException("private-storage-detail"))
                .when(tokens).findByTokenHash(anyString());
        var csrf = csrf();
        var result = mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(csrf.cookie(), new Cookie("DUMIO_REFRESH", pair.refreshToken()))
                        .header("X-XSRF-TOKEN", csrf.value()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("SERVICE_UNAVAILABLE"))
                .andReturn();
        assertNull(result.getResponse().getCookie("DUMIO_REFRESH"));
    }

    private User user() {
        User user = new User();
        user.setUsername("Student");
        user.setEmail("be06-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwords.encode("Student1!"));
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user = users.saveAndFlush(user);
        createdUsers.add(user.getId());
        return user;
    }

    private TokenService.TokenPair login(User user) {
        return auth.login(new LoginRequest(user.getEmail(), "Student1!"));
    }

    private RefreshToken stored(String raw) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.US_ASCII)));
        return tokens.findByTokenHash(hash).orElseThrow();
    }

    private CsrfPair csrf() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        return new CsrfPair(result.getResponse().getCookie("XSRF-TOKEN"),
                json.readTree(result.getResponse().getContentAsString()).get("token").asText());
    }

    private record CsrfPair(Cookie cookie, String value) {}
}
