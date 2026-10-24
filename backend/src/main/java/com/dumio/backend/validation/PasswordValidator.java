package com.dumio.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    private boolean checkComplexity;

    @Override
    public void initialize(ValidPassword annotation) {
        checkComplexity = annotation.checkComplexity();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // Required fields are checked by @NotBlank.
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        if (!checkComplexity) {
            return true;
        }
        return value.codePointCount(0, value.length()) >= 8
                && value.chars().anyMatch(c -> c >= 'A' && c <= 'Z')
                && value.chars().anyMatch(c -> c >= 'a' && c <= 'z')
                && value.chars().anyMatch(c -> c >= '0' && c <= '9')
                && value.chars().anyMatch(c -> c >= '!' && c <= '~'
                        && !(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')
                        && !(c >= '0' && c <= '9'));
    }
}
