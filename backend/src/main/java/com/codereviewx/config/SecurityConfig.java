package com.codereviewx.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.codereviewx.auth.AuthCookies;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Cookie-based authentication: a short-lived HS256 access JWT in an HttpOnly cookie, a rotating opaque refresh
 * token in a second HttpOnly cookie scoped to /api/auth, and double-submit CSRF protection (XSRF-TOKEN cookie,
 * X-XSRF-TOKEN header) for every state-changing request.
 */
@Configuration
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    static final String CSP = "default-src 'self'; script-src 'self'; "
        + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; "
        + "img-src 'self' data: blob:; media-src 'self'; connect-src 'self'; object-src 'none'; "
        + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    /** Endpoints that read the access cookie. Others ignore it so a stale cookie never breaks public calls. */
    static boolean usesAccessToken(String path) {
        return path.equals("/api/auth/me") || path.equals("/api/review") || path.startsWith("/api/reviews")
            || path.equals("/api/progress") || path.startsWith("/api/run");
    }

    @Bean
    SecretKey jwtKey(CrxProperties props, Environment env) {
        String secret = props.auth().jwtSecret();
        if (secret == null || secret.isBlank()) {
            if (env.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("CRX_JWT_SECRET must be set in production (at least 32 characters).");
            }
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            log.warn("CRX_JWT_SECRET is not set: using a random key for this run. Sessions end when the server restarts.");
            return new SecretKeySpec(b, "HmacSHA256");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("CRX_JWT_SECRET must be at least 32 bytes.");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, CrxProperties props) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),
            new JwtIssuerValidator(props.auth().issuer())));
        return d;
    }

    @Bean
    PasswordEncoder passwordEncoder(CrxProperties props) {
        return new BCryptPasswordEncoder(props.auth().bcryptStrength());
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, CrxProperties props, ObjectMapper json) throws Exception {
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookiePath("/");
        csrfRepo.setCookieCustomizer(c -> c.sameSite(props.auth().sameSite()).secure(props.auth().secureCookies()));
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // load eagerly so the cookie is always issued

        BearerTokenResolver cookieResolver = (HttpServletRequest req) -> {
            if (!usesAccessToken(req.getRequestURI().substring(req.getContextPath().length()))) return null;
            Cookie[] cookies = req.getCookies();
            if (cookies == null) return null;
            for (Cookie c : cookies) if (AuthCookies.ACCESS.equals(c.getName()) && !c.getValue().isBlank()) return c.getValue();
            return null;
        };

        http
            .csrf(c -> c.csrfTokenRepository(csrfRepo).csrfTokenRequestHandler(csrfHandler))
            .cors(props.cors() != null && props.cors().allowedOrigins() != null && !props.cors().allowedOrigins().isEmpty()
                ? Customizer.withDefaults() : c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .requestCache(r -> r.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/me", "/api/reviews", "/api/reviews/**", "/api/progress", "/api/run", "/api/run/**").authenticated()
                .anyRequest().permitAll())
            .oauth2ResourceServer(o -> o
                .bearerTokenResolver(cookieResolver)
                .jwt(Customizer.withDefaults())
                .authenticationEntryPoint((req, res, ex) -> write(json, res, HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue.")))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> write(json, res, HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue."))
                .accessDeniedHandler((req, res, ex) -> {
                    if (ex instanceof CsrfException) write(json, res, HttpStatus.FORBIDDEN, "csrf_failed", "Refresh the page and try again.");
                    else write(json, res, HttpStatus.FORBIDDEN, "forbidden", "You don't have access to this.");
                }))
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives(CSP))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CrxProperties props) {
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        List<String> origins = props.cors() == null || props.cors().allowedOrigins() == null ? List.of() : props.cors().allowedOrigins();
        if (!origins.isEmpty()) {
            CorsConfiguration c = new CorsConfiguration();
            c.setAllowedOrigins(origins); // explicit list only; never "*" with credentials
            c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
            c.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
            c.setAllowCredentials(true);
            c.setMaxAge(600L);
            src.registerCorsConfiguration("/api/**", c);
        }
        return src;
    }

    private static void write(ObjectMapper json, HttpServletResponse res, HttpStatus status, String code, String msg) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getOutputStream(), GlobalExceptionHandler.body(status, code, msg));
    }
}
