package com.codereviewx.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.Map;

import com.codereviewx.ApiTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MvcResult;

class AuthApiTest extends ApiTestSupport {
    @Autowired JwtEncoder encoder;
    @Autowired UserRepository users;

    @Test
    void registerSetsHttpOnlyCookiesAndNeverReturnsSecrets() throws Exception {
        Browser b = new Browser();
        MvcResult r = postJson(b, "/api/auth/register", Map.of("name", "Ada", "email", "Ada@Example.com", "password", "Str0ng-Pass!"));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        String body = r.getResponse().getContentAsString();
        assertThat(body).contains("\"email\":\"ada@example.com\"").doesNotContain("password").doesNotContain("$2a$").doesNotContain("Hash");
        for (String name : new String[] {AuthCookies.ACCESS, AuthCookies.REFRESH}) {
            Cookie c = r.getResponse().getCookie(name);
            assertThat(c).isNotNull();
            assertThat(c.isHttpOnly()).isTrue();
        }
        assertThat(r.getResponse().getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith(AuthCookies.REFRESH) && h.contains("Path=/api/auth") && h.contains("SameSite=Strict"));
        assertThat(users.findByEmail("ada@example.com").orElseThrow().getPasswordHash()).startsWith("$2a$").doesNotContain("Str0ng");
        assertThat(body(getJson(b, "/api/auth/me"))).containsKey("user");
    }

    @Test
    void duplicateAndWeakRegistrationsAreRejected() throws Exception {
        signedUp("dup@example.com");
        MvcResult dup = postJson(new Browser(), "/api/auth/register", Map.of("email", "DUP@example.com", "password", "Str0ng-Pass!"));
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        for (String weak : new String[] {"password1", "short1", "alllowercase", "aaaaaaaA1"}) {
            MvcResult r = postJson(new Browser(), "/api/auth/register", Map.of("email", "w" + weak.length() + weak.hashCode() + "@example.com", "password", weak));
            assertThat(r.getResponse().getStatus()).as(weak).isEqualTo(400);
            assertThat(body(r).get("error")).as(weak).isEqualTo("weak_password");
        }
        MvcResult bad = postJson(new Browser(), "/api/auth/register", Map.of("email", "not-an-email", "password", "Str0ng-Pass!"));
        assertThat(body(bad).get("error")).isEqualTo("validation");
    }

