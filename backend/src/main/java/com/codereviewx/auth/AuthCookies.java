package com.codereviewx.auth;

import java.time.Duration;

import com.codereviewx.config.CrxProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Writes the HttpOnly session cookies. The refresh cookie is only sent to /api/auth. */
@Component
public class AuthCookies {
    public static final String ACCESS = "crx_at";
    public static final String REFRESH = "crx_rt";

    private final CrxProperties.Auth cfg;

    public AuthCookies(CrxProperties props) { this.cfg = props.auth(); }

    public void set(HttpServletResponse res, String access, String refresh, Duration refreshMaxAge) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS, access, "/api", cfg.accessTtl()).toString());
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH, refresh, "/api/auth", refreshMaxAge).toString());
    }

    public void clear(HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS, "", "/api", Duration.ZERO).toString());
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH, "", "/api/auth", Duration.ZERO).toString());
    }

    public static String read(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) if (name.equals(c.getName()) && !c.getValue().isBlank()) return c.getValue();
        return null;
    }

    private ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(cfg.secureCookies()).sameSite(cfg.sameSite())
            .path(path).maxAge(maxAge).build();
    }
}
