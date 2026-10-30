package com.dumio.backend.validation;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.regex.Pattern;

public final class VocabularyInputNormalizer {
    private static final Pattern EDGE_WHITESPACE = Pattern.compile("(?U)^\\s+|\\s+$");

    private VocabularyInputNormalizer() {
    }

    public static String text(String value) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(EDGE_WHITESPACE.matcher(value).replaceAll(""), Normalizer.Form.NFC);
        return normalized.isEmpty() ? null : normalized;
    }

    public static String[] labels(String[] values) {
        return values == null ? new String[0] : Arrays.stream(values).map(VocabularyInputNormalizer::text).toArray(String[]::new);
    }
}
