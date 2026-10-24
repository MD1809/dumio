package com.dumio.backend.security;

import static org.junit.jupiter.api.Assertions.*;

import com.dumio.backend.entity.User;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.repository.UserRepository;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "spring.mail.username=test@example.com", "spring.mail.password=unused"})
class JwtHttpFlowTests {

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JsonMapper json;

    @Test
    void realHttpClientFollowsCookieCsrfRotationAndLogoutFlow() throws Exception {
        User user = new User();
        user.setUsername("Student");
        user.setEmail("be06-http-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwords.encode("Student1!"));
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user = users.saveAndFlush(user);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            String csrf = csrf(client);
            String body = json.writeValueAsString(Map.of("email", user.getEmail(), "password", "Student1!"));
            var login = post(client, "login", csrf, body);
            assertEquals(200, login.statusCode());
            String access = json.readTree(login.body()).get("accessToken").asText();
            assertEquals("no-store", login.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(login.headers().allValues("Set-Cookie").stream()
                    .anyMatch(value -> value.startsWith("DUMIO_REFRESH=") && value.contains("HttpOnly")
                            && value.contains("SameSite=Lax") && value.contains("Path=/api/v1/auth")));
            assertFalse(json.readTree(login.body()).has("refreshToken"));
            assertEquals(200, me(client, access).statusCode());
            String originalRefresh = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("DUMIO_REFRESH")).findFirst().orElseThrow().getValue();
            csrf = csrf(client); // Login deliberately clears the previous CSRF cookie.
            var refresh = post(client, "refresh", csrf, "");
            assertEquals(200, refresh.statusCode());
            String refreshedAccess = json.readTree(refresh.body()).get("accessToken").asText();
            String currentRefresh = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("DUMIO_REFRESH")).findFirst().orElseThrow().getValue();
            assertFalse(originalRefresh.equals(currentRefresh));
            String csrfCookieBeforeMe = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue();
            var me = me(client, refreshedAccess);
            assertEquals(200, me.statusCode());
            assertTrue(cookies.getCookieStore().getCookies().stream()
                    .anyMatch(cookie -> cookie.getName().equals("XSRF-TOKEN") && !cookie.hasExpired()),
                    "Bearer-only /me must not clear the CSRF cookie needed by refresh/logout");
            assertTrue(cookies.getCookieStore().getCookies().stream()
                    .anyMatch(cookie -> cookie.getName().equals("XSRF-TOKEN") && cookie.getValue().equals(csrfCookieBeforeMe)),
                    "Bearer authentication must keep the current CSRF cookie unchanged");
            var logout = post(client, "logout", csrf, "");
            assertEquals(204, logout.statusCode());
            assertTrue(logout.body().isEmpty());
            assertEquals(401, me(client, access).statusCode());
            assertEquals(401, me(client, refreshedAccess).statusCode());
            assertTrue(cookies.getCookieStore().getCookies().stream()
                    .noneMatch(cookie -> cookie.getName().equals("DUMIO_REFRESH") && !cookie.hasExpired()));
            csrf = csrf(client);
            assertEquals(401, post(client, "refresh", csrf, "").statusCode());
            assertTrue(cookies.getCookieStore().getCookies().stream()
                    .noneMatch(cookie -> cookie.getName().equals("JSESSIONID")));
        } finally {
            // Only remove the uniquely identified test account; FK cascade removes its test refresh rows.
            users.deleteById(user.getId());
        }
    }

    private String csrf(HttpClient client) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("csrf")).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return json.readTree(response.body()).get("token").asText();
    }

    private HttpResponse<String> post(HttpClient client, String path, String csrf, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> me(HttpClient client, String access) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("me")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + access).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + "/api/v1/auth/" + path); }
}
