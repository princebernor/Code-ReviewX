package com.codereviewx.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.codereviewx.config.CrxProperties;
import com.codereviewx.config.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record RegisterRequest(@Size(max = 64) String name,
                                  @NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(max = 128) String password) {}
    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}
    public record ForgotRequest(@NotBlank @Email @Size(max = 254) String email) {}
    public record ResetRequest(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 128) String password) {}
    public record UserDto(Long id, String email, String name) {
        static UserDto of(User u) { return new UserDto(u.getId(), u.getEmail(), u.getDisplayName()); }
    }

    private final AuthService auth;
    private final AuthCookies cookies;
    private final RateLimiter limiter;
    private final CrxProperties props;

    public AuthController(AuthService auth, AuthCookies cookies, RateLimiter limiter, CrxProperties props) {
        this.auth = auth;
        this.cookies = cookies;
        this.limiter = limiter;
        this.props = props;
    }

    /** Any request issues the XSRF-TOKEN cookie; this endpoint exists so the client can fetch it explicitly. */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf() { return ResponseEntity.noContent().build(); }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest r, HttpServletRequest req, HttpServletResponse res) {
        limiter.check("register", req.getRemoteAddr(), props.rateLimit().registerPerMinute());
        AuthService.Session s = auth.register(r.name(), r.email(), r.password());
        cookies.set(res, s.access(), s.refresh(), s.refreshMaxAge());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("user", UserDto.of(s.user())));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest r, HttpServletRequest req, HttpServletResponse res) {
        limiter.check("login", req.getRemoteAddr(), props.rateLimit().loginPerMinute());
        limiter.check("login-email", AuthService.normalizeEmail(r.email()), props.rateLimit().loginPerEmailPerMinute());
        AuthService.Session s = auth.login(r.email(), r.password());
        cookies.set(res, s.access(), s.refresh(), s.refreshMaxAge());
        return Map.of("user", UserDto.of(s.user()));
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(HttpServletRequest req, HttpServletResponse res) {
        try {
            AuthService.Session s = auth.refresh(AuthCookies.read(req, AuthCookies.REFRESH));
            cookies.set(res, s.access(), s.refresh(), s.refreshMaxAge());
            return Map.of("user", UserDto.of(s.user()));
        } catch (RuntimeException e) {
            cookies.clear(res);
            throw e;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        auth.logout(AuthCookies.read(req, AuthCookies.REFRESH));
        cookies.clear(res);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("user", UserDto.of(auth.user(Long.valueOf(jwt.getSubject()))));
    }

    @PostMapping("/forgot")
    public ResponseEntity<Map<String, Object>> forgot(@Valid @RequestBody ForgotRequest r, HttpServletRequest req) {
        limiter.check("forgot", req.getRemoteAddr(), props.rateLimit().forgotPerMinute());
        Optional<String> token = auth.forgot(r.email());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "If an account exists for that email, a reset link has been sent.");
        if (props.auth().exposeResetToken() && token.isPresent()) body.put("devResetToken", token.get());
        return ResponseEntity.accepted().body(body);
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetRequest r, HttpServletRequest req) {
        limiter.check("reset", req.getRemoteAddr(), props.rateLimit().resetPerMinute());
        auth.reset(r.token(), r.password());
        return ResponseEntity.noContent().build();
    }
}
