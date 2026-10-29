package com.codereviewx.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.codereviewx.config.ApiException;
import com.codereviewx.config.CrxProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    /** Issued tokens. {@code refresh} is the raw value for the cookie; only its hash is stored. */
    public record Session(User user, String access, String refresh, Duration refreshMaxAge) {}

    private static final String INVALID_LOGIN = "Email or password is incorrect.";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final JwtEncoder jwt;
    private final PasswordResetNotifier notifier;
    private final CrxProperties.Auth cfg;
    private final Clock clock;
    /** Compared against when the e-mail is unknown, so both paths cost one BCrypt verification. */
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordResetTokenRepository resetTokens,
                       PasswordEncoder encoder, PasswordPolicy policy, JwtEncoder jwt, PasswordResetNotifier notifier,
                       CrxProperties props, Optional<Clock> clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.resetTokens = resetTokens;
        this.encoder = encoder;
        this.policy = policy;
        this.jwt = jwt;
        this.notifier = notifier;
        this.cfg = props.auth();
        this.clock = clock.orElse(Clock.systemUTC());
        this.dummyHash = encoder.encode(Tokens.random());
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public Session register(String name, String email, String password) {
        String e = normalizeEmail(email);
        policy.check(password, e);
        if (users.findByEmail(e).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "registration_failed",
                "We couldn't create an account with that email. If it's yours, sign in or reset the password.");
        }
        String display = name == null || name.isBlank() ? nameFromEmail(e) : name.trim();
        Instant now = clock.instant();
        User u = users.save(new User(e, display.length() > 64 ? display.substring(0, 64) : display, encoder.encode(password), now));
        return newSession(u, UUID.randomUUID().toString(), now.plus(cfg.refreshTtl()));
    }

    @Transactional
    public Session login(String email, String password) {
        Optional<User> u = users.findByEmail(normalizeEmail(email));
        String hash = u.map(User::getPasswordHash).orElse(dummyHash);
        boolean ok = encoder.matches(password == null ? "" : password, hash);
        if (u.isEmpty() || !ok) throw ApiException.unauthorized("invalid_credentials", INVALID_LOGIN);
        return newSession(u.get(), UUID.randomUUID().toString(), clock.instant().plus(cfg.refreshTtl()));
    }

    /**
     * Rotates a refresh token. Presenting a token that was already rotated is treated as theft and revokes the
     * whole family, except within a few seconds of the rotation (two tabs refreshing at once).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawRefresh) {
        if (rawRefresh == null) throw ApiException.unauthorized("no_session", "Sign in to continue.");
        RefreshToken t = refreshTokens.findByTokenHash(Tokens.sha256(rawRefresh))
            .orElseThrow(() -> ApiException.unauthorized("invalid_session", "Your session has ended. Sign in again."));
        Instant now = clock.instant();
        if (t.isRevoked()) {
            boolean benignRace = t.getReplacedBy() != null && t.getRevokedAt().plus(cfg.refreshReuseGrace()).isAfter(now);
            if (!benignRace) {
                refreshTokens.revokeFamily(t.getFamilyId(), now);
                throw ApiException.unauthorized("session_reused", "Your session has ended. Sign in again.");
            }
        }
        if (!now.isBefore(t.getExpiresAt())) throw ApiException.unauthorized("session_expired", "Your session has expired. Sign in again.");
        User u = users.findById(t.getUserId())
            .orElseThrow(() -> ApiException.unauthorized("invalid_session", "Your session has ended. Sign in again."));
        // The family keeps its original absolute expiry, so rotation never extends a session past refresh-ttl.
        Session s = newSession(u, t.getFamilyId(), t.getExpiresAt());
        t.revoke(now, Tokens.sha256(s.refresh()));
        return s;
    }

    @Transactional
    public void logout(String rawRefresh) {
        if (rawRefresh == null) return;
        refreshTokens.findByTokenHash(Tokens.sha256(rawRefresh))
            .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), clock.instant()));
    }

    @Transactional(readOnly = true)
    public User user(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.unauthorized("invalid_session", "Sign in to continue."));
    }

    /** Returns the raw reset token when an account exists. Callers must not reveal whether it did. */
    @Transactional
    public Optional<String> forgot(String email) {
        Optional<User> u = users.findByEmail(normalizeEmail(email));
        if (u.isEmpty()) return Optional.empty();
        Instant now = clock.instant();
        resetTokens.deleteForUser(u.get().getId());
        String raw = Tokens.random();
        resetTokens.save(new PasswordResetToken(u.get().getId(), Tokens.sha256(raw), now, now.plus(cfg.resetTtl())));
        notifier.sendResetLink(u.get(), cfg.resetLinkBase() + "?reset=" + raw);
        return Optional.of(raw);
    }

    @Transactional
    public void reset(String rawToken, String newPassword) {
        Instant now = clock.instant();
        PasswordResetToken t = rawToken == null ? null : resetTokens.findByTokenHash(Tokens.sha256(rawToken)).orElse(null);
        if (t == null || !t.isUsable(now)) {
            throw ApiException.badRequest("invalid_token", "This reset link is invalid or has expired. Request a new one.");
        }
        User u = users.findById(t.getUserId()).orElseThrow(ApiException::notFound);
        policy.check(newPassword, u.getEmail());
        u.changePassword(encoder.encode(newPassword), now);
        t.markUsed(now);
        refreshTokens.revokeAllForUser(u.getId(), now); // sign out everywhere
    }

    /** Called only from the transactional methods above. */
    private Session newSession(User u, String family, Instant familyExpiry) {
        Instant now = clock.instant();
        String raw = Tokens.random();
        refreshTokens.save(new RefreshToken(u.getId(), Tokens.sha256(raw), family, now, familyExpiry));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(cfg.issuer()).subject(String.valueOf(u.getId()))
            .issuedAt(now).expiresAt(now.plus(cfg.accessTtl())).build();
        String access = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Session(u, access, raw, Duration.between(now, familyExpiry));
    }

    static String nameFromEmail(String email) {
        String local = email.split("@")[0].replaceAll("[._-]+", " ").trim();
        StringBuilder sb = new StringBuilder();
        for (String w : local.split(" ")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        String n = sb.toString();
        return n.isEmpty() ? "User" : n.length() > 32 ? n.substring(0, 32) : n;
    }
}
