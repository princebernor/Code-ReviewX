package com.codereviewx;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.HashMap;
import java.util.Map;

import com.codereviewx.config.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Boots the whole app on an in-memory database and drives it like a browser that keeps its cookies. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestSupport {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected RateLimiter limiter;

    @BeforeEach
    void resetLimits() { limiter.reset(); }

    /** A cookie jar per simulated browser. */
    public static final class Browser {
        public final Map<String, Cookie> jar = new HashMap<>();

        public MockHttpServletRequestBuilder with(MockHttpServletRequestBuilder b) {
            jar.values().stream().filter(c -> c.getMaxAge() != 0).forEach(b::cookie);
            return b;
        }

        public void keep(MvcResult r) {
            for (Cookie c : r.getResponse().getCookies()) {
                if (c.getMaxAge() == 0) jar.remove(c.getName());
                else jar.put(c.getName(), c);
            }
        }

        public String cookie(String name) { return jar.containsKey(name) ? jar.get(name).getValue() : null; }
    }

    protected MvcResult call(Browser b, MockHttpServletRequestBuilder req) throws Exception {
        req.with((MockHttpServletRequest r) -> { r.setRemoteAddr("10.0.0." + (b.hashCode() & 0x7f)); return r; });
        MvcResult r = mvc.perform(b.with(req)).andReturn();
        b.keep(r);
        return r;
    }

    protected MvcResult postJson(Browser b, String url, Object body) throws Exception {
        return call(b, post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected MvcResult getJson(Browser b, String url) throws Exception { return call(b, get(url)); }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isEmpty() ? Map.of() : json.readValue(s, Map.class);
    }

    protected Browser signedUp(String email) throws Exception {
        Browser b = new Browser();
        MvcResult r = postJson(b, "/api/auth/register", Map.of("email", email, "password", "Str0ng-Pass!"));
        if (r.getResponse().getStatus() != 201) throw new IllegalStateException(r.getResponse().getContentAsString());
        return b;
    }
}