    @Test
    void loginSucceedsOnlyWithTheRightPasswordAndResponsesDoNotRevealAccounts() throws Exception {
        signedUp("login@example.com");
        Browser b = new Browser();
        MvcResult wrong = postJson(b, "/api/auth/login", Map.of("email", "login@example.com", "password", "Wrong-Pass1"));
        MvcResult unknown = postJson(b, "/api/auth/login", Map.of("email", "nobody@example.com", "password", "Wrong-Pass1"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(b.cookie(AuthCookies.ACCESS)).isNull();
        assertThat(getJson(b, "/api/auth/me").getResponse().getStatus()).isEqualTo(401);

        MvcResult ok = postJson(b, "/api/auth/login", Map.of("email", "LOGIN@example.com", "password", "Str0ng-Pass!"));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson(b, "/api/auth/me").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void stateChangingRequestsRequireCsrfToken() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"a@example.com\",\"password\":\"x\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("csrf_failed");
    }

    @Test
    void expiredOrForgedAccessTokensAreRejected() throws Exception {
        Long id = Long.valueOf(String.valueOf(((Map<?, ?>) body(postJson(new Browser(), "/api/auth/register",
            Map.of("email", "exp@example.com", "password", "Str0ng-Pass!"))).get("user")).get("id")));
        Instant past = Instant.now().minusSeconds(3600);
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().issuer("codereviewx").subject(String.valueOf(id)).issuedAt(past.minusSeconds(900)).expiresAt(past).build())).getTokenValue();
        assertThat(mvc.perform(get("/api/auth/me").cookie(new Cookie(AuthCookies.ACCESS, expired))).andReturn().getResponse().getStatus()).isEqualTo(401);
        String forged = expired.substring(0, expired.lastIndexOf('.') + 1) + "AAAA";
        assertThat(mvc.perform(get("/api/auth/me").cookie(new Cookie(AuthCookies.ACCESS, forged))).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void refreshRotatesAndReplayingAnOldTokenRevokesTheWholeFamily() throws Exception {
        Browser b = signedUp("rotate@example.com");
        String first = b.cookie(AuthCookies.REFRESH);
        assertThat(postJson(b, "/api/auth/refresh", Map.of()).getResponse().getStatus()).isEqualTo(200);
        String second = b.cookie(AuthCookies.REFRESH);
        assertThat(second).isNotEqualTo(first);

        Browser thief = new Browser();
        thief.jar.put(AuthCookies.REFRESH, new Cookie(AuthCookies.REFRESH, first));
        MvcResult replay = postJson(thief, "/api/auth/refresh", Map.of());
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(replay).get("error")).isEqualTo("session_reused");
        // The legitimate holder's newer token died with the family.
        assertThat(postJson(b, "/api/auth/refresh", Map.of()).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        Browser b = signedUp("logout@example.com");
        String rt = b.cookie(AuthCookies.REFRESH);
        assertThat(postJson(b, "/api/auth/logout", Map.of()).getResponse().getStatus()).isEqualTo(204);
        assertThat(b.cookie(AuthCookies.REFRESH)).isNull();
        Browser again = new Browser();
        again.jar.put(AuthCookies.REFRESH, new Cookie(AuthCookies.REFRESH, rt));
        assertThat(postJson(again, "/api/auth/refresh", Map.of()).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void passwordResetIsSingleUseAndSignsOutEverywhere() throws Exception {
        Browser b = signedUp("reset@example.com");
        String oldRefresh = b.cookie(AuthCookies.REFRESH);
        Map<String, Object> unknown = body(postJson(new Browser(), "/api/auth/forgot", Map.of("email", "ghost@example.com")));
        Map<String, Object> known = body(postJson(new Browser(), "/api/auth/forgot", Map.of("email", "reset@example.com")));
        assertThat(unknown.get("message")).isEqualTo(known.get("message"));
        assertThat(unknown).doesNotContainKey("devResetToken");
        String token = (String) known.get("devResetToken");
        assertThat(token).isNotBlank();

        assertThat(body(postJson(new Browser(), "/api/auth/reset", Map.of("token", token, "password", "password1"))).get("error")).isEqualTo("weak_password");
        assertThat(postJson(new Browser(), "/api/auth/reset", Map.of("token", token, "password", "N3w-Secret!")).getResponse().getStatus()).isEqualTo(204);
        assertThat(body(postJson(new Browser(), "/api/auth/reset", Map.of("token", token, "password", "An0ther-One!"))).get("error")).isEqualTo("invalid_token");

        assertThat(postJson(new Browser(), "/api/auth/login", Map.of("email", "reset@example.com", "password", "Str0ng-Pass!")).getResponse().getStatus()).isEqualTo(401);
        assertThat(postJson(new Browser(), "/api/auth/login", Map.of("email", "reset@example.com", "password", "N3w-Secret!")).getResponse().getStatus()).isEqualTo(200);
        Browser old = new Browser();
        old.jar.put(AuthCookies.REFRESH, new Cookie(AuthCookies.REFRESH, oldRefresh));
        assertThat(postJson(old, "/api/auth/refresh", Map.of()).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void loginIsRateLimitedPerEmail() throws Exception {
        signedUp("limit@example.com");
        int last = 0;
        for (int i = 0; i < 6; i++) {
            last = postJson(new Browser(), "/api/auth/login", Map.of("email", "limit@example.com", "password", "Wrong-Pass" + i)).getResponse().getStatus();
        }
        assertThat(last).isEqualTo(429);
        // Even the right password is refused while limited.
        assertThat(postJson(new Browser(), "/api/auth/login", Map.of("email", "limit@example.com", "password", "Str0ng-Pass!")).getResponse().getStatus()).isEqualTo(429);
    }
}
