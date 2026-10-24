package com.dumio.backend.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dumio.backend.config.JwtConfig;
import com.dumio.backend.entity.RefreshToken;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.UserRole;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.repository.RefreshTokenRepository;
import com.dumio.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "spring.mail.username=test@example.com", "spring.mail.password=unused"})
@AutoConfigureMockMvc
@Import(AuthSecurityTests.Fixture.class)
@Transactional
class JwtLoginTests {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository tokens;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired JsonMapper json;
    @Autowired EntityManager em;

    @Test
    void loginMeRotationAndLogoutUseRealJwtAndHashOnlyStorage() throws Exception {
        User user = user(UserRole.USER, "Student1!");
        Session original = login(user, "Student1!", false);
        Jwt jwt = decoder.decode(original.access());
        assertEquals(900, jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond());
        assertEquals(user.getId().toString(), jwt.getSubject());
        assertEquals(List.of("USER"), jwt.getClaimAsStringList("roles"));
        assertEquals(0L, ((Number) jwt.getClaims().get("token_version")).longValue());
        RefreshToken stored = tokens.findByTokenHash(hash(original.refresh().getValue())).orElseThrow();
        assertEquals(604800, stored.getExpiresAt().getEpochSecond() - stored.getCreatedAt().getEpochSecond());
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(original.refresh().getValue()).length);
        assertNotEquals(original.refresh().getValue(), stored.getTokenHash());
        assertTrue(stored.getTokenHash().matches("[0-9a-f]{64}"));
        me(original.access(), 200);

        var csrf = csrf();
        var result = mvc.perform(post("/api/v1/auth/refresh").cookie(csrf.cookie(), original.refresh())
                        .header("X-XSRF-TOKEN", csrf.value()).header(HttpHeaders.AUTHORIZATION, "Bearer expired"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("expiresIn").value(900)).andExpect(jsonPath("refreshToken").doesNotExist())
                .andExpect(jsonPath("user.passwordHash").doesNotExist()).andReturn();
        Session rotated = session(result);
        assertNotEquals(original.refresh().getValue(), rotated.refresh().getValue());
        em.clear();
        assertNotNull(tokens.findById(stored.getId()).orElseThrow().getConsumedAt());
        assertNull(tokens.findByTokenHash(hash(rotated.refresh().getValue())).orElseThrow().getConsumedAt());
        me(rotated.access(), 200);
        mvc.perform(post("/api/v1/auth/refresh").cookie(csrf.cookie(), original.refresh())
                        .header("X-XSRF-TOKEN", csrf.value()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_REFRESH_TOKEN"));
        // Reuse does not revoke the replacement or older access token under the simplified policy.
        me(original.access(), 200);
        me(rotated.access(), 200);

        var logout = mvc.perform(post("/api/v1/auth/logout").cookie(csrf.cookie(), rotated.refresh())
                        .header("X-XSRF-TOKEN", csrf.value()))
                .andExpect(status().isNoContent()).andExpect(content().string("")).andReturn();
        assertEquals(0, logout.getResponse().getCookie("DUMIO_REFRESH").getMaxAge());
        assertEquals("/api/v1/auth", logout.getResponse().getCookie("DUMIO_REFRESH").getPath());
        assertEquals(0, logout.getResponse().getCookie("XSRF-TOKEN").getMaxAge());
        em.clear();
        assertEquals(1, users.findById(user.getId()).orElseThrow().getTokenVersion());
        assertNotNull(tokens.findById(stored.getId()).orElseThrow().getRevokedAt());
        me(original.access(), 401);
        me(rotated.access(), 401);
        mvc.perform(post("/api/v1/auth/refresh").cookie(csrf.cookie(), rotated.refresh())
                        .header("X-XSRF-TOKEN", csrf.value()))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cookieFlagsFollowHttpOrHttpsAndDoNotCreateSession(boolean https) throws Exception {
        Session session = login(user(UserRole.USER, "Student1!"), "Student1!", https);
        Cookie cookie = session.refresh();
        assertTrue(cookie.isHttpOnly());
        assertEquals(https, cookie.getSecure());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
        assertEquals("/api/v1/auth", cookie.getPath());
        assertEquals(604800, cookie.getMaxAge());
        assertNull(cookie.getDomain());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-password", "missing-email", "pending", "disabled"})
    void loginFailuresHaveSamePublicErrorAndDoNotIssueTokens(String scenario) throws Exception {
        User user = user(UserRole.USER, "Student1!");
        if (scenario.equals("pending")) {
            user.setStatus(UserStatus.PENDING_VERIFICATION);
            user.setEmailVerifiedAt(null);
        } else if (scenario.equals("disabled")) {
            user.setStatus(UserStatus.DISABLED);
        }
        users.saveAndFlush(user);
        var csrf = csrf();
        String email = scenario.equals("missing-email") ? UUID.randomUUID() + "@example.com" : user.getEmail();
        String password = scenario.equals("wrong-password") ? "Wrong1!" : "Student1!";
        var result = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "password", password)))
                        .cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.value()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("message").value("Email hoặc mật khẩu không hợp lệ"))
                .andExpect(jsonPath("fieldErrors").isEmpty()).andReturn();
        assertNull(result.getResponse().getCookie("DUMIO_REFRESH"));
        assertEquals(0, tokens.findAll().stream().filter(token -> token.getUser().getId().equals(user.getId())).count());
    }

