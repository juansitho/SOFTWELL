package com.softwell.softwell.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

@Configuration
public class PasswordEncoderConfig {

    private static final Pattern BCRYPT_HASH =
            Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    @Bean
    public PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return bcrypt.encode(rawPassword);
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                if (rawPassword == null || encodedPassword == null) {
                    return false;
                }
                if (BCRYPT_HASH.matcher(encodedPassword).matches()) {
                    return bcrypt.matches(rawPassword, encodedPassword);
                }
                return MessageDigest.isEqual(
                        rawPassword.toString().getBytes(StandardCharsets.UTF_8),
                        encodedPassword.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public boolean upgradeEncoding(String encodedPassword) {
                return !BCRYPT_HASH.matcher(encodedPassword).matches()
                        || bcrypt.upgradeEncoding(encodedPassword);
            }
        };
    }
}
