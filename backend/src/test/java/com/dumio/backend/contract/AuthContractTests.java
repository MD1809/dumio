package com.dumio.backend.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dumio.backend.config.JsonConfig;
import com.dumio.backend.dto.request.ForgotPasswordRequest;
import com.dumio.backend.dto.request.LoginRequest;
import com.dumio.backend.dto.request.RegisterRequest;
import com.dumio.backend.dto.request.ResendVerificationRequest;
import com.dumio.backend.dto.request.ResetPasswordRequest;
import com.dumio.backend.dto.request.VerifyEmailRequest;
import com.dumio.backend.dto.response.CsrfResponse;
import com.dumio.backend.dto.response.ErrorResponse;
import com.dumio.backend.dto.response.MessageResponse;
import com.dumio.backend.dto.response.OtpRequestResponse;
import com.dumio.backend.dto.response.RegisterResponse;
import com.dumio.backend.dto.response.TokenResponse;
import com.dumio.backend.entity.User;
import com.dumio.backend.enums.ErrorCode;
import com.dumio.backend.enums.UserRole;
import com.dumio.backend.exception.AuthException;
import com.dumio.backend.exception.GlobalExceptionHandler;
import com.dumio.backend.mapper.UserMapper;
import jakarta.validation.Valid;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.json.JsonMapper;

class AuthContractTests {

    private static JsonMapper json;
    private static MockMvc mvc;
    private static LocalValidatorFactoryBean validator;