    @Test
    void loginKeepsPasswordWhitespaceAndDoesNotReapplyRegistrationComplexity() throws Exception {
        login(user(UserRole.USER, " legacy "), " legacy ", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unknown", "malformed", "expired", "consumed", "revoked", "disabled", "pending"})
    void unusableRefreshIsRejectedWithoutIssuingNewCookie(String scenario) throws Exception {
        User user = user(UserRole.USER, "Student1!");
        Session session = login(user, "Student1!", false);
        RefreshToken stored = tokens.findByTokenHash(hash(session.refresh().getValue())).orElseThrow();
        Cookie cookie = session.refresh();
        switch (scenario) {
            case "missing" -> cookie = null;
            case "unknown" -> cookie = new Cookie("DUMIO_REFRESH", "A".repeat(43));
            case "malformed" -> cookie = new Cookie("DUMIO_REFRESH", "bad");
            case "expired" -> expire(stored);
            case "consumed" -> tokens.consumeIfUsable(stored.getId(), Instant.now());
            case "revoked" -> tokens.revokeByUserId(user.getId(), Instant.now());
            case "disabled" -> { user.setStatus(UserStatus.DISABLED); users.saveAndFlush(user); }
            case "pending" -> {
                user.setStatus(UserStatus.PENDING_VERIFICATION);
                user.setEmailVerifiedAt(null);
                users.saveAndFlush(user);
            }
            default -> fail("Unknown case");
        }
        var csrf = csrf();
        var request = post("/api/v1/auth/refresh").cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.value());
        if (cookie != null) { request.cookie(cookie); }
        var result = mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("code").value("INVALID_REFRESH_TOKEN")).andReturn();
        assertNull(result.getResponse().getCookie("DUMIO_REFRESH"));
    }

