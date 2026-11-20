package com.softwell.softwell.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordEncoderConfigTest {

    @Test
    void matchesLegacyPlainTextPasswords() {
        PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

        assertTrue(encoder.matches("old-password", "old-password"));
        assertFalse(encoder.matches("wrong-password", "old-password"));
    }

    @Test
    void hashesNewPasswordsAndMatchesTheHash() {
        PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();
        String encoded = encoder.encode("new-password");

        assertFalse(encoded.contains("new-password"));
        assertTrue(encoder.matches("new-password", encoded));
        assertFalse(encoder.matches("wrong-password", encoded));
    }
}