    @BeforeAll
    static void setup() {
        JsonMapper.Builder builder = JsonMapper.builder().findAndAddModules();
        new JsonConfig().strictAuthJson().customize(builder);
        json = builder.build();
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        // Standalone fixtures exist only in tests; no production auth endpoints or security bypass.
        mvc = MockMvcBuilders.standaloneSetup(new ContractFixture())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json))
                .setValidator(validator)
                .build();
    }

    @AfterAll
    static void closeValidator() {
        validator.close();
    }

    @Test
    void requestBindingNormalizesBeforeValidationAndDoesNotLeakCredentials() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRegistration())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.username").value("Nguyễn An"))
                .andExpect(jsonPath("$.user.email").value("an@example.com"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user.status").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.confirmPassword").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "status", "purpose", "unknownField", "passwordConfirmed"})
    void rejectsFieldsOutsideTheRequestContract(String field) throws Exception {
        Map<String, Object> payload = validRegistration();
        payload.put(field, "ADMIN");
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void rejectsNumericOtpInsteadOfCoercingItToAString() throws Exception {
        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"an@example.com\",\"code\":123456}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"an@example.com\",\"code\":\"012345\"}"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "email", "password", "confirmPassword"})
    void rejectsNumericAndBooleanInputsForStringFields(String field) throws Exception {
        for (Object invalidValue : List.of(12345678, true, 1.5)) {
            Map<String, Object> payload = validRegistration();
            payload.put(field, invalidValue);
            mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void rejectsMalformedJsonWithoutEchoingParserDetailsOrPassword() throws Exception {
        String result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"private-password\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(result.contains("private-password"));
        assertFalse(result.contains("stackTrace"));
    }

    @Test
    void mapsConfirmationFailureToTheConfirmPasswordField() throws Exception {
        Map<String, Object> payload = validRegistration();
        payload.put("confirmPassword", "Different1!");
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("confirmPassword"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Mật khẩu xác nhận không khớp"));
        mvc.perform(post("/api/v1/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"an@example.com\",\"code\":\"012345\",\"newPassword\":\"Newpass2!\",\"confirmPassword\":\"Different1!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("confirmPassword"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"register", "login", "verify-email", "resend-verification", "forgot-password", "reset-password"})
    void missingRequiredFieldsUseTheSameErrorContract(String endpoint) throws Exception {
        mvc.perform(post("/api/v1/auth/" + endpoint).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void authExceptionsUseTheStatusCodeAndMessageFromTheEnum(ErrorCode code) throws Exception {
        mvc.perform(get("/__contract/auth-error/" + code.name()))
                .andExpect(status().is(code.getHttpStatus().value()))
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void mapsOnlyTheUserEmailUniqueConstraintToEmailConflict() throws Exception {
        mvc.perform(get("/__contract/storage-error/email"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
        mvc.perform(get("/__contract/storage-error/other-constraint"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    void infrastructureAndUnexpectedErrorsNeverExposeTheirOriginalMessages() throws Exception {
        String unavailable = mvc.perform(get("/__contract/storage-error/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        String unexpected = mvc.perform(get("/__contract/storage-error/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(unavailable.contains("private-password"));
        assertFalse(unexpected.contains("private-password"));
    }

    @Test
    void mapperOnlyExposesTheFourPublicUserFields() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("Nguyễn An");
        user.setEmail("an@example.com");
        user.setRole(UserRole.ADMIN);
        user.setPasswordHash("private-hash");
        var result = json.readTree(json.writeValueAsString(new UserMapper().toResponse(user)));

        assertEquals(Set.of("id", "username", "email", "role"), result.propertyNames());
        assertEquals("ADMIN", result.get("role").asString());
    }

    @Test
    void tokenAndCsrfResponsesOnlySerializeTheirPublicContractAndRedactToString() {
        var user = new UserMapper().toResponse(testUser("An", "an@example.com"));
        TokenResponse token = new TokenResponse("private-access-token", "Bearer", 900, user);
        var serialized = json.readTree(json.writeValueAsString(token));
        assertEquals(Set.of("accessToken", "tokenType", "expiresIn", "user"), serialized.propertyNames());
        assertFalse(token.toString().contains("private-access-token"));
        assertFalse(new CsrfResponse("private-csrf", "X-XSRF-TOKEN").toString().contains("private-csrf"));
        assertEquals(List.of(), ErrorResponse.from(ErrorCode.INVALID_OTP).fieldErrors());
    }

    @Test
    void sensitiveRequestFieldsAreWriteOnlyEvenIfAccidentallySerialized() {
        RegisterRequest request = new RegisterRequest("An", "an@example.com", "Abcde1!x", "Abcde1!x");
        String serialized = json.writeValueAsString(request);
        assertFalse(serialized.contains("password"));
        assertFalse(serialized.contains("Abcde1!x"));
        assertTrue(serialized.contains("an@example.com"));
    }

    private static Map<String, Object> validRegistration() {
        return new HashMap<>(Map.of("username", "  Nguyễn   An  ", "email", " An@Example.COM ",
                "password", "Abcde1!x", "confirmPassword", "Abcde1!x"));
    }

    private static User testUser(String name, String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(name);
        user.setEmail(email);
        return user;
    }

    @RestController
    @TestComponent
    static class ContractFixture {

        @PostMapping("/api/v1/auth/register")
        @ResponseStatus(HttpStatus.CREATED)
        RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
            return new RegisterResponse(new UserMapper().toResponse(testUser(request.username(), request.email())), true, 300);
        }

        @PostMapping("/api/v1/auth/login")
        TokenResponse login(@Valid @RequestBody LoginRequest request) {
            return new TokenResponse("fixture-access-token", "Bearer", 900,
                    new UserMapper().toResponse(testUser("An", request.email())));
        }

        @PostMapping("/api/v1/auth/verify-email")
        MessageResponse verify(@Valid @RequestBody VerifyEmailRequest request) {
            return new MessageResponse("Thao tác thành công");
        }

        @PostMapping("/api/v1/auth/resend-verification")
        @ResponseStatus(HttpStatus.ACCEPTED)
        OtpRequestResponse resend(@Valid @RequestBody ResendVerificationRequest request) {
            return new OtpRequestResponse("Nếu tài khoản đủ điều kiện, hệ thống sẽ gửi mã qua email", 300);
        }

        @PostMapping("/api/v1/auth/forgot-password")
        @ResponseStatus(HttpStatus.ACCEPTED)
        OtpRequestResponse forgot(@Valid @RequestBody ForgotPasswordRequest request) {
            return new OtpRequestResponse("Nếu tài khoản đủ điều kiện, hệ thống sẽ gửi mã qua email", 300);
        }

        @PostMapping("/api/v1/auth/reset-password")
        MessageResponse reset(@Valid @RequestBody ResetPasswordRequest request) {
            return new MessageResponse("Thao tác thành công");
        }

        @GetMapping("/__contract/auth-error/{code}")
        MessageResponse authError(@PathVariable ErrorCode code) {
            throw new AuthException(code);
        }

        @GetMapping("/__contract/storage-error/{scenario}")
        MessageResponse storageError(@PathVariable String scenario) {
            switch (scenario) {
                case "email" -> throw new DataIntegrityViolationException("hidden", new SQLException(
                        "private-password duplicate key uk_users_email \"uk_users_email\"", "23505"));
                case "other-constraint" -> throw new DataIntegrityViolationException("private-password",
                        new SQLException("duplicate key \"uk_refresh_tokens_hash\"", "23505"));
                case "unavailable" -> throw new DataAccessResourceFailureException("private-password");
                default -> throw new IllegalStateException("private-password");
            }
        }
    }
}
