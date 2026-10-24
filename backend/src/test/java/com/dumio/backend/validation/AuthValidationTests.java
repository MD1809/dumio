package com.dumio.backend.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dumio.backend.dto.request.ForgotPasswordRequest;
import com.dumio.backend.dto.request.LoginRequest;
import com.dumio.backend.dto.request.RegisterRequest;
import com.dumio.backend.dto.request.ResendVerificationRequest;
import com.dumio.backend.dto.request.ResetPasswordRequest;
import com.dumio.backend.dto.request.VerifyEmailRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthValidationTests {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void normalizesNameAndEmailButPreservesPasswordsExactly() {
        RegisterRequest request = new RegisterRequest("  Nguyễn\u00a0  An  ",
                "  An+Tag@Example.COM  ", " Abcde1!x ", " Abcde1!x ");

        assertEquals("Nguyễn An", request.username());
        assertEquals("an+tag@example.com", request.email());
        assertEquals(" Abcde1!x ", request.password());
        assertEquals(request.password(), request.confirmPassword());
        assertTrue(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void normalizesEmailForEveryRequestIncludingUnderTurkishLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            String email = "  I.User+Tag@Example.COM  ";
            String expected = "i.user+tag@example.com";
            assertEquals(expected, register(email, "Abcde1!x").email());
            assertEquals(expected, new LoginRequest(email, "old").email());
            assertEquals(expected, new VerifyEmailRequest(email, "012345").email());
            assertEquals(expected, new ResendVerificationRequest(email).email());
            assertEquals(expected, new ForgotPasswordRequest(email).email());
            assertEquals(expected, new ResetPasswordRequest(email, "012345", "Abcde1!x", "Abcde1!x").email());
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "abcdefgh1!", "ABCDEFGH1!", "Abcdefgh!", "Abcdefgh1", "Aa1!bbb", "Aa1ébbbb"})
    void rejectsPasswordsMissingARequiredCondition(String password) {
        assertFalse(VALIDATOR.validate(register("an@example.com", password)).isEmpty());
    }

    @Test
    void countsUnicodeCodePointsRatherThanUtf16Units() {
        assertFalse(VALIDATOR.validate(register("an@example.com", "Aa1!😀bb")).isEmpty());
        assertTrue(VALIDATOR.validate(register("an@example.com", "Aa1!😀bbb")).isEmpty());
    }

    @Test
    void enforcesBcryptUtf8ByteBoundaryWithoutTruncating() {
        assertTrue(VALIDATOR.validate(register("an@example.com", "Aa1!" + "b".repeat(68))).isEmpty());
        assertFalse(VALIDATOR.validate(register("an@example.com", "Aa1!" + "b".repeat(69))).isEmpty());
        assertTrue(VALIDATOR.validate(register("an@example.com", "Aa1!" + "é".repeat(34))).isEmpty());
        assertFalse(VALIDATOR.validate(register("an@example.com", "Aa1!" + "é".repeat(35))).isEmpty());
    }

    @Test
    void loginDoesNotReapplyRegistrationComplexity() {
        LoginRequest request = new LoginRequest(" an@example.com ", " old ");
        assertEquals(" old ", request.password());
        assertTrue(VALIDATOR.validate(request).isEmpty());
        assertFalse(VALIDATOR.validate(new LoginRequest("an@example.com", "é".repeat(37))).isEmpty());
        assertFalse(VALIDATOR.validate(new LoginRequest("an@example.com", " ")).isEmpty());
    }

    @Test
    void requiresExactPasswordConfirmationForRegistrationAndReset() {
        assertFalse(VALIDATOR.validate(new RegisterRequest("An", "an@example.com", "Abcde1!x", "Abcde1!x ")).isEmpty());
        assertFalse(VALIDATOR.validate(new ResetPasswordRequest("an@example.com", "012345", "Abcde1!x", "abcde1!x")).isEmpty());
        assertFalse(VALIDATOR.validate(new ResetPasswordRequest("an@example.com", "012345", "short", "short")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "a@@example.com", "a b@example.com", "a@exa mple.com", "a\u00a0b@example.com"})
    void rejectsInvalidEmail(String email) {
        assertFalse(VALIDATOR.validate(register(email, "Abcde1!x")).isEmpty());
    }

    @Test
    void requiresFieldsAndEnforcesEmailLengthWithoutProviderAllowlist() {
        assertFalse(VALIDATOR.validate(new RegisterRequest(null, null, null, null)).isEmpty());
        assertFalse(VALIDATOR.validate(new RegisterRequest("An", "an@example.com", "Abcde1!x", null)).isEmpty());
        assertFalse(VALIDATOR.validate(new ForgotPasswordRequest("a".repeat(255) + "@example.com")).isEmpty());
        assertTrue(VALIDATOR.validate(new ForgotPasswordRequest("an@university.education")).isEmpty());
    }

    @Test
    void checksNameAfterNormalizationAndRejectsControlCharacters() {
        assertTrue(VALIDATOR.validate(new RegisterRequest("  " + "á".repeat(100) + "  ", "an@example.com", "Abcde1!x", "Abcde1!x")).isEmpty());
        assertFalse(VALIDATOR.validate(new RegisterRequest("á".repeat(101), "an@example.com", "Abcde1!x", "Abcde1!x")).isEmpty());
        assertFalse(VALIDATOR.validate(new RegisterRequest(" \tAn\n ", "an@example.com", "Abcde1!x", "Abcde1!x")).isEmpty());
        assertFalse(VALIDATOR.validate(new RegisterRequest("   ", "an@example.com", "Abcde1!x", "Abcde1!x")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "1234567", "12345a", " 123456", "１２３４５６"})
    void rejectsOtpOutsideSixAsciiDigits(String code) {
        assertFalse(VALIDATOR.validate(new VerifyEmailRequest("an@example.com", code)).isEmpty());
        assertFalse(VALIDATOR.validate(new ResetPasswordRequest("an@example.com", code, "Abcde1!x", "Abcde1!x")).isEmpty());
    }

    @Test
    void retainsOtpLeadingZeroAndRedactsSensitiveToStringValues() {
        VerifyEmailRequest verification = new VerifyEmailRequest("an@example.com", "012345");
        assertEquals("012345", verification.code());
        assertTrue(VALIDATOR.validate(verification).isEmpty());
        assertFalse(verification.toString().contains("012345"));
        assertFalse(register("an@example.com", "Abcde1!x").toString().contains("Abcde1!x"));
        assertFalse(new LoginRequest("an@example.com", "Abcde1!x").toString().contains("Abcde1!x"));
        assertFalse(new ResetPasswordRequest("an@example.com", "012345", "Abcde1!x", "Abcde1!x").toString().contains("Abcde1!x"));
    }

    private RegisterRequest register(String email, String password) {
        return new RegisterRequest("An", email, password, password);
    }
}
