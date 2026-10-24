package com.dumio.backend.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dumio.backend.config.JwtConfig;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.UserRole;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@AutoConfigureMockMvc
@Import(AuthSecurityTests.Fixture.class)
@Transactional
class AuthSecurityTests {

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;
    @Autowired PasswordEncoder passwords;
    @Autowired AuthenticationManager authenticationManager;
    @Autowired JsonMapper json;

    @Test
    void csrfEndpointReturnsMaskedTokenAndHttpOnlyCookieWithoutSession() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("token").isNotEmpty()).andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        assertTrue(cookie.isHttpOnly());
        assertFalse(cookie.getSecure());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
        assertEquals("/api/v1/auth", cookie.getPath());
        assertNotEquals(cookie.getValue(), json.readTree(result.getResponse().getContentAsString()).get("token").asText());
        assertNull(result.getRequest().getSession(false));
        assertNull(result.getResponse().getCookie("JSESSIONID"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"register", "verify-email", "resend-verification", "login",
            "refresh", "logout", "forgot-password", "reset-password"})
    void everyCookiePostRequiresCsrfEvenWhenBearerIsPresent(String endpoint) throws Exception {
        String bearer = token(account(UserRole.USER), Map.of());
        mvc.perform(post("/api/v1/auth/" + endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("CSRF_INVALID"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"register", "verify-email", "resend-verification", "login",
            "refresh", "logout", "forgot-password", "reset-password"})
    void everyCookiePostAcceptsRealCookieAndMaskedHeader(String endpoint) throws Exception {
        var csrf = csrf();
        var result = mvc.perform(post("/api/v1/auth/" + endpoint)
                        .cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-or-invalid"))
                .andExpect(status().isOk()).andReturn();
        assertNull(result.getRequest().getSession(false));
    }

    @Test
    void rejectsMissingCookieOrWrongCsrfHeader() throws Exception {
        var csrf = csrf();
        mvc.perform(post("/api/v1/auth/login").header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("CSRF_INVALID"));
        mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie()).header("X-XSRF-TOKEN", "wrong"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("CSRF_INVALID"));
    }

    @Test
    void allowsConfiguredCorsPreflightAndRejectsOtherOrigins() throws Exception {
        mvc.perform(options("/api/v1/auth/login").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type,X-XSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mvc.perform(options("/api/v1/auth/login").header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validatesRealSignedJwtWithoutSessionAndDoesNotRequireCsrfForBearerOnlyPost() throws Exception {
        User user = account(UserRole.USER);
        String token = token(user, Map.of());
        Jwt decoded = decoder.decode(token);
        assertEquals(900, decoded.getExpiresAt().getEpochSecond() - decoded.getIssuedAt().getEpochSecond());
        assertEquals(user.getId().toString(), decoded.getSubject());
        var result = mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("message").value("ok")).andReturn();
        assertNull(result.getRequest().getSession(false));
        assertNull(result.getResponse().getCookie("JSESSIONID"));
        mvc.perform(post("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void requiresBearerAndDoesNotUseRefreshCookieOrBasicAuthentication() throws Exception {
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("DUMIO_REFRESH", "opaque-refresh")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_ACCESS_TOKEN"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Bearer")));
        mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer opaque-refresh"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/login")).andExpect(status().isUnauthorized());
        mvc.perform(get("/login")).andExpect(status().isUnauthorized());
    }

    @Test
    void verifiesUserAndAdminAuthoritiesThroughMethodSecurity() throws Exception {
        mvc.perform(get("/__security/admin").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(account(UserRole.USER), Map.of())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("ACCESS_DENIED"))
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
        mvc.perform(get("/__security/admin").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(account(UserRole.ADMIN), Map.of())))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "future", "issuer", "audience", "missing-exp", "missing-iat",
            "missing-nbf", "subject", "role", "roles-type", "version-string", "version-negative", "missing-version"})
    void rejectsInvalidClaims(String scenario) throws Exception {
        User user = account(UserRole.USER);
        Map<String, Object> claims = claims(user);
        switch (scenario) {
            case "expired" -> {
                claims.put("iat", Instant.now().minusSeconds(1000));
                claims.put("nbf", Instant.now().minusSeconds(1000));
                claims.put("exp", Instant.now().minusSeconds(100));
            }
            case "future" -> claims.put("nbf", Instant.now().plusSeconds(120));
            case "issuer" -> claims.put("iss", "wrong");
            case "audience" -> claims.put("aud", List.of("wrong"));
            case "missing-exp" -> claims.remove("exp");
            case "missing-iat" -> claims.remove("iat");
            case "missing-nbf" -> claims.remove("nbf");
            case "subject" -> claims.put("sub", "not-a-uuid");
            case "role" -> claims.put("roles", List.of("OWNER"));
            case "roles-type" -> claims.put("roles", "ADMIN");
            case "version-string" -> claims.put("token_version", "0");
            case "version-negative" -> claims.put("token_version", -1);
            case "missing-version" -> claims.remove("token_version");
            default -> fail("Unknown case");
        }
        mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer " + encode(claims)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_ACCESS_TOKEN"));
    }

    @Test
    void rejectsJwtSignedWithAnotherKeyOrAlgorithm() throws Exception {
        var other = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(new byte[64]));
        for (MacAlgorithm algorithm : List.of(MacAlgorithm.HS256, MacAlgorithm.HS512)) {
            String token = other.encode(JwtEncoderParameters.from(JwsHeader.with(algorithm).build(),
                    JwtClaimsSet.builder().claims(values -> values.putAll(claims(account(UserRole.USER)))).build()))
                    .getTokenValue();
            mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_ACCESS_TOKEN"));
        }
    }

    @Test
    void versionIncrementRevokesAllOldAccessTokensButAllowsNewVersion() throws Exception {
        User user = account(UserRole.USER);
        String first = token(user, Map.of());
        String second = token(user, Map.of());
        users.incrementTokenVersion(user.getId(), Instant.now());
        entityManager.clear();
        for (String old : List.of(first, second)) {
            mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer " + old))
                    .andExpect(status().isUnauthorized());
        }
        String current = token(users.findById(user.getId()).orElseThrow(), Map.of());
        mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer " + current))
                .andExpect(status().isOk());
    }

    @Test
    void disabledPendingDeletedOrChangedRoleAccountsCannotUseOldJwt() throws Exception {
        User disabled = account(UserRole.USER);
        String old = token(disabled, Map.of());
        disabled.setStatus(UserStatus.DISABLED);
        users.saveAndFlush(disabled);
        mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer " + old))
                .andExpect(status().isUnauthorized());

        // Invalidation must survive re-enabling an account.
        users.incrementTokenVersion(disabled.getId(), Instant.now());
        entityManager.clear();
        User enabled = users.findById(disabled.getId()).orElseThrow();
        enabled.setStatus(UserStatus.ACTIVE);
        users.saveAndFlush(enabled);
        mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer " + old))
                .andExpect(status().isUnauthorized());

        User pending = account(UserRole.USER);
        String pendingToken = token(pending, Map.of());
        pending.setStatus(UserStatus.PENDING_VERIFICATION);
        pending.setEmailVerifiedAt(null);
        users.saveAndFlush(pending);
        mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer " + pendingToken))
                .andExpect(status().isUnauthorized());

        User changed = account(UserRole.USER);
        String changedToken = token(changed, Map.of());
        changed.setRole(UserRole.ADMIN);
        users.saveAndFlush(changed);
        mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer " + changedToken))
                .andExpect(status().isUnauthorized());
        users.delete(changed);
        users.flush();
        mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer " + changedToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticationManagerUsesBcryptAndRejectsWrongOrUnverifiedCredentials() {
        User user = account(UserRole.USER);
        assertInstanceOf(BCryptPasswordEncoder.class, passwords);
        assertTrue(passwords.matches("Student1!", user.getPasswordHash()));
        assertNotEquals(passwords.encode("Student1!"), user.getPasswordHash());
        assertTrue(authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(user.getEmail(), "Student1!")).isAuthenticated());
        assertThrows(AuthenticationException.class, () -> authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(user.getEmail(), "wrong")));
        assertThrows(AuthenticationException.class, () -> authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("missing@example.com", "Student1!")));
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setEmailVerifiedAt(null);
        users.saveAndFlush(user);
        assertThrows(AuthenticationException.class, () -> authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(user.getEmail(), "Student1!")));
        user.setStatus(UserStatus.DISABLED);
        users.saveAndFlush(user);
        assertThrows(AuthenticationException.class, () -> authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(user.getEmail(), "Student1!")));
    }

    private User account(UserRole role) {
        User user = new User();
        user.setUsername("Student");
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwords.encode("Student1!"));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        return users.saveAndFlush(user);
    }

    private Map<String, Object> claims(User user) {
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getId().toString());
        claims.put("iss", JwtConfig.ISSUER);
        claims.put("aud", List.of(JwtConfig.AUDIENCE));
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", now.plusSeconds(JwtConfig.ACCESS_TOKEN_SECONDS));
        claims.put("roles", List.of(user.getRole().name()));
        claims.put("token_version", user.getTokenVersion());
        return claims;
    }

    private String token(User user, Map<String, Object> overrides) {
        Map<String, Object> claims = claims(user);
        claims.putAll(overrides);
        return encode(claims);
    }

    private String encode(Map<String, Object> claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().claims(values -> values.putAll(claims)).build())).getTokenValue();
    }

    private CsrfPair csrf() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        return new CsrfPair(result.getResponse().getCookie("XSRF-TOKEN"),
                json.readTree(result.getResponse().getContentAsString()).get("token").asText());
    }

    private record CsrfPair(Cookie cookie, String token) {}

    // Only imported into this test context; never scanned into the running application.
    @TestComponent
    @RestController
    public static class Fixture {
        @RequestMapping(path = "/__security/private", method = {RequestMethod.GET, RequestMethod.POST})
        public Map<String, String> authenticated() { return Map.of("message", "ok"); }

        @GetMapping("/__security/admin")
        @PreAuthorize("hasRole('ADMIN')")
        public Map<String, String> admin() { return Map.of("message", "admin"); }

        @PostMapping({"/api/v1/auth/register", "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification",
                "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password"})
        public Map<String, String> futureBusinessEndpoint() { return Map.of("message", "fixture"); }
    }
}
