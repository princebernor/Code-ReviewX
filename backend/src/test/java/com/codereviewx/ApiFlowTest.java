package com.codereviewx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class ApiFlowTest extends ApiTestSupport {
    static final String CODE = "def find(nums, t):\n    for i in nums:\n        for j in nums:\n            if i + j == t:\n                return True\n    return False\n";

    @Test
    @SuppressWarnings("unchecked")
    void languagesListsExactlyTheFrontendSix() throws Exception {
        List<Map<String, Object>> langs = json.readValue(getJson(new Browser(), "/api/languages").getResponse().getContentAsString(), List.class);
        assertThat(langs).extracting(l -> l.get("id")).containsExactly("c", "cpp", "java", "python", "javascript", "go");
        assertThat(langs).allMatch(l -> Boolean.TRUE.equals(l.get("available")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anonymousReviewWorksButIsNotSaved() throws Exception {
        MvcResult r = postJson(new Browser(), "/api/review", Map.of("code", CODE, "lang", "python", "save", true));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> b = body(r);
        assertThat(b.get("saved")).isEqualTo(false);
        assertThat(b).containsKeys("overall", "weighted", "efficiency", "time", "space", "categories", "counts", "findings", "complexity", "diagnostics");
        assertThat(b.get("time")).isEqualTo("O(n²)");
        Map<String, Object> f = ((List<Map<String, Object>>) b.get("findings")).get(0);
        assertThat(f).containsKeys("uid", "cat", "sev", "title", "line", "end", "original", "suggested", "kind", "explain", "why", "changes", "benefits", "tradeoffs", "ast", "confidence");
    }

    @Test
    void reviewErrorsUseTheFrontendCodes() throws Exception {
        assertThat(body(postJson(new Browser(), "/api/review", Map.of("code", "x = 1", "lang", "python"))).get("error")).isEqualTo("short");
        assertThat(body(postJson(new Browser(), "/api/review", Map.of("code", CODE, "lang", "cobol"))).get("error")).isEqualTo("unsupported_language");
        MvcResult big = postJson(new Browser(), "/api/review", Map.of("code", "x = 1\n".repeat(90_000), "lang", "python"));
        assertThat(big.getResponse().getStatus()).isEqualTo(413);
    }

    @Test
    @SuppressWarnings("unchecked")
    void savedReviewsBelongToTheirOwnerOnly() throws Exception {
        Browser alice = signedUp("alice@example.com"), bob = signedUp("bob@example.com");
        Map<String, Object> saved = body(postJson(alice, "/api/review", Map.of("code", CODE, "lang", "python", "save", true, "filename", "pairs.py")));
        assertThat(saved.get("saved")).isEqualTo(true);
        String id = (String) saved.get("id");
        assertThat(saved).containsEntry("file", "pairs.py").containsEntry("code", CODE);

        Map<String, Object> list = body(getJson(alice, "/api/reviews"));
        assertThat((List<Map<String, Object>>) list.get("items")).extracting(m -> m.get("id")).containsExactly(id);
        assertThat(((List<?>) body(getJson(bob, "/api/reviews")).get("items"))).isEmpty();

        assertThat(getJson(bob, "/api/reviews/" + id).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(bob, delete("/api/reviews/" + id).with(csrf())).getResponse().getStatus()).isEqualTo(404);
        assertThat(getJson(alice, "/api/reviews/" + id).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(alice, delete("/api/reviews/" + id).with(csrf())).getResponse().getStatus()).isEqualTo(204);
        assertThat(getJson(alice, "/api/reviews/" + id).getResponse().getStatus()).isEqualTo(404);
        assertThat(getJson(new Browser(), "/api/reviews").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void importedReviewsAreValidatedAndOwned() throws Exception {
        Browser u = signedUp("importer@example.com");
        Map<String, Object> review = Map.of("lang", "python", "overall", 81, "efficiency", 58, "time", "O(n²)", "space", "O(n)",
            "engine", "demo-rules-v1", "findings", List.of(), "counts", Map.of("critical", 0), "categories", Map.of(), "sample", true);
        MvcResult ok = postJson(u, "/api/reviews", review);
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(ok)).containsEntry("sample", true).containsEntry("engine", "demo-rules-v1");
        assertThat(postJson(u, "/api/reviews", Map.of("lang", "python", "overall", 500)).getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson(u, "/api/reviews", Map.of("lang", "fortran", "overall", 5)).getResponse().getStatus()).isEqualTo(400);
        assertThat(((List<?>) body(getJson(u, "/api/reviews")).get("items"))).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void progressRoundTripsPerUserInTheFrontendShape() throws Exception {
        Browser a = signedUp("prog-a@example.com"), b = signedUp("prog-b@example.com");
        Map<String, Object> state = Map.of("progress", Map.of("12", Map.of("status", "solved", "at", 1700000000000L)),
            "activity", List.of(Map.of("type", "solved", "ref", "12", "at", 1700000000000L)));
        MvcResult r = call(a, put("/api/progress").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(state)));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> got = body(getJson(a, "/api/progress"));
        assertThat((Map<String, Object>) got.get("progress")).containsKey("12");
        assertThat(((Map<String, Object>) body(getJson(b, "/api/progress")).get("progress"))).isEmpty();

        Map<String, Object> bad = Map.of("progress", Map.of("12", Map.of("status", "mastered", "at", 1)));
        assertThat(call(a, put("/api/progress").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(bad)))
            .getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> stale = Map.of("progress", Map.of(), "version", 99);
        assertThat(call(a, put("/api/progress").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(stale)))
            .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @SuppressWarnings("unchecked")
    void astAndCompareEndpoints() throws Exception {
        Map<String, Object> ast = body(postJson(new Browser(), "/api/ast", Map.of("code", CODE, "lang", "python")));
        assertThat(((Map<String, Object>) ast.get("ast")).get("kind")).isEqualTo("PROGRAM");
        Map<String, Object> cmp = body(postJson(new Browser(), "/api/compare", Map.of(
            "a", Map.of("code", CODE, "lang", "python"),
            "b", Map.of("code", CODE.replace("nums", "values"), "lang", "python"))));
        assertThat(cmp.get("similarity")).isEqualTo(1.0);
    }

    @Test
    void explanationModeIsDeterministic() throws Exception {
        Map<String, Object> r = body(postJson(new Browser(), "/api/review", Map.of("code", CODE, "lang", "python", "explain", true)));
        assertThat(r.get("explanation").toString()).contains("provider=deterministic");
    }

    @Test
    void onlyTheFrontendIsServedStatically() throws Exception {
        assertThat(mvc.perform(get("/index.html")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/js/api-client.js")).andReturn().getResponse().getStatus()).isEqualTo(200);
        MvcResult page = mvc.perform(get("/review.html")).andReturn();
        assertThat(page.getResponse().getHeader("Content-Security-Policy")).contains("script-src 'self'");
        for (String hidden : List.of("/README.md", "/backend/pom.xml", "/docs/integration-map.md", "/backend/src/main/resources/application.yml",
                "/backend/.env.example")) {
            assertThat(mvc.perform(get(hidden)).andReturn().getResponse().getStatus()).as(hidden).isEqualTo(404);
        }
    }

    @Test
    void noSecretsShipInBrowserFiles() throws Exception {
        for (String f : List.of("/js/api-client.js", "/js/auth.js", "/js/main.js", "/index.html")) {
            String s = mvc.perform(get(f)).andReturn().getResponse().getContentAsString();
            assertThat(s).as(f).doesNotContainIgnoringCase("jwt-secret").doesNotContain("CRX_JWT_SECRET").doesNotContain("sk-ant").doesNotContain("Bearer ");
        }
    }
}
