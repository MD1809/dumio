package com.dumio.backend.security;

import static org.junit.jupiter.api.Assertions.*;

import com.dumio.backend.entity.User;
import com.dumio.backend.enums.UserStatus;
import com.dumio.backend.repository.UserRepository;
import com.dumio.backend.repository.EmailOtpCodeRepository;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "spring.mail.username=test@example.com", "spring.mail.password=unused"})
class JwtHttpFlowTests {

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired EmailOtpCodeRepository codes;
    @Autowired PasswordEncoder passwords;
    @Autowired JsonMapper json;
    @MockitoBean JavaMailSender sender;

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
            String access = json.readTree(login.body()).get("accessToken").asString();
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
            String refreshedAccess = json.readTree(refresh.body()).get("accessToken").asString();
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

    @Test
    void realHttpPasswordRecoveryRevokesOldTokensAndAllowsOnlyNewPassword() throws Exception {
        User user = new User();
        user.setUsername("Student");
        user.setEmail("be07-http-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwords.encode("Student1!"));
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user = users.saveAndFlush(user);
        var delivered = new LinkedBlockingQueue<SimpleMailMessage>();
        org.mockito.Mockito.doAnswer(invocation -> {
            delivered.add(invocation.getArgument(0));
            return null;
        }).when(sender).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            String csrf = csrf(client);
            String oldCredentials = json.writeValueAsString(Map.of("email", user.getEmail(), "password", "Student1!"));
            var login = post(client, "login", csrf, oldCredentials);
            assertEquals(200, login.statusCode());
            String oldAccess = json.readTree(login.body()).get("accessToken").asString();
            csrf = csrf(client);
            var forgot = post(client, "forgot-password", csrf, json.writeValueAsString(Map.of("email", user.getEmail())));
            assertEquals(202, forgot.statusCode());
            var otpMail = delivered.poll(5, TimeUnit.SECONDS);
            assertNotNull(otpMail);
            var matcher = Pattern.compile("\\b[0-9]{6}\\b").matcher(otpMail.getText());
            assertTrue(matcher.find());
            var reset = post(client, "reset-password", csrf, json.writeValueAsString(Map.of(
                    "email", user.getEmail(), "code", matcher.group(), "newPassword", "Newpass2!", "confirmPassword", "Newpass2!")));
            assertEquals(200, reset.statusCode());
            assertEquals(1, json.readTree(reset.body()).size());
            assertTrue(cookies.getCookieStore().getCookies().stream()
                    .noneMatch(cookie -> cookie.getName().equals("DUMIO_REFRESH") && !cookie.hasExpired()));
            var notification = delivered.poll(5, TimeUnit.SECONDS);
            assertNotNull(notification);
            assertEquals("Dumio - Mật khẩu đã được thay đổi", notification.getSubject());
            assertFalse(notification.getText().contains("Newpass2!"));
            assertEquals(401, me(client, oldAccess).statusCode());
            csrf = csrf(client);
            assertEquals(401, post(client, "refresh", csrf, "").statusCode());
            assertEquals(401, post(client, "login", csrf, oldCredentials).statusCode());
            String newCredentials = json.writeValueAsString(Map.of("email", user.getEmail(), "password", "Newpass2!"));
            var newLogin = post(client, "login", csrf, newCredentials);
            assertEquals(200, newLogin.statusCode());
            assertEquals(200, me(client, json.readTree(newLogin.body()).get("accessToken").asString()).statusCode());
        } finally {
            users.deleteById(user.getId());
        }
    }

    @Test
    void realHttpRegistrationResendVerificationAndLoginFollowThePublishedContract() throws Exception {
        String email = "be08-http-" + UUID.randomUUID() + "@example.com";
        var delivered = new LinkedBlockingQueue<SimpleMailMessage>();
        org.mockito.Mockito.doAnswer(invocation -> {
            delivered.add(invocation.getArgument(0));
            return null;
        }).when(sender).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            String csrf = csrf(client);
            var register = post(client, "register", csrf, json.writeValueAsString(Map.of(
                    "username", "  Nguyen   An  ", "email", " " + email.toUpperCase(java.util.Locale.ROOT) + " ",
                    "password", "Student1!", "confirmPassword", "Student1!")));
            assertEquals(202, register.statusCode());
            var body = json.readTree(register.body());
            assertFalse(body.has("user"));
            assertFalse(body.has("registrationId"));
            assertTrue(body.get("verificationRequired").asBoolean());
            assertEquals(300, body.get("expiresInSeconds").asInt());
            assertFalse(body.has("accessToken"));
            assertNotNull(delivered.poll(5, TimeUnit.SECONDS));
            assertTrue(users.findByEmail(email).isEmpty());
            var registrationId = UUID.fromString(cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("DUMIO_REGISTRATION")).findFirst().orElseThrow().getValue());
            var original = codes.findByRegistrationIdAndUsedAtIsNullAndInvalidatedAtIsNull(registrationId).orElseThrow();
            String credentials = json.writeValueAsString(Map.of("email", email, "password", "Student1!"));
            assertEquals(401, post(client, "login", csrf, credentials).statusCode());
            var resend = post(client, "resend-verification", csrf, json.writeValueAsString(Map.of("email", email)));
            assertEquals(202, resend.statusCode());
            assertEquals(300, json.readTree(resend.body()).get("expiresInSeconds").asInt());
            var replacement = delivered.poll(5, TimeUnit.SECONDS);
            assertNotNull(replacement);
            assertNotNull(codes.findById(original.getId()).orElseThrow().getInvalidatedAt());
            var matcher = Pattern.compile("\\b[0-9]{6}\\b").matcher(replacement.getText());
            assertTrue(matcher.find());
            String verification = json.writeValueAsString(Map.of("email", email, "code", matcher.group()));
            assertEquals(200, post(client, "verify-email", csrf, verification).statusCode());
            assertEquals(UserStatus.ACTIVE, users.findByEmail(email).orElseThrow().getStatus());
            assertEquals(400, post(client, "verify-email", csrf, verification).statusCode());
            var login = post(client, "login", csrf, credentials);
            assertEquals(200, login.statusCode());
            assertEquals(200, me(client, json.readTree(login.body()).get("accessToken").asString()).statusCode());
        } finally {
            users.findByEmail(email).ifPresent(user -> users.deleteById(user.getId()));
        }
    }

    private String csrf(HttpClient client) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("csrf")).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return json.readTree(response.body()).get("token").asString();
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
