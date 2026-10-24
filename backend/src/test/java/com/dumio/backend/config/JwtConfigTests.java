package com.dumio.backend.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtConfigTests {

    private final JwtConfig config = new JwtConfig();

    @Test
    void rejectsMissingMalformedOrShortKeyInsteadOfUsingDefaultSecret() {
        assertThrows(IllegalArgumentException.class, () -> config.jwtSecretKey(""));
        assertThrows(IllegalArgumentException.class, () -> config.jwtSecretKey("not-base64!"));
        assertThrows(IllegalArgumentException.class,
                () -> config.jwtSecretKey(Base64.getEncoder().encodeToString(new byte[31])));
    }

    @Test
    void loadsConfiguredKeyWithoutGeneratingAnotherOneOnRestart() {
        String encoded = Base64.getEncoder().encodeToString(new byte[32]);
        assertArrayEquals(config.jwtSecretKey(encoded).getEncoded(), config.jwtSecretKey(encoded).getEncoded());
        assertEquals("HmacSHA256", config.jwtSecretKey(encoded).getAlgorithm());
    }
}
