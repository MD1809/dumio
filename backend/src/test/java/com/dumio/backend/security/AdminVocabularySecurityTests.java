package com.dumio.backend.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dumio.backend.config.JwtConfig;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.UserRole;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "spring.mail.username=test@example.com", "spring.mail.password=unused"})
@AutoConfigureMockMvc
@Import(AdminVocabularySecurityTests.Fixture.class)
@Transactional
class AdminVocabularySecurityTests {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Autowired UserRepository users;

    static Stream<Arguments> protectedRequests() {
        return Stream.of("/api/v1/admin/vocabulary/_security-check", "/api/v1/admin/media/_security-check")
                .flatMap(path -> Stream.of("GET", "POST", "PUT", "PATCH", "DELETE")
                        .map(method -> Arguments.of(path, method)));
    }

    @ParameterizedTest
    @MethodSource("protectedRequests")
    void anonymousRequestsRequireBearerEvenWithRefreshCookie(String path, String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path)
                        .cookie(new Cookie("DUMIO_REFRESH", "not-an-access-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("code").value("INVALID_ACCESS_TOKEN"))
                .andExpect(jsonPath("fieldErrors").isArray())
                .andExpect(header().exists("WWW-Authenticate"));
    }

    @ParameterizedTest
    @MethodSource("protectedRequests")
    void userIsForbiddenOnEveryAdminMethod(String path, String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer " + token(UserRole.USER, false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("code").value("ACCESS_DENIED"));
    }

    @ParameterizedTest
    @MethodSource("protectedRequests")
    void adminCanReachHandlerWithoutCsrfAndDoesNotRotateCookies(String path, String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer " + token(UserRole.ADMIN, false))
                        .cookie(new Cookie("XSRF-TOKEN", "existing-csrf")))
                .andExpect(status().isOk()).andExpect(content().string("secured"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/vocabulary", "/api/v1/admin/vocabulary/sets/missing/restore",
            "/api/v1/admin/media", "/api/v1/admin/media/missing/content"})
    void prefixRootsAndFutureRoutesAreProtectedBeforeControllerLookup(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + token(UserRole.USER, false)))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", "Bearer " + token(UserRole.ADMIN, false)))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/vocabulary/_security-check", "/api/v1/admin/media/_security-check"})
    void malformedAndExpiredTokensAreUnauthorized(String path) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_ACCESS_TOKEN"));
        mvc.perform(get(path).header("Authorization", "Bearer " + token(UserRole.ADMIN, true)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_ACCESS_TOKEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void localPreflightAllowsDeclaredMethodsAndBearerHeader(String method) throws Exception {
        for (String path : List.of("/api/v1/admin/vocabulary/topics", "/api/v1/admin/media")) {
            mvc.perform(options(path).header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", method)
                            .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                    .andExpect(header().string("Access-Control-Allow-Headers", "Authorization, Content-Type"));
        }
    }

    @Test
    void untrustedOriginAndUnsupportedMethodAreRejected() throws Exception {
        mvc.perform(options("/api/v1/admin/media").header("Origin", "https://other.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/v1/admin/media").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "TRACE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossOriginUnauthorizedResponseRemainsReadableByFrontend() throws Exception {
        mvc.perform(get("/api/v1/admin/vocabulary/_security-check").header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Expose-Headers", "WWW-Authenticate"));
    }

    private String token(UserRole role, boolean expired) {
        User user = new User();
        user.setUsername("Security test");
        user.setEmail("vocab-security-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-only-not-for-login");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user = users.saveAndFlush(user);
        Instant issued = Instant.now().minusSeconds(expired ? 1000 : 1);
        var claims = JwtClaimsSet.builder().issuer(JwtConfig.ISSUER).audience(List.of(JwtConfig.AUDIENCE))
                .subject(user.getId().toString()).issuedAt(issued).notBefore(issued).expiresAt(issued.plusSeconds(900))
                .claim("roles", List.of(role.name())).claim("token_version", user.getTokenVersion()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    // Test-only handlers prove authorization independently of future CRUD controllers.
    @TestComponent
    @RestController
    static class Fixture {
        @RequestMapping({"/api/v1/admin/vocabulary/_security-check", "/api/v1/admin/media/_security-check"})
        String check() { return "secured"; }
    }
}