    @Test
    void refreshTokenInJsonOrBearerCannotReplaceTheCookie() throws Exception {
        Session session = login(user(UserRole.USER, "Student1!"), "Student1!", false);
        var csrf = csrf();
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("refreshToken", session.refresh().getValue())))
                        .cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.value())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.refresh().getValue()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_REFRESH_TOKEN"));
        mvc.perform(get("/api/v1/auth/me").cookie(session.refresh()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAccessCanRefreshAndLogoutWithoutBeingBlockedByBearerHeader() throws Exception {
        Session session = login(user(UserRole.USER, "Student1!"), "Student1!", false);
        Jwt jwt = decoder.decode(session.access());
        Instant issued = Instant.now().minusSeconds(1200);
        var claims = JwtClaimsSet.builder().claims(values -> values.putAll(jwt.getClaims()))
                .issuedAt(issued).notBefore(issued).expiresAt(issued.plusSeconds(900)).build();
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        me(expired, 401);
        var csrf = csrf();
        mvc.perform(post("/api/v1/auth/refresh").cookie(csrf.cookie(), session.refresh())
                        .header("X-XSRF-TOKEN", csrf.value()).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isOk());
        // The consumed original cookie still identifies the user for logout.
        mvc.perform(post("/api/v1/auth/logout").cookie(csrf.cookie(), session.refresh())
                        .header("X-XSRF-TOKEN", csrf.value()).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isNoContent());
        em.clear();
        me(session.access(), 401);
    }

    @Test
    void logoutRevokesAllDevicesButNotAnotherAccountAndIsIdempotentAfterNewLogin() throws Exception {
        User user = user(UserRole.USER, "Student1!");
        Session first = login(user, "Student1!", false);
        Session second = login(user, "Student1!", false);
        Session other = login(user(UserRole.USER, "Student1!"), "Student1!", false);
        var csrf = csrf();
        mvc.perform(post("/api/v1/auth/logout").cookie(csrf.cookie(), first.refresh())
                        .header("X-XSRF-TOKEN", csrf.value())).andExpect(status().isNoContent());
        em.clear();
        me(first.access(), 401);
        me(second.access(), 401);
        me(other.access(), 200);
        Session current = login(users.findById(user.getId()).orElseThrow(), "Student1!", false);
        var newCsrf = csrf();
        mvc.perform(post("/api/v1/auth/logout").cookie(newCsrf.cookie(), first.refresh())
                        .header("X-XSRF-TOKEN", newCsrf.value())).andExpect(status().isNoContent());
        em.clear();
        assertEquals(1, users.findById(user.getId()).orElseThrow().getTokenVersion());
        me(current.access(), 200);
        assertNull(tokens.findByTokenHash(hash(current.refresh().getValue())).orElseThrow().getRevokedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unknown", "malformed", "expired"})
    void logoutClearsCookieEvenWithoutUsableRefresh(String scenario) throws Exception {
        Cookie refresh = null;
        if (scenario.equals("unknown")) { refresh = new Cookie("DUMIO_REFRESH", "A".repeat(43)); }
        if (scenario.equals("malformed")) { refresh = new Cookie("DUMIO_REFRESH", "bad"); }
        Session session = null;
        if (scenario.equals("expired")) {
            session = login(user(UserRole.USER, "Student1!"), "Student1!", false);
            refresh = session.refresh();
            expire(tokens.findByTokenHash(hash(refresh.getValue())).orElseThrow());
        }
        var csrf = csrf();
        var request = post("/api/v1/auth/logout").cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.value());
        if (refresh != null) { request.cookie(refresh); }
        var result = mvc.perform(request).andExpect(status().isNoContent()).andReturn();
        assertEquals(0, result.getResponse().getCookie("DUMIO_REFRESH").getMaxAge());
        em.clear();
        if (session != null) { me(session.access(), 401); }
    }

    @Test
    void disablingThenReenablingDoesNotRestoreRevokedTokensAndRoleChangesRejectOldJwt() throws Exception {
        User user = user(UserRole.USER, "Student1!");
        Session session = login(user, "Student1!", false);
        user.setStatus(UserStatus.DISABLED);
        users.saveAndFlush(user);
        users.incrementTokenVersion(user.getId(), Instant.now());
        tokens.revokeByUserId(user.getId(), Instant.now());
        em.clear();
        me(session.access(), 401);
        User enabled = users.findById(user.getId()).orElseThrow();
        enabled.setStatus(UserStatus.ACTIVE);
        users.saveAndFlush(enabled);
        me(session.access(), 401);
        Session fresh = login(enabled, "Student1!", false);
        enabled.setRole(UserRole.ADMIN);
        users.saveAndFlush(enabled);
        em.clear();
        me(fresh.access(), 401);
    }

    @Test
    void actualLoginTokensHaveUserAndAdminAuthorities() throws Exception {
        Session user = login(user(UserRole.USER, "Student1!"), "Student1!", false);
        Session admin = login(user(UserRole.ADMIN, "Student1!"), "Student1!", false);
        mvc.perform(get("/__security/admin").header(HttpHeaders.AUTHORIZATION, "Bearer " + user.access()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("ACCESS_DENIED"));
        mvc.perform(get("/__security/admin").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.access()))
                .andExpect(status().isOk());
    }

    @Test
    void invalidLoginInputIsRejectedBeforeAuthentication() throws Exception {
        var csrf = csrf();
        for (String body : List.of("{}", "{\"email\":\"bad\",\"password\":\"Student1!\"}",
                "{\"email\":\"a@example.com\",\"password\":12345678}",
                json.writeValueAsString(Map.of("email", "a@example.com", "password", "é".repeat(37))))) {
            mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.value())
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        }
    }

    private User user(UserRole role, String password) {
        User user = new User();
        user.setUsername("Student");
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwords.encode(password));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        return users.saveAndFlush(user);
    }

    private Session login(User user, String password, boolean https) throws Exception {
        var csrf = csrf();
        var result = mvc.perform(post("/api/v1/auth/login").secure(https).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", " " + user.getEmail().toUpperCase(java.util.Locale.ROOT)
                                + " ", "password", password)))
                        .cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.value()))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("tokenType").value("Bearer")).andExpect(jsonPath("expiresIn").value(900))
                .andExpect(jsonPath("refreshToken").doesNotExist()).andReturn();
        assertEquals(4, json.readTree(result.getResponse().getContentAsString()).size());
        assertEquals(4, json.readTree(result.getResponse().getContentAsString()).get("user").size());
        assertNull(result.getRequest().getSession(false));
        assertNull(result.getResponse().getCookie("JSESSIONID"));
        assertEquals(0, result.getResponse().getCookie("XSRF-TOKEN").getMaxAge());
        return session(result);
    }

    private Session session(MvcResult result) throws Exception {
        return new Session(json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText(),
                result.getResponse().getCookie("DUMIO_REFRESH"));
    }

    private void me(String token, int expected) throws Exception {
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(expected));
    }

    private CsrfPair csrf() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        return new CsrfPair(result.getResponse().getCookie("XSRF-TOKEN"),
                json.readTree(result.getResponse().getContentAsString()).get("token").asText());
    }

    private void expire(RefreshToken token) {
        em.createNativeQuery("""
                UPDATE refresh_tokens SET created_at = CURRENT_TIMESTAMP - INTERVAL '8 days',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE id = :id
                """).setParameter("id", token.getId()).executeUpdate();
        em.refresh(token);
    }

    private String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
    }

    private record CsrfPair(Cookie cookie, String value) {}
    private record Session(String access, Cookie refresh) {
        @Override public String toString() { return "Session[redacted]"; }
    }
}
