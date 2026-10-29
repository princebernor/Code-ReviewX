package com.codereviewx.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.codereviewx.config.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Server-side password rules. The 8-character minimum matches the existing sign-in form; the 72-byte maximum
 * is BCrypt's input limit, so longer passwords are rejected instead of silently truncated.
 */
@Component
public class PasswordPolicy {
    public static final int MIN = 8;
    public static final int MAX_BYTES = 72;
    private final Set<String> common = new HashSet<>();

    public PasswordPolicy() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource("security/common-passwords.txt").getInputStream(), StandardCharsets.UTF_8))) {
            r.lines().map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .forEach(s -> common.add(s.toLowerCase(Locale.ROOT)));
        } catch (IOException e) {
            throw new IllegalStateException("common-passwords.txt is missing", e);
        }
    }

    public void check(String password, String email) {
        String why = problem(password, email);
        if (why != null) throw ApiException.badRequest("weak_password", why);
    }

    String problem(String pw, String email) {
        if (pw == null || pw.length() < MIN) return "Use at least " + MIN + " characters.";
        if (pw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return "Use at most 72 bytes (about 64 characters).";
        String lower = pw.toLowerCase(Locale.ROOT);
        if (common.contains(lower)) return "That password is too common. Choose something less predictable.";
        int classes = 0;
        if (pw.chars().anyMatch(Character::isLowerCase)) classes++;
        if (pw.chars().anyMatch(Character::isUpperCase)) classes++;
        if (pw.chars().anyMatch(Character::isDigit)) classes++;
        if (pw.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) classes++;
        if (classes < 2) return "Mix at least two of: lowercase, uppercase, digits, symbols.";
        if (pw.chars().distinct().count() < 4) return "Use more varied characters.";
        if (email != null) {
            String local = email.toLowerCase(Locale.ROOT).split("@")[0];
            if (local.length() >= 4 && lower.contains(local)) return "Don't include your email name in the password.";
        }
        return null;
    }
}
