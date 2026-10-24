package com.dumio.backend.validation;

import java.util.Locale;
import java.util.regex.Pattern;

public final class AuthInputNormalizer {

    private static final Pattern EDGE_WHITESPACE = Pattern.compile("(?U)^\\s+|\\s+$");
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");

    private AuthInputNormalizer() {
    }

    public static String email(String value) {
        return value == null ? null : trim(value).toLowerCase(Locale.ROOT);
    }

    public static String username(String value) {
        if (value == null || value.codePoints().anyMatch(Character::isISOControl)) {
            return value;
        }
        return WHITESPACE.matcher(trim(value)).replaceAll(" ");
    }

    private static String trim(String value) {
        return EDGE_WHITESPACE.matcher(value).replaceAll("");
    }
}
